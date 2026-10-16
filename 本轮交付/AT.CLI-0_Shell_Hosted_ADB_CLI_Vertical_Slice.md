# AT.CLI-0 — Shell-Hosted ADB CLI Vertical Slice (Implementation)

> **AT_CLI_0_OVERALL_STATUS = COMPLETE**（automation；Interactive Human Gate 待执行）
>
> | 阶段 | 状态 |
> |---|---|
> | EntryPoint Recovery | **COMPLETE** |
> | One-shot Core (`adb shell <cmd...>`) | **VERIFIED** |
> | Auto-WD / connection lifecycle (AT.CLI-0.1A) | **COMPLETE** |
> | Live command-time ADB status (AT.CLI-0.1B) | **VERIFIED / CLOSED** |
> | Interactive `adb shell` (AT.CLI-0.2) | **VERIFIED**（automation 16/16；Human Gate 待） |
>
> `SHELL_HOSTED_ADB_CLI = ONESHOT + LIFECYCLE + LIVE_STATUS + INTERACTIVE`
> （automation VERIFIED）。`AT_CLI_0_2_AUTOMATION = CLOSED`；
> `REAL_HUMAN_INTERACTIVE_SHELL = PENDING`（§37 Human Gate）。

---

## 0. 目标

在真实 Human Local `/system/bin/sh`（SELinux `untrusted_app`）里，让 `adb shell <command...>`
作为**产品可见的 one-shot CLI** 工作：用户在 Local 终端敲 `adb shell id` → 得到
`uid=2000(shell)`，stdout/stderr/exitCode 正确映射回 Local 终端。

约束（不可破）：
- `AT_ENGINE_MODIFIED = NO` / `ADB_ENGINE_MODIFIED = NO`
- 不引入第二 AdbRuntime / identity / TLS owner / DebugBridge 依赖
- 不改 root / SELinux
- 复用现有 Product CLI Bridge（same-UID UDS，protocol v1）

## 1. 阶段 A — EntryPoint Recovery（COMPLETE）

**问题**：原 `files/bin/adb`（ELF，SELinux `app_data_file`）被 `untrusted_app` exec 时
**RC=126 Permission denied**（SELinux 阻止 `untrusted_app` 执行 `app_data_file` 二进制）。
`run-as`（`runas_app` 域）能 exec 同一 ELF，但不代表真实 Local PTY。
→ `PRIVATE_APP_DATA_ELF_ENTRYPOINT = REJECTED`。

**恢复**：入口改 `/system/bin/app_process`（context `zygote_exec`），可被 `untrusted_app` exec。
命令形如：
```
/system/bin/app_process -Djava.class.path=$AT_APP_APK /system/bin \
  com.androidterminal.app.cli.AdbCliMain <args...>
```
P1-P6（exec / class load / argv / PTY stdio / 连现有 Product CLI Bridge / clean return）
**6/6 PASS**（见 `docs/evidence/at_cli_0/APP_PROCESS_ENTRYPOINT_PROBE.md` 与
`SELINUX_EXEC_BLOCKER.md`）。commit `17ba449`。

## 2. 阶段 B — One-shot Core（VERIFIED）

`adb shell <command...>` one-shot 闭合，真机 **15/15 PASS**（见
`docs/evidence/at_cli_0/ONESHOT_CLOSURE.md`）。

### 组件
- `cli/AdbCliMain.kt`（`src/main`，debug+release）：app_process 入口，纯 JVM/Kotlin（无 JNI），
  `android.net.LocalSocket` UDS 客户端，one-shot 往返（HELLO→ONESHOT→OUT/ERR/EXIT→映射
  stdout/stderr/exitCode）。
- `cli/AdbCliArgs.kt`（`src/main`）：纯 JVM 参数解析 + POSIX 单引号 shell quoting 助手
  （`quoteSingle` / `serializeToRemoteCommand`）。
- `cli/LocalSessionBootstrap.kt`（`src/main`）：写 `adb_bootstrap.sh`（env export + `adb()` 函数）
  到 app filesDir + source 行。
- `cli/AdbCliBridgeServer.kt`（`src/main`，**已存在**，本轮仅加 `ensureReady` 轮询 + 诊断日志）：
  复用 native UDS server，same-UID `SO_PEERCRED`，protocol v1。
- `cli/CliProbe.kt`（`src/debug`，**仅 debug**）：调试探针，release APK 不含。
- `AppRuntime.kt`：新增 `appApkPath()`（`applicationInfo.sourceDir`）/ `cliBridgeSocketPath()` /
  `appContextRef`。
- `MainActivity.kt`：LOCAL 页 `banner()` 前置 source bootstrap；`activeSharedHandle()` 访问器。

### 真实路径
```
Local sh (untrusted_app)
  → adb shell id            # adb() 函数 (adb_bootstrap.sh)
  → /system/bin/app_process ... AdbCliMain shell id
  → LocalSocket UDS (cli_bridge.sock, same-UID)
  → AdbCliBridgeServer.onOneShot
  → AppRuntime.adbEngine().execute (AT Engine → ADB Backend → ADB Engine → adbd)
  → 远程 sh (shell, uid=2000) 执行 id
```

### principal transition（O3 实测）
```
本地 uid=10313(u0_a313)  →  adb shell id  →  远程 uid=2000(shell)  →  本地 uid=10313
```

### 本轮实现 bug fixes（1-6）
1. `AT_APP_APK` 空 → bootstrap 改文件 + source（PTY 多行函数 mangle）。
2. Kotlin 模板展开 `$ENV_APK`→常量名 → `"$"+ENV_APK` 输出字面量 `$AT_APP_APK`。
3. `ensureReady` 单次 `connectAuto` 不足 → 轮询 15s。
4. O1 断言（`type adb` 对函数只返回名字）→ 含 `function`。
5. O3 marker 误匹配（本地 `id` 含 `u0_a`）→ 只用 `uid=2000`。
6. observer 捕获 flaky（PTY 分批）→ marker 后多等 2.5s。

## 3. 阶段 C — Auto-WD / connection lifecycle（AT.CLI-0.1A = **COMPLETE**）

AT.CLI-0.1 的 one-shot 验证**依赖** WD=ON + trusted + READY 固定前置。AT.CLI-0.1A 闭合了
connection lifecycle：AdbRuntime 作为**长驻生命周期 owner**，`adb command` 在 Local session
里**零手动前置** cold-ready（WD OFF 也能一命令 auto-enable → connected），且 transport
丢失 / 重连以**异步 status** 投影到使用过 adb 的 Local session。

### 序列（实测）
```
adb shell id            # WD OFF, 首次
  → ensureReady (Runtime-owned)
  → [adb] checking connection...
  → [adb] wireless debugging: off
  → [adb] enabling wireless debugging...   # auto-WD (WRITE_SECURE_SETTINGS putInt)
  → [adb] discovering...
  → [adb] connecting...                    # bounded 3 次重试 (adbd 启动时间)
  → [adb] connected
  → uid=2000(shell) ...

adb shell echo second   # 同 session, 已 READY
  → (dedupe: 无重复 checking/connected)
  → second

# WD OFF, 无新命令
  → [adb] disconnected                    # async status (read-only observer edge)

# 重连
  → [adb] reconnecting...
  → [adb] connected
```

### 关键设计
- **状态映射**复用现有 `AdbRuntimeState`（READY→connected / RECOVERING→reconnecting /
  TRANSPORT_LOST→disconnected / USER_ACTION_REQUIRED→user action required），不新加值。
- **T_STATUS = 12**（additive protocol frame，backward-compat）→ CLI stderr → Local PTY。
- **异步 status** 经 `SharedSessionHandle.emitSystemOutput`（host/system event，**非** PTY
  stdin / **非** remote stdout）→ 订阅 session（`AT_SESSION_ID` 绑定，session-scoped）。
- **read-only transport observer**（1.5s poll `transportState()`，ONLINE→!ONLINE edge →
  `onObservedTransportLost` → 委托现有 `onOperationResult(true)` → 有界恢复）。无 ping/
  socket/heartbeat。
- **fast-path dedupe**：同 session 连接未变化时静默（§23）。
- **in-flight 无透明重放**（结构性不变量：恢复只重连，不重跑命令）。
- **Bug fix（banner `(` syntax error）**：banner 的 `echo === ... (page N) ===` 里未引用的
  `(` 被 `/system/bin/sh` 当子 shell 元字符 → `syntax error: unexpected '('`，中断整行。
  修复：echo 消息双引号包裹（详见 evidence §5.1）。
- 详见 `docs/evidence/at_cli_0/ADB_RUNTIME_LIFECYCLE_CLOSURE.md`（L1-L11 = 11/11 PASS +
  回归 + Gate A-K evidence + banner bug fix）。

## 3.5 Live command-time ADB status（AT.CLI-0.1B = **VERIFIED**）

0.1A 闭合了 **lifecycle / async** status（transport 丢失、重连、READY 等**状态迁移**），
但**命令时刻（command-time）**的 cold-connect 中间状态（`checking` / `enabling` /
`discovering` / `connecting`）走短命 `AdbCliMain` 进程的 PTY stderr（T_STATUS frame），
在 `ensureReady` 阻塞期间**不 live**——Human 看到的是命令 EXIT 时才刷出的一整块 status。

**0.1B 修复**：命令时刻 status 经 **Host 投影**（`SharedSessionHandle.emitSystemOutput`，
HOST/SYSTEM terminal event）**实时逐行**投影到 Local session，不是 buffered 到 EXIT。

组件改动（全部 app/ 层，CLI/Host + tests）：
- **`AdbCliStatusHub.emitCommandStatus(sessionId, status)`**（新）：命令时刻 status 的
  **单一投影 owner**。同步投递（非 `main.post`）——caller 是 bridge 线程（正在跑
  `ensureReady`），`forEachObserver` 是 synchronized 同步 fan-out。closed session →
  `unsubscribe`（无泄漏）。
- **`AdbCliStatusHub.onTransition` 改同步**（去 `main.post`）：与 `emitCommandStatus`
  一致的投递语义（`forEachObserver` 已线程安全）。
- **`AdbCliBridgeServer.emitStatus(fd, sid, line)`**（新）：命令时刻 status 的**单一投影
  路径**。有效 Local session → Host 投影（live）；否则 → T_STATUS fallback（AdbCliMain，
  buffered，非 Human 可接受）。**不**对有效 session 同时发 T_STATUS（避免重复）。
- **`ensureReady` subscribe 提前**：`subscribe(sid)` 移到 `AdbRuntime.ensureReady` 之前，
  使 cold-connect 期间的命令时刻 status 能投影到 session。
- **`statusListener` 跳过 `"connected"`**：lifecycle transition（`onTransition`
  CONNECTING→READY）拥有 `connected`，statusListener 再发会重复。
- **fast path backfill `connected`**：subscribe 时若 AdbRuntime 已 READY（bootstrap
  connect 早于 subscribe），transition 已 fire（当时 `subscribed` 为空），补发一次
  `connected`（不重复）。
- **`statusEmittedForFd`**（ConcurrentHashMap）：per-fd 去重，fast path 只对每个 fd 的
  首次 use 发 `checking`（无 re-spam）。
- **`AppRuntime.initStatusHub` 总是重建 hub**（不再 `if (statusHub == null)`）：Activity
  recreate 时创建新 `AppSessionRegistry`；旧 hub 持 stale registry（lookup 返回 null）。
- **`AppRuntime.sessionRegistry()`**（新）+ `AppTerminal` 用进程级 registry（跨 Activity
  recreate 一致）。

**不变量**：`ADB_ENGINE_MODIFIED=NO` / `ADBRUNTIME_MODIFIED=NO`（AdbRuntime READ-ONLY）/
`AT_ENGINE_MODIFIED=NO` / `ADB_NATIVE_MODIFIED=NO` / `REMOTE_PTY_MODIFIED=NO` /
`AdbCliMain` 未改（T_STATUS fallback 路径不变）。STATUS=HOST/SYSTEM TERMINAL EVENT /
STATUS_DUPLICATE=NO（单投影路径）/ T_STATUS 保留（非 Human fallback）。

- 详见 `docs/evidence/at_cli_0/LIVE_COMMAND_STATUS_CLOSURE.md`（S1-S9 = 9/9 PASS + 回归全绿 +
  S1 时间戳证明 STATUS_BEFORE_COMMAND_EXIT=REQUIRED）。

## 4. Interactive `adb shell`（AT.CLI-0.2 = **VERIFIED**，Human Gate 待）

无参 `adb shell` 进入真正的长期 Remote PTY shell（`uid=2000(shell)`，`/dev/pts/*`），
支持 stdin / persistent cwd+env / Ctrl-C / resize / clean exit 回 Local shell。**REUSE 既有
verified 能力**（AT Engine / ADB Backend / ADB Engine interactive stream / Remote PTY），
**NOT REWRITE**；仅 `:app` CLI bridge / frontend / bootstrap 组合层改动。

### 4.1 两个 PTY（不合并为一个 Session）

- **OUTER LOCAL PTY** = app UID `/system/bin/sh`（外层 shell，SessionPage 的 ATTerminalSession）。
- **INNER REMOTE PTY** = `uid=2000` `/system/bin/sh`（`shell,v2,pty:` 打开的 Remote PTY，
  由 CLI connection 持有的 inner ATTerminalSession）。

### 4.2 实现路径（canonical）

```
Local /system/bin/sh → adb()（no-arg → stty raw -echo + 保存/恢复）
  → app_process AdbCliMain shell
  → Product CLI Bridge (UDS, protocol v1, frames 7-11)
  → AdbCliBridgeServer.onInteractiveOpen
  → AppRuntime.adbEngine().openSession(AdbPersistentBackend.Launch("",rows,cols))
  → AdbTerminalSession → ADB Engine interactive stream → shell,v2,pty: → adbd
  → INNER REMOTE PTY
```

### 4.3 关键设计

- **Outer TTY relay mode**：`adb()` wrapper 对 no-arg `adb shell` 保存 `stty -g` →
  `stty raw -echo` → 运行 → **无论退出路径恢复 `stty "$_old"`**（hard gate）。one-shot
  不切 raw。
- **双向 relay**：`AdbCliMain.bridgeInteractive()` = 1 个 stdin relay thread（byte-oriented，
  不等 newline）+ 1 个 bridge read loop。socket frame write 单 writer（无 interleave）。
- **Ctrl-C**：raw mode 下 0x03 不被 Outer PTY 当 SIGINT 消费，relay 到 Remote PTY line
  discipline → remote foreground SIGINT。
- **Resize**：initial size 来自 `stty size`（`T_INTERACTIVE` payload，无 hardcode）；Human
  resize Local session 经 `AppTerminal.resize → AppRuntime.relayInteractiveResize →
  interactiveForSession 映射 → inner ATTerminalSession.resize`（AT Engine 不知 "nested adb
  shell"，app/CLI 组合 concern）。
- **Natural exit**：ADB backend 以 `ATSessionEvent.Eof`（stream end）表达 remote exit →
  bridge 映射 `T_EXIT(code=0)` → relay 关闭 → wrapper 恢复 Outer TTY。
  `INTERACTIVE_EXIT_CODE = NOT_EXPOSED`（shell-v2 facade 无 exit frame，不扩大 AT contract）。
- **Transport loss**：当前 interactive invocation 终止 + Local TTY 恢复；**不**透明 resume /
  **不** replay；recovery 由既有 AdbRuntime（ONE owner）。
- **Multi-session**：每 fd 持有自己的 inner session（`sessions[fd]`）；无 global
  singleInteractiveSession。

### 4.4 已知修复（root cause）

- **JNI 签名 bug**（`cli_bridge_server.cpp`）：`onInteractiveData` `GetMethodID` 签名
  `(IIB[V)V`（`B[` 顺序错）→ null → 首次 interactive `JNI DETECTED ERROR: mid == null`
  SIGABRT。修为 `(II[B)V` + nativeStart 非空校验。
- **Eof 未映射**（`AdbCliBridgeServer`）：ADB backend 以 `Eof` 表达 natural remote exit，
  listener 原忽略 → relay 永不终止。改为 Eof→T_EXIT。

- 详见 `docs/evidence/at_cli_0/INTERACTIVE_SHELL_CLOSURE.md`（I1-I16 = 16/16 PASS + 回归全绿）。

## 5. 测试与回归

- `AdbCliInteractiveTest`（androidTest，AT.CLI-0.2，**新**）：I1-I16，**16/16 PASS**
  （no-arg opens interactive / uid=2000 / /dev/pts/* real PTY / cwd continuity / env
  continuity / foreground stdin cat / Ctrl-C kills remote fg + shell survives / resize
  reflected by remote stty size / UTF-8 / natural exit → Local return / Outer TTY restored /
  second reopen / connection reuse / transport loss termination (no resume) / multi-session
  isolation / cold WD-OFF live status before prompt）。真实 Human Local PTY path，每 test
  有 BODY_EXECUTED sentinel（无 vacuous PASS）。
- `AdbCliLiveStatusTest`（androidTest，AT.CLI-0.1B，**新**）：S1-S9，**9/9 PASS**（cold live
  status before exit / ordering / no duplicate / fast-path dedupe / stdout isolation /
  permission failure / session scope / async regression / close cleanup）。核心 S1 证明中间
  status 在 `uid=2000` 之前被 observer 观察到（时间戳，STATUS_BEFORE_COMMAND_EXIT=REQUIRED）。
- `AdbCliLifecycleTest`（androidTest，AT.CLI-0.1A）：L1-L11，**11/11 PASS**（auto-WD /
  fast-path / dedupe / transport projection / idle disconnect / recovery / no-replay /
  session-scoped / close cleanup / permission fallback / pairing classification）。
  注：0.1B 后 L1/L3 放宽为中间状态/命令成功（harness 的 main-thread 阻塞可能捕获不全
  status 行；完整 cold 结果由 `AdbCliLiveStatusTest` S1 长 timeout 验证）。
- `AdbCliOneShotTest`（androidTest）：O1-O15，**15/15 PASS**（守 0.1 底线；ensureReady
  重构 + T_STATUS 未破 one-shot）。
- `AppProcessProbeTest`（androidTest）：P1-P6，**6/6 PASS**。
- 相关回归（WD=ON 前置）：`SharedSessionBridgeTest` 13/13、`DynamicSessionPagesTest` 9/9、
  `ProgrammaticAdbConsumerTest` 7/7、`CommonConsumerPathTest` 3/3。
- JVM：`AdbCliArgsTest`（`src/test`）20/20。
- `AdbCliBridgeTest`（androidTest）：**13/13 PASS**（C13 SUPERSEDED by `AdbCliOneShotTest.o12_unsupported`，
  加 `@Ignore`；13 active gate 全过）。
- **AT.CLI-0.1A.R final regression closure**（真机 HONOR ALI-AN00）：
  - AdbRuntimeLifecycle 12/12 + Pairing deterministic 54/54 + Shellless 15/15 +
    AdbCliBridge 13/13 + OneShot 15/15 + Lifecycle 11/11 + HeadlessInput 1/1 =
    **FINAL_REGRESSION_CLOSURE = PASS**（本轮改动路径关键回归全过）。
  - PC-orchestration real-device suite（AdbBackend 25 / Parity 32 / AdbOnlyRealDevice /
    AdbDebt1）失败为**设备/前置条件**（HONOR adbd 拒绝 loopback：`state=Online tls=1` 但
    adbd 立即 CLSE + `WIFI TLS disconnect`；pairing dialog 需手动；需 PC 传当前端口），
    **非 9886838 causal**（本轮未改 ADB native transport / protocol / AdbBackend / AT Engine）。

> **回归注意**：ADB persistent 类测试（ProgrammaticAdbConsumerTest / CommonConsumerPathTest）
> 依赖 WD=ON；WD OFF 时会 `USER_ACTION_REQUIRED` 失败（前置问题，非代码 regression）。

## 6. fresh-install / post-bootstrap 语义（AT.CLI-0.1A.R 归一化）

```text
FRESH_INSTALL_ZERO_TOUCH           = NOT_COMPLETE
FIRST_USE_MANUAL_BOOTSTRAP         = REQUIRED
POST_BOOTSTRAP_AUTO_WD             = VERIFIED
POST_BOOTSTRAP_ONE_COMMAND_COLD_READY = VERIFIED
POST_BOOTSTRAP_ZERO_MANUAL_COLD_READY = VERIFIED   (限定 post-bootstrap，非 fresh install)
```

完成首次 manual WD + trust/pair + WRITE_SECURE bootstrap **以后**：WD OFF → `adb shell id` →
auto-WD → discovery → connect → connected → uid=2000，无需额外用户操作。
**不**声明 `FRESH_INSTALL_ZERO_TOUCH = COMPLETE`。
