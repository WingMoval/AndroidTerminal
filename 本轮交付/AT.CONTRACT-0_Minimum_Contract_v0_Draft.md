# AT.CONTRACT-0 — AT Engine Minimum Contract v0 Draft

> **本轮性质：CONTRACT DRAFT（非 Final API Freeze）。**
>
> 目标：在 Canonical V1 已冻结的职责边界下，基于 V2 已验证行为 + V3 已独立 ADB Engine，设计并落地一套最小 AT Engine Contract v0 Draft，为 AT.PERSIST-0（Local）与 AT.PERSIST-1（ADB）提供共同接口。
>
> 状态语义：
> ```text
> ARCHITECTURE      = FROZEN（Canonical V1）
> CONTRACT          = DRAFT
> IMPLEMENTATION    = NOT YET VALIDATED BY BOTH BACKENDS
> ```

---

## 1. Context

- **Canonical V1** 冻结：AT Engine = one-shot execution + persistent terminal（TerminalSession / Emulator / Screen State）+ backend abstraction；AT Engine ↔ ADB Engine = consumer→provider。
- **AT.V3-1** 已把 V3 `:adb-engine` 独立（identity/TLS/connection/one-shot/interactive/pairing + canonical JNI/Kotlin facade），真机验证 connect/one-shot/reuse/interactive PASS。
- `:at-engine` 在 AT.V3-0 仅为空占位；本轮回填 **contract domain types + 极薄 facade + contract unit tests**。
- `:at-backend-adb` / `:at-backend-local` 本轮加入 **STUB / CONTRACT PROBE**（仅证明可自然实现契约，非 runtime）。

## 2. Evidence Basis

| Evidence | 判定 | 对本轮的意义 |
|---|---|---|
| ATsh.T0 / T0.5 | VERIFIED | Local one-shot（ProcessBuilder → sh -c）：stdout/stderr/exitCode 分离、cwd/env 不保持、App UID principal |
| ATsh.T1 | VERIFIED | Local PTY persistent shell：真实 `/dev/pts/0`、双向字节流、cwd/env 持久、前台 stdin、Ctrl-C、resize、cleanup |
| ADB.I0 | VERIFIED | ADB remote PTY：`shell,v2,pty`、uid=2000(shell)、connection reuse、exit 帧、transport-loss 无 replay |
| AT.V3-1 | COMPLETE | V3 `AdbEngine.executeShell` / `AdbInteractiveStream` / `AdbShellResult` 已独立并真机验证 |

**结论**：one-shot 与 persistent 的**共同语义**在两 provider 均有实证支撑；Contract v0 只表达这些共同语义，不发明 provider-specific 细节。

## 3. Goals / Non-goals

### Goals

- 定义 AT Engine 对外最小能力（one-shot + persistent 平级）。
- 明确 one-shot input/result 最小模型 + exit vs failure 区分。
- 明确 persistent session 最小 lifecycle + byte-stream 语义。
- 明确 backend capability contract（小接口 + composition）。
- 提供 Local / ADB 的可实现性证明（contract probe）。
- 锁定 `:at-engine` 零 backend 依赖。

### Non-goals（本轮明确不做 / DEFERRED）

```text
SSH-specific API
multi-client
attach / detach
shared Human-Agent session
session migration
record / replay
screen diff
workspace / plugin
advanced keyboard abstraction
daemon / multiplexer
Terminal Emulator 实现
Screen State API（正式）
AndroidTerminal APK 新功能
AG TerminalTool
```

## 4. One-shot Semantic Contract（DRAFT）

### 4.1 输入模型

```kotlin
data class ATExecutionRequest(
    val command: String,      // 命令行（Local: sh -c <cmd>; ADB: shell,v2,raw:<cmd>）
    val timeoutMs: Int = 0,   // 0 = 无超时；是否支持超时属 backend capability
)
```

- 最小需求 = 一条 command line；结构化 argv/program 形式 **DEFERRED**（直到有 slice 需要）。
- timeout 是 **调用 option**，但其是否被遵守是 backend capability（§7）。

### 4.2 结果模型

```kotlin
data class ATExecutionResult(
    val outcome: ATExecutionOutcome,  // COMPLETED / TRANSPORT_ERROR / UNSUPPORTED
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val hasError: Boolean,
    val error: String?,
)
```

- **stdout/stderr 保留分离**（T0/I0 实证；one-shot 不做 PTY 合流）。
- **exitCode 与 execution failure 严格区分**：
  - 非零 process exit（如 `exit 7`）= `COMPLETED` + exitCode=7，**非** failure。
  - transport/backend 失败（无法产生结果）= `TRANSPORT_ERROR` / `hasError=true`。
  - 未接 backend / 不支持 = `UNSUPPORTED`。
- 设计来源：V2 `ExecResult` / `TerminalResult` 的 **正确语义被保留**，但**不复制**其 Kotlin shape（新 ADB-domain 已剥离，AT domain 重新表达）。

### 4.3 平级性

one-shot 是**独立 capability**（`ATOneShotBackend.execute`），**不是** persistent session 的派生。禁止 `PersistentSession.execute()` 强塞。

## 5. Persistent Terminal Semantic Contract（DRAFT）

### 5.1 会话 lifecycle（最小，基于 T1/I0）

```kotlin
enum class ATSessionState {
    OPENING,   // spawn/connect 中
    ACTIVE,    // 可读可写
    EXITED,    // 进程已退出（exitCode 可用）
    FAILED,    // backend/transport 失败（非进程退出）
    CLOSED,    // 调用方 close
}
```

- 状态数**不**为"看起来完整"而扩；attach/detach / DETACHED / REATTACHING **DEFERRED**。

### 5.2 事件模型

```kotlin
sealed class ATSessionEvent {
    Data(bytes: ByteArray)          // 输出字节（PTY 合流：stdout+stderr）
    Resized(rows, cols)
    Exited(exitCode)
    Eof
    Failed(error)
    Closed
}
```

- **输出 = 字节流**（persistent 下 stdout/stderr 合流，T1/I0 实证）。
- **exit = 事件**（非返回值）：`Exited(code)`；EOF 单独表达。
- **close / process exit / failure 区分**：`Closed`（调用方关）vs `Exited`（进程退）vs `Failed`（backend 错）。

### 5.3 `write(bytes)` / `read` / `resize`

```kotlin
interface ATTerminalSession {
    fun write(bytes: ByteArray): Int     // 写输入；-1 = 不可写
    fun read(timeoutMs: Int): ByteArray? // 读输出；null = EOF/error
    fun resize(rows: Int, cols: Int): Boolean
    fun close(terminateProcess: Boolean = true)
}
```

- `write(bytes)` = 字节级输入（无需键盘抽象 / 复杂 IME；Canonical §18）。
- output 交给 AT Engine 的消费方式 = `setListener((ATSessionEvent) -> Unit)`（**IMPLEMENTATION SHAPE PROVISIONAL**，见 §9）。

## 6. TerminalSession Draft 边界

```kotlin
interface ATTerminalSession {
    val state: ATSessionState
    fun write(bytes: ByteArray): Int
    fun read(timeoutMs: Int): ByteArray?
    fun setListener(listener: (ATSessionEvent) -> Unit)
    fun resize(rows: Int, cols: Int): Boolean
    fun close(terminateProcess: Boolean = true)
}
```

- **TerminalSession ≠ Backend stream**：Session 是 AT Engine 域的逻辑对象（聚合 backend + lifecycle）；Backend 只提供底层 interactive transport / process。
- **不假装有最终 screen observation API**：Emulator / Screen State 本轮**未实现**，未来数据链
  ```text
  Backend bytes → TerminalSession → Terminal Emulator → Screen State
  ```
  为未来只留**极薄**边界（`ATSessionEvent.Data(bytes)`），标记 PROVISIONAL / DEFERRED，不发明 screen API。

## 7. Backend Capability Contract（DRAFT）

**不**用一个大 `Backend` interface；用**小 capability 接口 + composition**：

```kotlin
interface ATOneShotBackend {
    fun execute(request: ATExecutionRequest): ATExecutionResult
}

interface ATPersistentBackend {
    data class Launch(command, initialRows, initialCols, env)
    fun openSession(launch: Launch): ATOpenSessionResult
}
```

- Provider 可实现一个或两个 capability。
- **Backend 不承担 Terminal Emulator 职责**（AT domain，deferred）。
- `ATOpenSessionResult.Success(session)` / `Failure(reason)` 表达 open 结果。

## 8. Provider Reality Check

### Local mapping（ATsh.T0/T1 evidence）

| Contract | Local 自然映射 |
|---|---|
| `ATOneShotBackend.execute` | ProcessBuilder → `/system/bin/sh -c`（T0）→ stdout/stderr/exitCode 分离 |
| `ATPersistentBackend.openSession` | posix_openpt → fork → setsid → TIOCSCTTY → exec `sh -i`（T1）→ 字节流/read/write/resize/close |
| `ATSessionEvent` | PTY read → Data；进程退 → Exited；master 关 → Eof/Closed；errno → Failed |
| resize | ioctl(TIOCSWINSZ) |
| Ctrl-C | 0x03 字节经 PTY → SIGINT（行为，不属 API） |
| cwd/env 持续 | 会话内保持（行为，不属 API） |

### ADB mapping（V3 AdbEngine / AdbInteractiveStream）

| Contract | ADB 自然映射 |
|---|---|
| `ATOneShotBackend.execute` | `AdbEngine.executeShell` → `AdbShellResult`（V3 真机 PASS）→ stdout/stderr/exitCode |
| `ATPersistentBackend.openSession` | `AdbEngine.openInteractive("shell,v2,pty:")` → `AdbInteractiveStream`（V3 真机 PASS）|
| `ATSessionEvent` | stream read → Data；shell-v2 exit 帧 → Exited；CLSE/read=-1 → Eof/Closed；transport error → Failed |
| resize | shell-v2 winsize 帧 |
| Ctrl-C | 0x03 字节经 stdin 帧 → 远程 SIGINT |
| connection reuse | stream close 不关 connection（I0/V3） |

**结论**：两条 provider 都能自然实现同一个 contract；无"只有一端需要"的项被强行放入 AT domain。

## 9. read / callback / Flow（IMPLEMENTATION SHAPE）

- 本轮选择 **callback listener + 阻塞 read** 作为**最小可编译形态**：
  ```kotlin
  IMPLEMENTATION SHAPE = PROVISIONAL
  SEMANTIC CONTRACT    = 消费方收到 bytes + lifecycle 事件；写输入 = write(bytes)
  ```
- **不得**把 callback/suspend/Flow 宣布为最终架构决策（Canonical §27 / 本轮 §9）。后续 slice 可改为 suspend/Flow，不影响语义契约。

## 10. Launch Target

- 默认验证目标 = `/system/bin/sh`（Local `sh -i` / ADB `shell,v2,pty:` 空命令）。
- Contract **不**定义 `Terminal = Shell`。
- 最小启动描述 = `Launch(command, rows, cols, env)`（够 Local/ADB slice 用）；复杂通用 LaunchTarget system **DEFERRED**。

## 11. Dependency Direction（独立性审计结果）

```text
:at-engine            →（零依赖：无 :adb-engine / :at-backend-* / :app / :test-host / UI / ADB impl）
:at-backend-adb       → :at-engine + :adb-engine   （consumer→provider，正确）
:at-backend-local     → :at-engine
:app / :test-host     → :at-engine（未来）+ backends
```

- `:at-engine` 源码无任何 `adb.engine` / `at.backend` / `com.androidterminal` import（命中均为注释）。
- 反向依赖不存在。**AT_ENGINE_INDEPENDENCE = VERIFIED**。

## 12. Provisional Decisions（本轮明确标记）

```kotlin
// IMPLEMENTATION SHAPE = PROVISIONAL:
ATEngine facade（skeleton）          // 仅契约骨架
setListener((ATSessionEvent) -> Unit) // callback 形态；可改 suspend/Flow
ATSessionEvent.Data(bytes)            // 为 Emulator 预留的极薄边界
// 命名 / package / 是否用 interface vs abstract 均未冻结
```

## 13. Deferred Decisions（明确 DEFERRED）

```text
Terminal Emulator 实现
Screen State API
structured argv LaunchTarget
attach / detach / multi-client / handoff
session migration / record / replay
timeout 的真实实现（是否遵守属 backend capability）
advanced keyboard / IME
SSH backend
shared Human-Agent session
```

## 14. Emulator / Screen State 未来接入（不污染本轮）

- 未来：`ATSessionEvent.Data(bytes)` 是**唯一**给 Emulator 的输入；Emulator 产出 Screen State（独立数据，无 UI）。
- 本轮**不**定义 ScreenState 数据结构 / renderer API；不假装已有 screen observation。
- 接入点 = TerminalSession 与 consumer 之间；Contract 只承诺"输出 = 字节 + 事件"，Emulator 是纯增量，不破坏 v0。

## 15. 实现落盘（`:at-engine`）

| 文件 | 内容 |
|---|---|
| `ATExecution.kt` | `ATExecutionOutcome` / `ATExecutionResult` / `ATExecutionRequest` |
| `ATSession.kt` | `ATSessionState` / `ATSessionEvent` / `ATTerminalSession` / `ATOpenSessionResult` |
| `ATBackend.kt` | `ATOneShotBackend` / `ATPersistentBackend`（+ Launch） |
| `ATEngine.kt` | 极薄 facade skeleton（one-shot + persistent 平级，注入 backend） |
| `src/test/.../ATContractTest.kt` | contract 语义 unit tests（6 项 PASS） |

`:at-backend-adb` / `:at-backend-local`：`*Probe.kt`（STUB / CONTRACT PROBE，标记非 runtime）。

## 16. 验证

```powershell
.\gradlew.bat clean :at-engine:testDebugUnitTest :at-backend-adb:compileDebugKotlin :at-backend-local:compileDebugKotlin assembleDebug
BUILD SUCCESSFUL
```

- unit tests：`ATExecutionContractTest`（3）+ `ATSessionContractTest`（3）**全部 PASS**。
- dependency direction 审计 PASS。
- **未改 `:adb-engine` 已验证语义 / JNI / runtime**（本轮零改动该模块）。

## 17. Contract Freeze Gates（本轮后仍不得 Freeze）

```text
AT.PERSIST-0  Local Persistent Vertical Slice
        ↓
AT.PERSIST-1  ADB Persistent Vertical Slice
        ↓
必要的 Emulator interaction validation
        ↓
Contract revision
        ↓
AT.CONTRACT-1 Freeze
```

Local 与 ADB 两条真实路径未完成前：

```text
FINAL CONTRACT FREEZE = NOT AUTHORIZED
```

## 18. Final Verdict

```text
AT.CONTRACT-0 = COMPLETE

ARCHITECTURE_BOUNDARY      = UNCHANGED（Canonical V1 未改）
CONTRACT_STATUS            = DRAFT
ONESHOT_CONTRACT           = DRAFT（ATExecutionRequest/Result/Outcome；exit vs failure 分离）
PERSISTENT_CONTRACT        = DRAFT（ATSessionState/Event/TerminalSession；字节流 + 事件）
BACKEND_CONTRACT           = DRAFT（ATOneShotBackend / ATPersistentBackend，小接口 + composition）
TERMINAL_SESSION_DRAFT     = DRAFT（逻辑 Session，非 backend stream；无 screen API）
LOCAL_MAPPING              = CONFIRMED（T0/T1 evidence；contract probe 编译通过）
ADB_MAPPING                = CONFIRMED（V3 AdbEngine/AdbInteractiveStream；contract probe 编译通过）
AT_ENGINE_INDEPENDENCE     = VERIFIED（零 backend/adb/UI 依赖）
BUILD                      = PASS（clean + unit tests 6/6）

FINAL_API_FREEZE           = NO
V2_MODIFIED                = NO
ADB_ENGINE_RUNTIME_CHANGED = NO

NEXT                       = AT.PERSIST-0（Local Persistent Vertical Slice，需 Controller 授权）
```

**结论**：AT.CONTRACT-0 完成。AT Engine 最小 Contract v0 Draft 已落地——one-shot 与 persistent 平级、backends 通过小 capability 接口组成、Local/ADB 均证明可自然实现、`:at-engine` 独立。所有 API shape 标记 PROVISIONAL；Contract Freeze 明确 NOT AUTHORIZED，须待 AT.PERSIST-0/1 真实 slice 验证。

本轮未开始 `AT.PERSIST-0` / `AT.PERSIST-1` / `AT.EMULATOR-0` / `AG.TERMINAL-*` / APK 功能。任务结束，STOP。