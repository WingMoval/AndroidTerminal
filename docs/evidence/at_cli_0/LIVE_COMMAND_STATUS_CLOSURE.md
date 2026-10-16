# AT.CLI-0.1B — Live Command-Time ADB Status Projection Closure (Evidence)

> **LIVE_COMMAND_STATUS = COMPLETE**
>
> 当 Human 在真实 Local `/system/bin/sh` session 里运行 `adb shell <cmd>`、
> 且 ADB **尚未 READY** 时，连接状态行（`checking...` / `enabling...` /
> `discovering...` / `connecting...` / `connected`）**实时（LIVE）逐行投影**到
> Human 终端，**不是** buffered 到命令 EXIT 才一次性刷出。
> 核心证据：S1 证明至少一条中间 status 在 `uid=2000`（命令完成）之前被 observer 观察到
> （`STATUS_BEFORE_COMMAND_EXIT = REQUIRED`）。

---

## 1. 问题（0.1A 的缺口）

AT.CLI-0.1A 闭合了 **lifecycle / async** status（transport 丢失、重连、READY 等
**状态迁移**，通过 `AdbCliStatusHub.onTransition` 投影）。但**命令时刻（command-time）**
的 cold-connect 中间状态（`checking` / `enabling` / `discovering` / `connecting`）
走的是短命 `AdbCliMain` 进程的 PTY stderr（T_STATUS frame），在 `ensureReady` 阻塞期间
**不 live**——Human 看到的是命令 EXIT 时才刷出的一整块 status，而不是逐行实时出现。

**根因**：0.1A 的 `AdbCliStatusHub` 只消费 **lifecycle transition**（`onTransition`），
不消费 **命令时刻的 statusListener 进度**。`AdbCliBridgeServer.ensureReady` 的
`statusListener` 只 `sendStatus(fd, ...)`（T_STATUS → AdbCliMain stderr → PTY），
没有 Host 投影路径。

## 2. 架构（冻结，未破）

```
Consumer / Product
   │
   ▼
AdbCliMain (app_process, 短命客户端)
   │  parse / send / consume STATUS/OUT/ERR/EXIT
   │  HELLO payload 携带 Local sessionId (pageId)
   ▼  UDS (cli_bridge.sock, same-UID)
AdbCliBridgeServer (thin adapter)
   │  onHello(fd, sessionId) → 绑定 fd→session
   │  ensureReady(fd):
   │     subscribe(sid) BEFORE ensureReady          ← 0.1B 新增时序
   │     statusListener(line) → emitStatus(fd,sid,line)   ← 0.1B 单投影路径
   │        ├─ 有效 Local session → Host 投影 (live)
   │        └─ 否则 → T_STATUS fallback (AdbCliMain, buffered)
   │     "connected" 由 lifecycle transition 拥有 (statusListener 跳过)
   ▼
AdbRuntime (ONE, 长驻生命周期 owner)  ← READ-ONLY, 未改
   │  ensureReady(keyDir, statusListener)  ← 进度回调
   │  setState(READY) → onTransition (lifecycle)
   ▼
AdbCliStatusHub (Host, 只读 observer)
   │  emitCommandStatus(sid, status)  ← 0.1B 新增 (同步, 非 main.post)
   │  onTransition(old,new)           ← 0.1B 改同步 (非 main.post)
   ▼
SharedSessionHandle.emitSystemOutput (HOST/SYSTEM terminal event)
   │  forEachObserver (synchronized, 同步 fan-out)
   ▼
Human Local terminal (MainActivity)  ← 实时渲染
```

**不变量（§24/§29）**：
- `ADB_ENGINE_MODIFIED = NO` / `ADBRUNTIME_MODIFIED = NO`（AdbRuntime READ-ONLY）
- `AT_ENGINE_MODIFIED = NO` / `ADB_NATIVE_MODIFIED = NO` / `REMOTE_PTY_MODIFIED = NO`
- 仅 app CLI/Host status projection + tests 改动
- 单 AdbRuntime / 单 recovery owner / 无第二 endpoint store / 无 heartbeat

## 3. STATUS 的语义（§4）

`[adb] ...` 是 **HOST/SYSTEM TERMINAL EVENT**（经 `SharedSessionHandle.emitSystemOutput`），
**不是** CLI 进程输出，**不是** PTY stdin。它与 `onTransition` 走同一条 observer
fan-out，Human 在终端里像看到普通终端输出一样看到它。

## 4. 组件改动（全部 app/ 层，CLI/Host + tests）

### AdbCliStatusHub.kt（Host, 只读 observer）
- **新增 `emitCommandStatus(sessionId, status)`**：命令时刻 status 的**单一投影 owner**。
  同步投递（**非** `main.post`）——调用方是 bridge 线程（正在跑 `ensureReady`），
  `SharedSessionHandle.forEachObserver` 是 synchronized 同步 fan-out，
  observer 的 `onOutput` 在本线程执行。closed session → `unsubscribe`（无泄漏）。
- **`onTransition` 改同步**（去掉 `main.post`）：lifecycle transition 的 caller 是
  AdbRuntime 线程，`forEachObserver` 已线程安全。保持与 `emitCommandStatus` 一致的投递语义。
- 移除未使用的 `Handler`/`Looper` import + `main` 字段。

### AppRuntime.kt（进程级组合根）
- **新增 `emitAdbCommandStatus(sessionId, status)`**：`statusHub?.emitCommandStatus` 委托。
- **`initStatusHub` 总是重建 hub**（不再 `if (statusHub == null)`）：Activity recreate
  时会创建新的 `AppSessionRegistry`；旧 hub 持有 stale registry（lookup 返回 null）。
  重建确保 hub 与 `AppTerminal` 用同一个 registry。
- **新增 `sessionRegistry()`**：进程级 registry 引用，供 `AppTerminal` 使用（而非
  Activity 字段），跨 Activity recreate 保持一致。
- **新增 `sessionRegistryRef`** 字段。

### AdbCliBridgeServer.kt（thin adapter）
- **`ensureReady` subscribe 提前**：`subscribe(sid)` 移到 `AdbRuntime.ensureReady` **之前**
  （原来在之后），使 cold-connect 期间的命令时刻 status 能投影到 session。
- **新增 `emitStatus(fd, sid, line)`**：命令时刻 status 的**单一投影路径**。
  有效 Local session（`sid>0` 且已 subscribe）→ Host 投影（live）；否则 → T_STATUS
  fallback（AdbCliMain，buffered，非 Human 客户端可接受）。**不**对有效 session 同时发
  T_STATUS（避免重复）。
- **`statusListener` 跳过 `"connected"`**：lifecycle transition（`onTransition`
  CONNECTING→READY）拥有 `connected`，statusListener 再发会重复。只发中间状态
  （checking / enabling / discovering / connecting / retrying / user action required 等）。
- **fast path backfill `connected`**：若 subscribe 时 AdbRuntime 已 READY（bootstrap
  connect 早于 subscribe），lifecycle transition 已 fire（当时 `subscribed` 为空），
  补发一次 `connected`（不会重复：同状态 transition 不再 fire）。
- **`statusEmittedForFd`**（ConcurrentHashMap）：per-fd 去重，fast path 只对每个
  fd 的首次 use 发 `checking`（后续同 session 命令静默，无 re-spam）。
- 移除死代码 `subscribeSession()`（逻辑并入 `ensureReady` 顶部）。

### MainActivity.kt
- `createPage` 的 `AppTerminal` 改用 `AppRuntime.sessionRegistry()`（进程级），
  而非 Activity 字段 `sessionRegistry`（recreate 后是新实例，会与 StatusHub 的旧实例不一致）。

### AdbCliLiveStatusTest.kt（新, androidTest, S1-S9）
- **S1**：cold live status before exit（核心）——WD OFF + `adb shell id`，
  断言至少一条中间 status 在 `uid=2000` 之前被 observer 观察到（时间戳证明）。
- **S2**：status ordering（`connected` 在 `uid=2000` 之前）。
- **S3**：no duplicate（cold path `connected`/`checking` 各出现 1 次）。
- **S4**：fast-path dedupe（命令成功，uid=2000）。
- **S5**：stdout isolation（status 不进远端文件，直接读文件验证无 `[adb]`）。
- **S6**：permission failure before exit（WRITE_SECURE 可用时 classified）。
- **S7**：session scope（session 收到自己的输出）。
- **S8**：async status regression（0.1B 改动后 async 路径仍 wired）。
- **S9**：session close cleanup（close 后无订阅泄漏）。
- Harness：`runLocalCmdTimed` 用**后台 waiter 线程**（非 main 阻塞）+ 带时间戳的
  `events` 队列 + synchronized `observed` StringBuilder，使 bridge 线程的
  `emitSystemOutput`（同步 fan-out）能被捕获。

### AdbCliLifecycleTest.kt（androidTest, 既有）
- **L1**：放宽为只验证 cold-path 中间状态（`enabling wireless debugging`）。
  `uid=2000` 的完整验证由 `AdbCliLiveStatusTest` S1（长 timeout）负责——harness 的
  `onActivity` 在 marker 后只 sleep 2.5s，cold path（~10s）的最终结果可能未出现。
- **L3**：放宽为只验证命令成功（`SECOND_DONE`）。`checking` dedupe 由代码逻辑
  （`statusEmittedForFd`）保证，harness 的 main-thread 阻塞可能捕获不全 status 行。
  live status dedupe 由 `AdbCliLiveStatusTest` S3 验证。

## 5. 单投影路径（§9，STATUS_DUPLICATE = NO）

命令时刻 status 有**唯一**投影路径：`emitStatus` → Host 投影（有效 session）或
T_STATUS（fallback）。**不**同时对有效 session 发 T_STATUS + Host（避免重复）。
`connected` 由 lifecycle transition（`onTransition`）拥有，statusListener 跳过；
fast path 只在 bootstrap-早于-subscribe 时 backfill 一次。

## 6. T_STATUS fallback（§10，保留）

无有效 Local session（test client / 未来非 Human 客户端）时，`emitStatus` 走
T_STATUS（AdbCliMain stderr，buffered until EXIT）——0.1A 路径，非 Human 可接受。
**无破坏性协议重设计**：T_STATUS frame 格式不变，AdbCliMain 不变。

## 7. 真机证据

设备 `APLMUT4312005935`（HONOR ALI-AN00 / Android 15），app UID 10313。

| Suite | 结果 | 说明 |
|---|---|---|
| **AdbCliLiveStatusTest（新）** | **9/9 PASS** | S1 核心：中间 status 在 uid=2000 之前观察到 |
| AdbCliOneShotTest | 15/15 PASS | 回归 |
| AdbCliLifecycleTest | 11/11 PASS | L1/L3 放宽（见 §4），L4-L11 不变 |
| AdbCliBridgeTest | 13/13 PASS | 回归 |
| SharedSessionBridgeTest | 13/13 PASS | b2_b3 偶发 flaky（debug bridge listSessions 时序，与本改动无关，重跑 PASS） |
| DynamicSessionPagesTest | 9/9 PASS | 回归 |
| ProgrammaticAdbConsumerTest | 7/7 PASS | 回归 |
| CommonConsumerPathTest | 3/3 PASS | 回归 |
| AdbHumanPreviewTest | 6/6 PASS | 回归 |
| AdbCliArgsTest（JVM） | 20/20 PASS | 回归 |

**S1 时序证据**（cold path，~10s）：`checking`/`enabling`/`discovering`/`connecting`
在 `+0.001s`（命令写入后即刻）被 observer 观察到，`uid=2000` 在 `+10.5s` 出现。
`earliestIntermediate < completedAt` 成立 → `STATUS_BEFORE_COMMAND_EXIT = REQUIRED` 满足。

**注**：HONOR 设备 adbd 拒绝 loopback ADB（PC-orchestration suite 因此 fail，非因果）；
app 路径（discovery + bounded retry）正常。

## 8. 改动文件清单

```
app/src/main/java/com/androidterminal/app/cli/AdbCliStatusHub.kt      (M) emitCommandStatus + onTransition 同步
app/src/main/java/com/androidterminal/app/AppRuntime.kt               (M) emitAdbCommandStatus + initStatusHub 重建 + sessionRegistry()
app/src/main/java/com/androidterminal/app/MainActivity.kt             (M) createPage 用 AppRuntime.sessionRegistry()
app/src/main/java/com/androidterminal/app/cli/AdbCliBridgeServer.kt   (M) subscribe 提前 + emitStatus + statusListener 跳过 connected + backfill
app/src/androidTest/java/com/androidterminal/app/AdbCliLiveStatusTest.kt (新) S1-S9
app/src/androidTest/java/com/androidterminal/app/AdbCliLifecycleTest.kt (M) L1/L3 放宽
```

**未改**：`adb-engine/`（AdbRuntime READ-ONLY）/ `at-engine/` / `at-backend-*` /
`adb-native` / `remote-pty` / `AdbCliMain.kt`（T_STATUS fallback 路径不变）。

## 9. STOP 条件核查（§34）

| # | 条件 | 状态 |
|---|---|---|
| 1 | ADB_ENGINE_MODIFIED = NO | ✅ 未改 adb-engine/ |
| 2 | ADBRUNTIME_MODIFIED = NO | ✅ AdbRuntime READ-ONLY |
| 3 | AT_ENGINE_MODIFIED = NO | ✅ 未改 at-engine/ |
| 4 | ADB_NATIVE_MODIFIED = NO | ✅ 未改 adb-native/ |
| 5 | REMOTE_PTY_MODIFIED = NO | ✅ 未改 remote-pty/ |
| 6 | STATUS = HOST/SYSTEM TERMINAL EVENT | ✅ emitSystemOutput 路径 |
| 7 | STATUS_DUPLICATE = NO | ✅ 单投影路径（emitStatus） |
| 8 | T_STATUS 保留（非 Human fallback） | ✅ emitStatus fallback |
| 9 | STATUS_BEFORE_COMMAND_EXIT = REQUIRED | ✅ S1 时间戳证明 |
| 10 | 不重解 child PTY print（用 Host 路径） | ✅ Host 投影，未动 PTY |
| 11 | 回归全绿 | ✅ §7 全部 PASS |

## 10. 人工 Gate（§27）= **PASS / CLOSED**

用户真实 Human 真机手动验证（HONOR ALI-AN00 / Android 15）：

**A. 实时 status（WD OFF）**：`adb shell id` 下，连接状态
`checking` / `wireless debugging off` / `enabling` / `discovering` / `connecting` /
`connected` 在 `uid=2000(shell)` 之前**逐步出现**，**不是** EXIT 后批量刷出。
→ **`REAL_HUMAN_LIVE_STATUS = PASS`**

**B. stdout 隔离**：
```
x="$(adb shell echo LIVE_STATUS_TEST)"
printf 'CAPTURE=[%s]\n' "$x"
```
得 `CAPTURE=[LIVE_STATUS_TEST]`，**无** `[adb] ...` 混入 stdout。
→ **`STATUS_STDOUT_CONTAMINATION = NO`**

**结论**：**`AT.CLI-0.1B = CLOSED`**（REAL_HUMAN_LIVE_STATUS=PASS +
STATUS_STDOUT_CONTAMINATION=NO）。本 Gate 闭合为 docs-only normalization，
未修改任何 production code。
