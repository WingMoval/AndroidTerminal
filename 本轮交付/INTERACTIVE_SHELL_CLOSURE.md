# AT.CLI-0.2 — Interactive `adb shell` Remote PTY Closure

> 状态：**AUTOMATION VERIFIED**（16/16 + 全回归绿）。
> Human Gate（§37）**待执行** — 见 §12。
> 基线：`c87dd10`（AT.CLI-0.1B docs-only gate closure）。

## 1. 目标

让真实 Human Local `/system/bin/sh` 中无参数 `adb shell` 进入真正的、长期
存在的 ADB Remote PTY shell（`uid=2000(shell)`，`/dev/pts/*`），支持
stdin / persistent cwd+env / Ctrl-C / resize / clean exit 回 Local shell。

本轮 **REUSE 既有 verified 能力**（AT Engine / ADB Backend / ADB Engine
interactive stream / Remote PTY），**NOT REWRITE**。

## 2. 两个 PTY（不得混为一个 Session）

```
OUTER LOCAL PTY  = app UID /system/bin/sh（外层 shell）
INNER REMOTE PTY = uid=2000 /system/bin/sh（shell,v2,pty: 打开的 Remote PTY）
```

`adb shell`（no-arg）启动短生命周期 CLI frontend（`AdbCliMain`），经 Product
CLI Bridge 持有 **一个 inner ATTerminalSession**；外层 Local PTY 仍是
SessionPage 的 ATTerminalSession。两者是不同的 Session，不合并。

## 3. 实现路径（全部 REUSE，仅 app/CLI 组合层）

```
Human
  ↓
Local Terminal UI
  ↓
Local ATTerminalSession (OUTER LOCAL PTY, app UID)
  ↓
/system/bin/sh
  ↓
adb()  shell-visible launcher（no-arg → stty raw -echo + 保存/恢复）
  ↓
/system/bin/app_process … AdbCliMain shell      # 短生命周期 CLI frontend
  ↓
same-UID Product CLI Bridge (UDS)
  ↓
AdbCliBridgeServer.onInteractiveOpen
  ↓
AppRuntime.adbEngine().openSession(AdbPersistentBackend.Launch("",rows,cols))
  ↓
AdbPersistentBackend → AdbTerminalSession
  ↓
ADB Engine interactive stream → shell,v2,pty: → adbd
  ↓
INNER REMOTE PTY (uid=2000)
```

### 改动文件（全部 `:app`，无 AT/ADB engine 改动）

| 文件 | 改动 |
|---|---|
| `AdbCliArgs.kt` | 新增 `Kind.INTERACTIVE`；no-arg `adb shell` → INTERACTIVE（原 HELP/RC=2）。one-shot `adb shell <cmd...>` 语义不变。 |
| `AdbCliMain.kt` | 新增 `bridgeInteractive()`：双向 byte-oriented relay（1 个 stdin relay thread + 1 个 bridge read loop）。`writeFrame` 改为返回 Boolean。 |
| `LocalSessionBootstrap.kt` | `adb()` wrapper：no-arg `adb shell`（`$# -eq 1`）分支 → 保存 `stty -g` / `stty raw -echo` / 读 `stty size` 注入 `AT_CLI_ROWS/COLS` / 运行 / **恢复 `stty "$_old"`**（hard gate §16）。one-shot 不切 raw。 |
| `AdbCliBridgeServer.kt` | listener 新增 **`Eof` → T_EXIT**（ADB backend 以 stream end 表达 natural remote exit，shell-v2 不带 exit code）。新增 `interactiveForSession`（sessionId→fd 映射）+ `relayResize`（§20 resize relay）。 |
| `AppRuntime.kt` | 新增 `relayInteractiveResize(sessionId,rows,cols)` 委托。 |
| `AppTerminal.kt` | `resize` 后调用 relay（若该 Local session 有 active interactive）。 |
| `MainActivity.kt` | 新增 `activeAppTerminal()` test accessor。 |
| `cli_bridge_server.cpp` | **修复 `onInteractiveData` JNI 签名 `(IIB[V)V`→`(II[B)V`**（原签名 `B[` 无效 → GetMethodID 返回 null → 首次 interactive 调用 `JNI DETECTED ERROR: mid == null` SIGABRT）。新增 nativeStart mid 非空校验。 |
| `AdbCliArgsTest.kt` | `parseShellNoCommand` 更新为 INTERACTIVE 语义。 |
| `AdbCliInteractiveTest.kt` | 新增 I1-I16（androidTest，真实 Human Local PTY path）。 |

## 4. Protocol（v1，无 breaking，全复用）

Frame 1-12 全部复用，**无重新编号**：

```
HELLO=1  HELLO_ACK=2  ONESHOT=3  OUT=4  ERR=5  EXIT=6
INTERACTIVE=7  STDIN=8  RESIZE=9  CLOSE=10  ERROR=11  STATUS=12
```

- `T_INTERACTIVE` payload = `[rows:4LE][cols:4LE]`（initial Remote PTY size）。
- `T_STDIN` = Local stdin bytes（byte-oriented，不等 newline）。
- `T_RESIZE` = `[rows:4LE][cols:4LE]`。
- `T_EXIT` = `[code:4LE]`（natural remote exit → code 0，见 §6）。
- `T_STATUS`（=12）保持 0.1B 的 Host 投影语义（cold live status）。

## 5. Outer Local PTY relay mode（§14/§15/§16）

`adb()` wrapper 对 **no-arg `adb shell`** 分支：
1. `OLD_STTY=$(stty -g)`
2. `stty raw -echo`（byte-oriented、无行缓冲、无双 echo、Ctrl-C 0x03 不被
   Outer PTY 当 SIGINT 消费）
3. `stty size` → `AT_CLI_ROWS/COLS`（initial Remote PTY size，§21）
4. 运行 `app_process … AdbCliMain shell`
5. **无论退出路径**都 `stty "$OLD_STTY"` 恢复（hard gate）。

one-shot `adb shell <cmd...>` **不**切 raw（保持 canonical Local TTY）。

## 6. Exit code（§26）

`AdbTerminalSession` 用 **stream end → `ATSessionEvent.Eof`** 表达 natural
remote exit（Kotlin ADB facade 不暴露 shell-v2 exit frame）。Bridge 把 `Eof`
映射为 `T_EXIT(code=0)`，CLI frontend 关闭 relay、wrapper 恢复 Outer TTY。

**`INTERACTIVE_EXIT_CODE = NOT_EXPOSED`**（现有 persistent contract 只表达
closed/exited，不保留 shell exit code；本轮不扩大 AT public contract）。
核心 Gate = clean exit + Local shell restoration（已验证）。

## 7. Ctrl-C 语义（§18/§34）

```
Human 0x03
  ↓ Outer PTY raw mode 不消费为 Local SIGINT
  ↓ AdbCliMain stdin relay 收 0x03
  ↓ T_STDIN → Bridge → ATTerminalSession.write(0x03)
  ↓ Remote PTY line discipline → SIGINT → remote foreground
```

I7 验证：`sleep 30` → 0x03 → sleep 终止 → `echo AFTER_CTRL_C` → 出现
（remote shell 存活）。**`REMOTE_FOREGROUND_SIGNAL = PASS`**。

## 8. Resize 路由（§19/§20/§21）

- **Initial size**：`stty size`（Outer Local PTY 真实尺寸）→ env →
  `AdbCliMain` → `T_INTERACTIVE` payload → `openSession(rows,cols)`。
  **`INITIAL_REMOTE_SIZE = PASS`**（无 hardcode）。
- **Resize relay**：Human resize Local session → `AppTerminal.resize` →
  `AppRuntime.relayInteractiveResize(sessionId,rows,cols)` →
  `AdbCliBridgeServer.relayResize` → inner `ATTerminalSession.resize`。
  由 `interactiveForSession`（sessionId→fd 最小映射）定位。AT Engine 不知
  "nested adb shell"（app/CLI 组合 concern）。
- I8 验证：remote `stty size` 24x80 → resize 41x123 → remote `stty size`
  显示 41。**`RESIZE_RELAY = PASS`**。

## 9. Transport loss（§28）

Interactive 运行中 ADB transport loss（I14：WD off）：
- 当前 interactive invocation **终止**（Local TTY 恢复、Local shell 返回）。
- **不**自动重建 Remote PTY / **不** replay 输入 / **不**透明 resume。
- 由既有 `AdbRuntime` 后台 recovery（ONE owner）。
- `INTERACTIVE_TRANSPARENT_RESUME = NO`，`INTERACTIVE_INPUT_REPLAY = NO`，
  `RECOVERY_OWNER = EXISTING AdbRuntime`。

## 10. Multi-session（§30/§31）

- 每个 interactive CLI connection/fd 持有 **自己的** inner ATTerminalSession
  （`sessions[fd]`）；无 global singleInteractiveSession。
- I15：fresh page 的 interactive 不继承前 page 的 env/cwd。
  **`INTERACTIVE_MULTI_SESSION_ISOLATION = PASS`**。
- I13：READY 时连续 open/exit/open/exit 复用底层 authenticated 连接
  （不重新 TLS/CNXN/pairing）。**`INTERACTIVE_CONNECTION_REUSE = PASS`**。

## 11. 自动化结果（真实 Human Local PTY path）

| Gate | 结果 |
|---|---|
| I1 no-arg opens interactive | PASS |
| I2 remote principal uid=2000 | PASS |
| I3 tty=/dev/pts/* (real PTY) | PASS |
| I4 cwd continuity | PASS |
| I5 env continuity | PASS |
| I6 foreground stdin (cat) | PASS |
| I7 Ctrl-C kills remote fg, shell survives | PASS |
| I8 resize reflected by remote stty size | PASS |
| I9 UTF-8 byte preservation | PASS |
| I10 natural exit → Local shell return | PASS |
| I11 Outer TTY restored | PASS |
| I12 second reopen | PASS |
| I13 connection reuse | PASS |
| I14 transport loss termination (no resume) | PASS |
| I15 multi-session isolation | PASS |
| I16 cold WD-OFF live status before prompt | PASS |

**AdbCliInteractiveTest = 16/16 PASS**（每 test 有 BODY_EXECUTED sentinel，
无 vacuous PASS）。

### 回归（§38，全绿）

```
AdbCliLiveStatusTest   9/9
AdbCliOneShotTest     15/15
AdbCliLifecycleTest   11/11
AdbCliBridgeTest      13/13
SharedSessionBridgeTest 13/13
DynamicSessionPagesTest 9/9
ProgrammaticAdbConsumerTest 7/7
CommonConsumerPathTest  3/3
AdbHumanPreviewTest     6/6
AppProcessProbeTest     6/6
AdbCliArgsTest (JVM)   20/20
```

**ONE_SHOT_INTERACTIVE_SEMANTIC_SEPARATION = PASS**（one-shot 全回归绿，
stdout/stderr/exitCode 未被 interactive protocol 污染）。

### 边界（本轮无 engine 改动）

```
AT_ENGINE_PUBLIC_CONTRACT_CHANGED  = NO
ADB_PROTOCOL_ENGINE_MODIFIED       = NO
ADB_NATIVE_TRANSPORT_MODIFIED      = NO
ADB_STREAMHUB_MODIFIED             = NO
REMOTE_PTY_PRIMITIVE_MODIFIED      = NO
ADBRUNTIME_MODIFIED                = NO
SECOND_ADB_RUNTIME                 = NO
SECOND_RECOVERY_OWNER              = NO
```

仅 `:app` CLI bridge / frontend / bootstrap 消费既有 backend，未改
`:at-engine` / `:adb-engine` / `:at-backend-adb`，故不机械重跑无因果的
ADB Backend / Parity / Shellless / Pairing 长套件（§38 scope 报告）。

## 12. Human Gate（§37）— 待执行

自动化全 PASS 后，需 Human 真机手动：
1. Local `id`（app UID）。
2. `adb shell` 进 remote prompt。
3. remote `id`/`tty`/`pwd`（uid=2000(shell)、/dev/pts/*）。
4. remote `cd /data/local/tmp` + `export HUMAN_INTERACTIVE=YES` + 验证。
5. remote `sleep 30` → 点 CTRL-C → sleep 终止、remote prompt 仍在 →
   `echo AFTER_CTRL_C`。
6. remote `stty size` → 点 RESIZE 改尺寸 → `stty size` 变化。
7. remote `exit` → 回 Local → `id` 回 app UID。
8. `adb shell id`（one-shot 仍正常）。

**`REAL_HUMAN_INTERACTIVE_SHELL = 待执行`**。

## 13. 已知修复（root cause）

- **JNI 签名 bug**（`cli_bridge_server.cpp`）：`onInteractiveData` 的
  `GetMethodID` 签名 `(IIB[V)V`（`B[` 顺序错）→ 返回 null → 首次 interactive
  触发 `JNI DETECTED ERROR: mid == null` SIGABRT。修为 `(II[B)V` + nativeStart
  非空校验。
- **Eof 未映射**（`AdbCliBridgeServer`）：ADB backend 以 `ATSessionEvent.Eof`
  表达 natural remote exit，listener 原忽略 → relay 永不终止。改为 Eof→T_EXIT。
