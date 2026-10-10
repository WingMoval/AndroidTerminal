# AT.PERSIST-0 — Local Backend + Persistent Terminal Vertical Slice

> 本轮任务：把 V2 已验证 Local one-shot + Local PTY 能力按 Canonical V1 迁入 V3 `:at-backend-local`，并让 Persistent Terminal 第一次真实穿过 `AT Engine → Backend Contract → Local execution provider` 运行。
>
> 最终判定：**AT.PERSIST-0 = PARTIAL**（实现 + 编译 + Contract reality check 完成；**真机 P1–P10 待设备连接，当前 BLOCKED_BY_NO_DEVICE**）
>
> **LOCAL_BACKEND = EXTRACTED / COMPILED**（真实实现完成，非 STUB）
> **CONTRACT_STATUS = DRAFT → REVISED_DRAFT**（C1/C2/C3 reality check 后修订）
> **CONTRACT_FREEZE = NO**

---

## 1. Scope

- **IN SCOPE**：Local one-shot 迁入 `:at-backend-local`（ATOneShotBackend）；Local PTY 迁入（ATPersistentBackend + TerminalSession impl）；AT Engine real vertical slice（consumer→ATEngine→Local backend→PTY→sh）；Contract reality check（C1 read/listener、C2 EOF/error、C3 ownership）；编译/单测；待设备真机 P1–P10。
- **OUT OF SCOPE**：ADB Backend runtime、AT.PERSIST-1、Emulator、Screen State、UI、AG TerminalTool、SSH、attach/detach、multi-client、record/replay、Contract Freeze。`:adb-engine` 未改。

## 2. Git / 环境基线

```text
V3 HEAD  : 56fadfd（AT.CONTRACT-0）
V3 status: clean（本轮开始）
V2 HEAD  : 726a441（只读；本轮未改动）
设备     : 未连接（用户指示先完成无实机部分，等待连接）
```

## 3. V2 Evidence / Source Basis

| 来源 | 判定 | 迁移依据 |
|---|---|---|
| `ATsh.T0` / `T0.5` | VERIFIED | Local one-shot：ProcessBuilder → `/system/bin/sh -c`；stdout/stderr/exitCode 分离；cwd/env 不保持；App UID principal |
| `ATsh.T1` | VERIFIED | Local PTY persistent shell：真实 `/dev/pts/0`、双向 I/O、cwd/env 持久、前台 stdin、Ctrl-C、resize、cleanup |
| V2 源码 `LocalShellExecutor.kt` / `ShellResult.kt` / `LocalPtySession.kt` / `PtyNative.kt` / `local_pty.{h,cpp}` / `local_pty_jni.cpp` | 源码事实 | 迁移核心行为（posix_openpt/grantpt/unlockpt/fork/setsid/TIOCSCTTY/dup2/execve/TIOCSWINSZ/wait+cleanup） |

## 4. Migration Details

### 4.1 Local one-shot → `LocalOneShotBackend`（ATOneShotBackend）

- 迁移 V2 `LocalShellExecutor` 语义（ProcessBuilder → sh -c，双线程并发 drain 防 pipe 死锁）。
- 实现 `ATExecutionRequest → execute() → ATExecutionResult`。
- **非零 process exit = COMPLETED**（exitCode 表达），**spawn 失败 = TRANSPORT_ERROR**（infrastructure failure 分离）。

### 4.2 Local PTY → `:at-backend-local`

| V2 源 | V3 目标 | 方式 |
|---|---|---|
| `local_pty.{h,cpp}` | `:at-backend-local/src/main/cpp/local_pty.{h,cpp}` | **迁入**（忠实迁移核心行为，非重写） |
| `local_pty_jni.cpp` | `local_pty_jni.cpp` | JNI 适配（`com.at.backend.local.LocalPtyNative`） |
| `PtyNative.kt` | `LocalPtyNative.kt` | JNI binding 适配 |
| `LocalPtySession.kt` | `LocalPtySession.kt` | V3 package + `start()` factory 适配 |
| — | `LocalPersistentBackend.kt` | **新实现**：ATPersistentBackend + LocalTerminalSession（ATTerminalSession impl） |

### 4.3 Architecture Call Chain（本轮实际可执行路径）

```text
consumer / test host
        ↓
ATEngine.openSession(...)
        ↓
ATPersistentBackend
        ↓
LocalPersistentBackend
        ↓
LocalPtySession.start()   →  real PTY + /system/bin/sh -i
        ↓
LocalTerminalSession       （AT Engine session impl）
```

## 5. Local one-shot mapping

- `command` → `/system/bin/sh -c <cmd>`。
- stdout/stderr 分离；exitCode 保留；非零 exit ≠ backend failure。
- `oneshot_local` instrumentation case 已写入 harness（`echo LOCAL_OK` → COMPLETED）。

## 6. Local PTY mapping

- open：`posix_openpt/grantpt/unlockpt/ptsname` → `fork` → child `setsid/TIOCSCTTY/dup2/execve("sh","-i")`。
- write：`write(master, bytes)`。
- read：`poll + read`（timeout）。
- resize：`ioctl(TIOCSWINSZ)`。
- Ctrl-C：`write(0x03)` 透传 → 前台 SIGINT。
- cwd/env：同一 `sh -i` 进程内保持。
- exit：进程退出 → read 端 EOF。
- close/cleanup：`close(master)` + `kill(SIGHUP)` + `waitpid`（SIGKILL fallback）。

## 7. JNI / native ownership

- native `at_local_backend.so` 拥有 master fd + child pid；`LocalPtySession` 持 fd/pid 句柄；`LocalTerminalSession`（AT 层）包装之。
- JNI symbols 6/6 与 `LocalPtyNative` externals 匹配（readelf 验证）。
- fd 关闭 / child reap 全在 native `close_pty`，Kotlin 不重复管理。

## 8. TerminalSession ownership（C3 结论）

- 结论：**implementation placement**（backend 返回 ATTerminalSession impl），**logical ownership 仍属 AT Engine**。
- backend 提供底层 primitive（`LocalPtySession`）；`LocalTerminalSession` 是 AT-domain view。
- 不引入额外 backend-session primitive（避免过度设计）；ADB slice 若暴露需要再评估。
- 证据：consumer 只经 `ATSessionState/Event/ATTerminalSession` 交互；backend 无独立 session 生命周期暴露。

## 9. Lifecycle

- `OPENING → ACTIVE → (EXITED | FAILED | CLOSED)`（与 Contract Draft 一致，未增状态）。
- `EXITED` = 进程退；`FAILED` = backend 错误；`CLOSED` = 调用方 close。
- EOF 无显式 exit code → 归 `EXITED`（`read` 返回 null，state=EXITED）。

## 10. Contract Reality Checks

### C1 — read() 与 listener 是否重复/冲突

- **结论：并存但互斥为同一流的两种消费模式。**
- `setListener` 启动后台 reader 独占 fd 并分发事件；listener 激活期间 `read` 不是合法并发消费者（默认 pull）。
- 禁止两者抢同一份数据。已在 Contract Draft §5.3 记录 REVISION。
- 实现：`LocalTerminalSession` 中 listener 非空时 `read` 返回 null；reader 线程持有读取权。

### C2 — read()==null 语义

- **结论：null = 会话已结束；`state` 区分 EXITED/FAILED/CLOSED/EOF。**
- `read` 永不把 process exit / failure / caller close / EOF 混淆为同一状态。
- timeout 返回**空数组**（会话仍 ACTIVE），非 null。
- 已在 Contract Draft §5.3 记录 REVISION。

### C3 — Backend 是否被迫拥有 TerminalSession

- **结论：不构成 ownership 反转**（见 §8）。
- `ATPersistentBackend.openSession → ATOpenSessionResult → ATTerminalSession` 让 backend 构造 AT-domain session 对象，但这是 implementation placement；logical ownership 在 AT Engine。

## 11. Contract Deltas（DRAFT → REVISED_DRAFT）

| 变更 | 内容 | 驱动 |
|---|---|---|
| §5.3 | `read`/`setListener` 消费模式互斥规则；`read==null` 语义（会话结束，state 区分）；timeout=空数组 | C1/C2 |
| §6 | C3 ownership 结论（implementation placement，无反转） | C3 |
| §8 | Local mapping 标记 IMPLEMENTED | 实现落地 |

**未改**：类型形状、状态集合、事件集合。仍 DRAFT，未冻结。

## 12. Real-device Evidence（待设备）

- 设备未连接。P1–P10 均已写成 instrumentation harness（`LocalPersistentSliceTest`），设备接入后运行。
- 当前状态：**BLOCKED_BY_NO_DEVICE**（不伪称 PARITY/VERIFIED）。
- P1 真 PTY / P2 principal / P3 persistent I/O / P4 cwd / P5 env / P6 stdin / P7 Ctrl-C / P8 resize / P9 exit / P10 close+cleanup + one-shot case 全部在 harness 中。

## 13. Cleanup Evidence

- native `close_pty`：close(master) → SIGHUP → waitpid（SIGKILL fallback）；无 zombie。
- `LocalTerminalSession.close()`：幂等；多次 close 不崩溃（harness p10 覆盖）。

## 14. Build / Unit Tests

```powershell
.\gradlew.bat clean assembleDebug :at-backend-local:assembleDebugAndroidTest :at-engine:testDebugUnitTest
BUILD SUCCESSFUL
```

- `:at-engine` unit tests 6/6 PASS（执行/会话语义）。
- `:at-backend-local` native `at_local_backend.so` + androidTest APK 编译通过。
- JNI ABI 6/6 匹配。

## 15. Migration Status

```text
LocalShellExecutor → LocalOneShotBackend  = EXTRACTED / COMPILED
LocalPtySession / PtyNative / local_pty.* → Local backend  = EXTRACTED / COMPILED
真机 parity（P1–P10）                       = BLOCKED_BY_NO_DEVICE（harness 就绪）
```

## 16. Final Verdict

```text
AT.PERSIST-0 = PARTIAL（实现+编译+Contract revision 完成；真机待设备）

LOCAL_ONESHOT_MIGRATION      = EXTRACTED / COMPILED（LocalOneShotBackend 实现）
LOCAL_PTY_MIGRATION          = EXTRACTED / COMPILED（native local_pty + LocalPtySession + backend）
LOCAL_BACKEND                = IMPLEMENTED（LocalOneShotBackend + LocalPersistentBackend）
AT_ENGINE_VERTICAL_SLICE     = BUILT（consumer→ATEngine→Local backend→PTY→sh；harness 就绪）

REAL_PTY                     = BLOCKED_BY_NO_DEVICE（harness P1 就绪）
LOCAL_PRINCIPAL              = BLOCKED_BY_NO_DEVICE（harness P2 就绪）
PERSISTENT_IO                = BLOCKED_BY_NO_DEVICE（harness P3 就绪）
CWD_CONTINUITY               = BLOCKED_BY_NO_DEVICE（harness P4 就绪）
ENV_CONTINUITY               = BLOCKED_BY_NO_DEVICE（harness P5 就绪）
STDIN                        = BLOCKED_BY_NO_DEVICE（harness P6 就绪）
CTRL_C                       = BLOCKED_BY_NO_DEVICE（harness P7 就绪）
RESIZE                       = BLOCKED_BY_NO_DEVICE（harness P8 就绪）
PROCESS_EXIT                 = BLOCKED_BY_NO_DEVICE（harness P9 就绪）
EXPLICIT_CLOSE               = BLOCKED_BY_NO_DEVICE（harness P10 就绪）
CLEANUP                      = BLOCKED_BY_NO_DEVICE（native close_pty 已实现；harness P10 就绪）

READ_LISTENER_MODEL          = REVISED_DRAFT（C1：互斥消费模式）
EOF_ERROR_MODEL              = REVISED_DRAFT（C2：null=结束，state 区分）
SESSION_OWNERSHIP_MODEL      = CONFIRMED（C3：implementation placement，AT Engine 逻辑所有）

CONTRACT_STATUS              = REVISED_DRAFT
CONTRACT_FREEZE              = NO

AT_ENGINE_INDEPENDENCE       = VERIFIED（:at-engine 零 backend/adb/UI 依赖）
LOCAL_BACKEND_INDEPENDENCE   = VERIFIED（:at-backend-local 只依赖 :at-engine + bionic/kernel）
BUILD                        = PASS（clean assembleDebug + androidTest + unit 6/6）

V2_MODIFIED                  = NO
ADB_ENGINE_RUNTIME_CHANGED   = NO

MIGRATION_STATUS             = Local backend EXTRACTED/COMPILED；真机 parity BLOCKED_BY_NO_DEVICE
NEXT                         = 设备连接后跑 P1–P10 → AT.PERSIST-1（需 Controller 授权）
```

**结论**：AT.PERSIST-0 完成实现侧全部工作——Local one-shot 与 Local PTY 已按职责迁入 `:at-backend-local`，`ATEngine → Local backend → PTY → sh` 完整调用链已构建并可编译；Contract reality check（C1/C2/C3）完成并修订 Draft；真机 P1–P10 已全部写入 instrumentation harness，**待设备连接后执行**（当前 BLOCKED_BY_NO_DEVICE，不伪称 PARITY/VERIFIED）。Contract 保持 DRAFT（REVISED_DRAFT），未冻结。

本轮未开始 `AT.PERSIST-1` / `AT.EMULATOR-0` / `AT.CONTRACT-1` / APK 功能 / `AG.TERMINAL-*`。任务结束，STOP。