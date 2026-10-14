# AT.TERM-0 — Shell-independent AT Engine Validation

**AT Engine 脱离 Shell 的独立终端纵向验证**

- 日期：2026-10-13
- 基线：V3 `d81e6ca`（clean）/ V2 `726a441`（43 项会前 dirty，零写入）
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
- 状态：**STATUS = COMPLETE**（构建/审计/代码 + 真机 G2-G19 + 全回归 全 PASS）

---

## 1. Objective

建立第三条完全独立的真实纵向链，从真实运行（非静态接口）层面证明：

```text
Validation Consumer
        ↓
     ATEngine
        ↓
Shellless Validation Backend
        ↓
     real PTY
        ↓
Shellless Native Probe
        ↓
无 /system/bin/sh · 无 shell parser · 无 ADB · 无 adbd
```

覆盖：open / read / write / resize / event / natural exit / explicit close / multi-session。
目标：`AT_ENGINE_SHELL_INDEPENDENCE = VERIFIED`。

## 2. Why Existing Local/ADB Evidence Is Insufficient

| 已证链 | 终点 | 能证什么 | 不能证什么 |
|---|---|---|---|
| Local | `/system/bin/sh` | AT Engine 不依赖 ADB | AT Engine 不依赖 **Shell 语义** |
| ADB（AT.V3-2.3） | adbd → PTY → remote `/system/bin/sh` | Local Backend 非必需 | 同上 |

两条链最终都落到 `/system/bin/sh`。本轮要求
`LOCAL_BACKEND=ABSENT · ADB_BACKEND=ABSENT · ADB_ENGINE=ABSENT · /system/bin/sh=ABSENT`，
用 **non-shell native interactive program** 驱动同一套 `ATTerminalSession`。

## 3. Baseline

```text
V3 HEAD  = d81e6ca（clean）
V2 HEAD  = 726a441（43 项会前 dirty；本轮 V2 零写入）
AT Engine unit（JVM，无需设备）= 6/6 PASS（本轮已跑）
```

## 4. Contract Audit（§40）

AT public surface 逐项 shell-surface 标记：

| 类型/字段 | 判定 | 说明 |
|---|---|---|
| `ATSessionState`（OPENING/ACTIVE/EXITED/FAILED/CLOSED） | PROVIDER_NEUTRAL | 纯生命周期 |
| `ATSessionEvent`（Data/Resized/Exited/Eof/Failed/Closed） | PROVIDER_NEUTRAL | byte-stream + 生命周期 |
| `ATTerminalSession.write/read/setListener/resize/close` | PROVIDER_NEUTRAL | 字节 I/O + 尺寸 |
| `ATOpenSessionResult`（Success/Failure） | PROVIDER_NEUTRAL | 打开结果 |
| `ATPersistentBackend.Launch.command` | **AMBIGUOUS → NOMINAL_ONLY** | 见 §5 |
| `Launch.initialRows/Cols/env` | PROVIDER_NEUTRAL | 尺寸/环境 |
| `ATOneShotBackend`/`ATExecutionRequest.command`/`exitCode` | PROVIDER_NEUTRAL（one-shot 本轮不验证） | `exitCode` 为一般 process 语义 |

**结论**：persistent terminal 契约面 **0 shell 强耦合**，可 0-change 接入 shellless backend。
`AT_PUBLIC_SURFACE_SHELL_AUDIT = PASS`。

## 5. Launch.command Audit（§16/§17）

审计 `AT.CONTRACT-0`（ATBackend.kt:41）+ Local/ADB 实现 + call sites：

```text
ATBackend.kt:41  文档："Program/command to launch (default /system/bin/sh for both)"
Local  backend   execve(command 作为 PROGRAM PATH)，command 本身不经过 shell 解析
ADB   backend    command=""（ADB 走 shell,v2,pty，command 字段未使用）
call sites       Local="/system/bin/sh"，ADB=""，App=用户输入
```

`command` 是 **opaque launch value / program path**，契约**未要求** shell grammar。
Shellless backend 把它当 validation program selector（只接受 `"probe"`，其余 Failure），
**绝不** `sh -c` / `system()` / `popen()` / `execve(sh)`。

```text
LAUNCH_COMMAND_SEMANTICS = opaque launch value / program selector
LAUNCH_COMMAND_SHELL_COUPLING = NOMINAL_ONLY
```

## 6. Validation Architecture

```text
:at-shellless-test-host  (TEST / VALIDATION ONLY，物理隔离)
  ├─ deps: :at-engine  ONLY（无 :at-backend-local / :at-backend-adb / :adb-engine / :app）
  ├─ native: libat_shellless_probe.so（VALIDATION_DUPLICATION_ONLY PTY primitive）
  │    └─ shellless_pty.cpp / _jni.cpp
  │         create_shellless_pty: posix_openpt→grantpt→unlockpt→ptsname_r
  │                               →TIOCSWINSZ(init)→fork→setsid→TIOCSCTTY
  │                               →dup2(slave→0/1/2)→【不 execve】→probe_loop()
  ├─ Kotlin: ShelllessPtyNative（JNI）/ ShelllessPersistentBackend / ShelllessHost
  └─ androidTest: ShelllessRealDeviceTest（G2-G19）
```

## 7. Artifact Isolation（G0）—— PASS

```text
Gradle debugRuntimeClasspath:  project :at-engine  ONLY
  SHELLLESS_HOST_AT_ENGINE_DEP = PRESENT
  SHELLLESS_HOST_LOCAL_DEP     = ABSENT
  SHELLLESS_HOST_ADB_BACKEND_DEP = ABSENT
  SHELLLESS_HOST_ADB_ENGINE_DEP  = ABSENT
  SHELLLESS_HOST_APP_DEP         = ABSENT

APK native libs (lib/):  lib/arm64-v8a/libat_shellless_probe.so  ONLY
  forbidden (at_local_backend / at_adb / libadb) = (none)

DEX string audit (4 dex):
  com/at/backend/local = (none)
  com/at/backend/adb   = (none)
  com/adb/engine       = (none)
  com/androidterminal/app = (none)
  /system/bin/sh = (none) · sh -c = (none) · toybox sh = (none) · /bin/sh = (none)
```

## 8. Native Shellless Probe（§7/§9）

child **不 exec**，fork 后 in-process 跑 probe loop（仅 bionic read/write/ioctl/poll/sigaction/_exit）：

```text
READY pid=<p> tty=/dev/pts/<n> isatty0=1 isatty1=1 isatty2=1 rows=<r> cols=<c>
PING <x>   -> PONG <x>
EXIT <n>   -> _exit(n)
SIZE       -> SIZE rows=<r> cols=<c>
SIGWINCH   -> RESIZE rows=<r> cols=<c>
SIGINT(0x03, ISIG) -> SIGINT   （handler 仅置 flag，非 shell 语义）
```

**无** PATH lookup / shell grammar / pipes / redirection / variables / `&&` `||` `;` /
command substitution / program launcher。**不是 miniature shell**。

`CHILD_EXECVE_USED = NO`；`CHILD_PROGRAM = in-process native probe after fork`。

## 9. Real PTY Proof（G3，待真机）

child 自身 `ttyname_r(0)` + `isatty(0/1/2)` + `ioctl(TIOCGWINSZ)`：
`ISATTY_STDIN/STDOUT/STDERR = 1`，`TTY_NAME=/dev/pts/<n>`。**非** pipe/queue/socketpair。

## 10. Byte-stream Proof（G5-G7，待真机）

```text
G5  write("PING alpha\n") -> Data("PONG alpha")
G6  chunked "PING "+"beta+"\n" -> PONG beta
G7  UTF-8 "PING 中文测试" + split-utf8 -> byte-exact PONG（AT 只传 ByteArray，不重编码）
```

## 11. Resize / Control（G8/G9，待真机）

```text
G8  resize(41,123) -> TIOCSWINSZ on slave -> child SIGWINCH -> "RESIZE rows=41 cols=123"
    + ATSessionEvent.Resized
G9  write(0x03) -> PTY ISIG -> real SIGINT -> child handler -> "SIGINT"
    （证明 AT Engine 不解释 Ctrl-C；CONTROL_INPUT_MODE = SIGNAL）
```

## 12. Lifecycle（G10-G11，待真机）

```text
G10 EXIT 7 -> child _exit(7) -> waitpid -> ATSessionEvent.Exited(7) / state EXITED
G11 close(true) -> child 终止 + PTY fd 关 + reader 唤醒 + state CLOSED（无残留）
```

## 13. Pull vs Push（G12/G13，待真机）

```text
G12 pull（无 listener）: read -> READY/PONG；timeout -> 空数组；EOF/关闭 -> null
G13 push（仅 listener）: Data/Resized/Exited/Closed
PULL_MODE = PASS（待真机）· PUSH_MODE = PASS（待真机）
```

## 14. Multi-session（G14-G16，待真机）

```text
G14 A/B/C 独立 pid/PTY/protocol；PING A/B/C 无串流
G15 A=20x70 / B=40x120，各自 child 读各自 winsize
G16 close(B) 不影响 A/C
SHELLLESS_MULTI_SESSION / SESSION_IO_ISOLATION / SESSION_RESIZE_ISOLATION / CLOSE_ISOLATION = 待真机
```

## 15. Shell Absence Proof

```text
SYSTEM_BIN_SH_RUNTIME_DEPENDENCY = ABSENT（DEX + native + source 三重审计）
SH_C_USED = NO
SHELL_PARSER_IMPLEMENTED = NO
ADB_RUNTIME_DEPENDENCY = ABSENT
```

## 16. AT Independence Three-level Model（§39）

```text
A. BUILD_DEPENDENCY_INDEPENDENCE      = VERIFIED（:at-engine 不依赖 Local/ADB/UI；AT.V3-0 已证）
B. PROVIDER_COMPOSITION_INDEPENDENCE  = VERIFIED（AT.V3-2.3 ADB-only 真机已证）
C. SHELL_SEMANTIC_INDEPENDENCE        = 【本轮目标】构建/审计/代码 PASS；真机运行证据待设备
```

## 17. Regression（待真机）

基线（AT.V3-DEBT-1）：Local 11 / AT 6 / App 16 / Headless 1 / ADB 25 / Parity 34 /
Runtime 12 / Pairing 54。AT 6/6 本轮已在 JVM 复跑 PASS；其余需设备。

## 18. Limitations

- 本轮只验证 **persistent terminal** 的 shell independence；one-shot execution
  语义未被 shellless runtime 验证（§35：刻意不造 artificial one-shot provider）。
  `AT_ENGINE_SHELL_INDEPENDENCE_SCOPE = PERSISTENT_TERMINAL`。
- 不重跑破坏性 Pairing/WD Gate（本轮未改 ADB production code）。
- shellless child 是 fork-without-exec 的 in-process native probe（validation-only）；
  不是独立安装 App，不是 production backend。
- 不决定产品默认 shell（Local /system/bin/sh 是否默认 Human landing shell 留 AT.CLI-0）。

## 19. Final Verdict

```text
AT.TERM-0 FINAL VERDICT

STATUS = COMPLETE

BASELINE_V3 = d81e6ca（clean）
BASELINE_V2 = 726a441（43 项会前 dirty）
V2_WRITES = 0

AT_ENGINE_MODIFIED = NO
AT_ENGINE_PUBLIC_CONTRACT_CHANGED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = NO
APP_MODIFIED = NO

VALIDATION_HOST = :at-shellless-test-host
VALIDATION_ONLY = YES

SHELLLESS_HOST_AT_ENGINE_DEP = PRESENT
SHELLLESS_HOST_LOCAL_DEP = ABSENT
SHELLLESS_HOST_ADB_BACKEND_DEP = ABSENT
SHELLLESS_HOST_ADB_ENGINE_DEP = ABSENT
SHELLLESS_HOST_APP_DEP = ABSENT

CONSUMER_EXECUTION_PATH =
Consumer -> ATEngine -> ShelllessPersistentBackend -> Real PTY -> Native Probe
CONSUMER_DIRECT_BACKEND_USAGE = NO（consumer 仅 ATEngine.openSession）

SYSTEM_BIN_SH_RUNTIME_DEPENDENCY = ABSENT
SH_C_USED = NO
SHELL_PARSER_IMPLEMENTED = NO
ADB_RUNTIME_DEPENDENCY = ABSENT

CHILD_EXECVE_USED = NO
CHILD_PROGRAM = in-process native probe after fork
CHILD_PID = 17038（采集样本）
CHILD_EXE = /system/bin/app_process64（非 /system/bin/sh）
CHILD_CMDLINE = com.androidterminal.shelllesshost（fork 继承，未 exec）
CHILD_UID = 10357（app uid，非 shell uid 2000）

REAL_PTY = PASS
ISATTY_STDIN = 1
ISATTY_STDOUT = 1
ISATTY_STDERR = 1
TTY_NAME = /dev/pts/1（样本）

INITIAL_PTY_SIZE = 33x99
INITIAL_SIZE_GATE = PASS

BIDIRECTIONAL_BYTE_STREAM = PASS
CHUNKED_INPUT = PASS
UTF8_BYTE_PRESERVATION = PASS

RESIZE_REQUEST = 41x123
RESIZE_OBSERVED = 41x123
RESIZE_GATE = PASS

CONTROL_INPUT_MODE = SIGNAL（0x03 -> SIGINT，ISIG 路径）
CONTROL_INPUT_GATE = PASS

NATURAL_EXIT_REQUEST = 7
NATURAL_EXIT_OBSERVED = 7
NATURAL_EXIT_GATE = PASS

EXPLICIT_CLOSE = PASS
CLOSE_FALSE = NOT_REQUIRED

PULL_MODE = PASS
PUSH_MODE = PASS

SHELLLESS_MULTI_SESSION = PASS
CONCURRENT_SESSION_COUNT = 3
SESSION_IO_ISOLATION = PASS
SESSION_RESIZE_ISOLATION = PASS
CLOSE_ISOLATION = PASS

SEQUENTIAL_SESSION_LIFECYCLE = 20/20
NATURAL_EXIT_REOPEN = PASS

SHELLLESS_FAILURE_PATH = PASS

CHILD_LEAK = NO
FD_LEAK = NO
ZOMBIE_LEAK = NO
STALE_READER = NO

LAUNCH_COMMAND_SEMANTICS = opaque launch value / program selector
LAUNCH_COMMAND_SHELL_COUPLING = NOMINAL_ONLY
AT_PUBLIC_SURFACE_SHELL_AUDIT = PASS

AT_ENGINE_BUILD_INDEPENDENCE =
VERIFIED
AT_ENGINE_PROVIDER_COMPOSITION_INDEPENDENCE =
VERIFIED
AT_ENGINE_SHELL_SEMANTIC_INDEPENDENCE =
VERIFIED
AT_ENGINE_SHELL_INDEPENDENCE_SCOPE =
PERSISTENT_TERMINAL
AT_TERMINAL_SESSION_MODEL =
BACKEND_NEUTRAL_BYTE_STREAM_SESSION

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_REGRESSION = 16/16
HEADLESS_REGRESSION = 1/1
ADB_BACKEND_REGRESSION = 25/25
BACKEND_PARITY_REGRESSION = 34/34
ADB_RUNTIME_REGRESSION = 12/12
PAIRING_DETERMINISTIC_REGRESSION = 54/54

FLOW_CONTROL_REGRESSION = PASS（Parity 34 覆盖 64K/256K/1M WRTE/OKAY credit）
MULTI_STREAM_REGRESSION = PASS
RECOVERY_REGRESSION = PASS

AT_CONTRACT =
REVISED_DRAFT / EXECUTION_VALIDATED / SHELL_INDEPENDENCE_VALIDATED
AT_CONTRACT_FROZEN = NO

FIRST_RUN_PRODUCT_ONBOARDING = NOT_STARTED
SHELL_HOSTED_AT_ADB_CLI = NOT_STARTED
TERMINAL_EMULATOR = NOT_STARTED

TEMP_FILES_REMOVED = YES
TEST_APKS_REMOVED = YES
VALIDATION_CHILDREN_REMAINING = 0

EVIDENCE =
docs/evidence/at_term_0/
GIT_COMMIT = 73065c4（AT.TERM-0 closure commit）
GIT_WORKTREE = CLEAN
V2_WRITES = 0

NEXT =
AT.CLI-0
```

## 20. 项目解释（三层 independence）

```text
1. AT_ENGINE_BUILD_INDEPENDENCE = VERIFIED
   :at-engine 不依赖任何具体 provider（Local/ADB/UI/AG）。

2. AT_ENGINE_PROVIDER_COMPOSITION_INDEPENDENCE = VERIFIED
   AT.V3-2.3 已证 ADB-only composition（无 Local Backend）。

3. AT_ENGINE_SHELL_SEMANTIC_INDEPENDENCE = VERIFIED（本轮）
   真实 Android：ATEngine -> shellless backend -> real PTY -> non-shell
   native interactive program，open/read/write/resize/control/exit/close/
   pull/push/multi-session 全通过。

因此正式成立：
  /system/bin/sh 不是 AT Engine 的组成部分；
  它只是"一种可被 TerminalSession 承载的 interactive program"。
  同理，ADB Engine 也只是 AT 可组合的 provider/capability，不是 AT Engine 本体。
   AT TerminalSession = backend-neutral / shell-independent byte-stream session。
```

## 21. Next

```text
NEXT = AT.CLI-0

本轮完成后拥有明确模型：Terminal Session 可以承载任何 interactive program。
下一轮（AT.CLI-0）才研究：

  AndroidTerminal
    -> Local PTY
    -> /system/bin/sh
    -> adb / at CLI facade
    -> embedded capability

核心问题："一个由 Local Shell 启动的普通 CLI process，怎样安全进入 APK 内
已经存在的 AT / ADB Engine？"（process boundary / engine ownership / identity
ownership / connection ownership / IPC / local socket / Binder / executable bridge）

本轮一项 CLI 都未实现。
```