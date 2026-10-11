# AT.V3-2 — ADB Backend Vertical Slice

> 本轮任务：验证已独立的 ADB Engine 能否通过薄 ADB Backend Adapter，作为 AT Engine 的第二个 Execution Provider，在**不改 AT Engine / Contract** 的前提下提供 one-shot 与 persistent remote PTY。
>
> 最终判定：**AT.V3-2 = PARTIAL**
>
> **GATE A（one-shot）= PASS（真机）**
> **GATE B（persistent remote PTY）= PASS（真机，单会话）**
> **GATE C（multi-session 并发）= BLOCKED_BY_ADB_ENGINE_SINGLE_CONNECTION_READ_OWNERSHIP（真实传输限制）**
> **AT_ENGINE_MODIFIED = NO / AT_CONTRACT_CHANGED = NO**

---

## 1. Objective

- 用临时 ADB Terminal consumer（headless instrumentation）验证：
  ```text
  consumer → AT Engine → ADB Backend → ADB Engine → Wireless ADB → adbd → uid=2000(shell)
  ```
- 与已验证 Local 链路对照，证明 AT Engine = backend-independent execution abstraction。
- **不**重新开发 ADB / 不建立正式 ADB Terminal 产品 / 不实现 Human adb CLI。

## 2. Temporary ADBTerminal Dev role

- 本轮采用 **headless instrumentation**（`:test-host` → `AdbBackendHeadlessTest`）作为临时验证 consumer。
- 无 UI APK（headless-only；符合用户"先测无头版本"指示）。
- 定位：`TEMPORARY_VERIFICATION_CONSUMER`（非正式产品）。

## 3. Dependency Graph

```text
AdbBackendHeadlessTest (consumer, headless)
        ↓
AT Engine (ATEngine / ATOneShotBackend / ATPersistentBackend / ATSession*)
        ↓
ADB Backend (:at-backend-adb → AdbOneShotBackend / AdbPersistentBackend / AdbTerminalSession)
        ↓
ADB Engine (:adb-engine → AdbEngine / AdbInteractiveStream)
        ↓
Wireless ADB → adbd → uid=2000(shell)
```

- **consumer 不直接调用 ADB Engine**（经 AT Engine + ADB Backend）。`APP_DIRECT_ADB_ENGINE_DEPENDENCY = NO`。

## 4. ADB Backend Implementation

- `AdbOneShotBackend`（ATOneShotBackend）：`ATExecutionRequest.command → AdbEngine.executeShell` → 映射 `AdbShellResult → ATExecutionResult`（非零 exit = COMPLETED；transport 失败 = TRANSPORT_ERROR）。
- `AdbPersistentBackend`（ATPersistentBackend）：`openSession → AdbEngine.openInteractive("shell,v2,pty:")` → 包成 `AdbTerminalSession`（ATTerminalSession）。
- `AdbTerminalSession`：单一输出消费者（reader 线程 → ATSessionEvent.Data/EOF）；`read==-1 → EXITED`（Kotlin ADB facade 无 exit 帧暴露）；close → CLSE。
- 不实现 ADB 协议（全在 :adb-engine）。

## 5. Identity / Endpoint Bootstrap

- **explicit endpoint**：`127.0.0.1:38793`（adbd 无线 connect 端口，uid 2000 监听 `*:9789`）。
- **trusted identity**：PC adbkey（`Wing@DESKTOP-PG78M2N`，设备已信任）注入 test-host `files/`。
- 未提交私钥 / 未打包 key / 未在文档保存敏感 key。
- 设备无线调试需手动开启（`adb_wifi_enabled=1`）。

## 6. Gate A — One-shot（真机 PASS）

| Test | 验证 | 结果 |
|---|---|---|
| a1_echo | `echo hello` → stdout contains hello, exit 0 | **PASS** |
| a2_principal | `id` → uid=2000(shell) | **PASS** |
| a3_pwd | remote shell pwd | **PASS** |
| a4_stderrNonZero | 不存在命令 → exit 127, stderr 有错误, COMPLETED | **PASS** |
| a5_connectionReuse | 3 次 one-shot 复用同一连接 | **PASS** |

**Transport evidence（a5 logcat）**：
```text
state=Online tls=1  OPEN(local_id=1..3) → OKAY → WRTE → CLSE（3 条流）
TLS handshake count = 1（无重连/重 CNXN）
```

## 7. Gate B — Persistent Remote PTY（真机 PASS）

| Test | 验证 | 结果 |
|---|---|---|
| b1_openActive | openSession → ACTIVE | **PASS** |
| b2_writeOutput | `echo ADB_PERSIST_OK` → 输出 | **PASS** |
| b3_cwdContinuity | cd /data/local/tmp → pwd 保持 | **PASS** |
| b4_envContinuity | export → echo 保持 | **PASS** |
| b5_stdin | cat + ADB_STDIN_TEST | **PASS** |
| b6_ctrlC | sleep 30 + 0x03 → shell ACTIVE | **PASS** |
| b7_ttyAndResize | tty → /dev/pts/* ; resize 40x120 → stty size | **PASS** |
| b8_exitCloseReopen | exit / close / reopen | **PASS** |

**Remote PTY 实证**：`tty → /dev/pts/*`（真实 remote PTY，非 repeated one-shot）；resize 40x120 生效。

## 8. Gate C — Multi-session（BLOCKED）

- **真实行为**：第一个 interactive session 打开后，其 reader 线程占用共享 AdbConnection 读；**第二个并发 open FAILED（"ADB interactive open failed"）**，且后续导致进程崩溃（BoringSSL SIGABRT in SSL_read）。
- **根因**（ADB Engine 传输限制，非 AT Contract）：
  - `AdbInteractiveStream::read` 为**阻塞无超时**（native 注释明确："readPacket blocks on TLS; callers use bounded windows"）。
  - 单 AdbConnection 一次只允许一个活跃 reader（reader 线程持有连接读）。
  - 多 session 各自 reader 并发 SSL_read 同一连接 → 数据竞争 → 崩溃。
- **Local 对照**：每 session 独立 PTY，天然并发安全 → 无此限制（DynamicSessionPages 9/9 PASS 证明）。
- **结论**：`MULTI_SESSION = BLOCKED_BY_ADB_ENGINE_SINGLE_CONNECTION_READ_OWNERSHIP`。这属于 ADB Engine 演进，不属 AT Contract 缺陷。

## 9. Transport Reuse / Multiplexing 审计

| 项 | 值（真机证据） |
|---|---|
| physical transport count | 1（单 TCP/TLS/ADB connection） |
| TLS handshake count | 1（one-shot 3 命令复用；persistent 单会话 1 次） |
| ADB stream count | one-shot = 每命令 1 条（3 条）；persistent = 1 条 |
| close 一个 stream 是否影响其他 | 单会话场景不受影响；多会话并发未验证（Gate C blocked） |
| connection multiplexing | 串行多 stream（one-shot）VERIFIED；并发多 stream **NOT_VERIFIED**（限制见 §8） |

## 10. Local vs ADB Parity Matrix

| Capability | Local | ADB |
|---|---|---|
| one-shot | PASS | **PASS** |
| stdout | PASS | PASS |
| stderr | PASS | PASS |
| exitCode | PASS | PASS |
| persistent | PASS | **PASS** |
| stdin | PASS | PASS |
| cwd continuity | PASS | PASS |
| env continuity | PASS | PASS |
| Ctrl-C | PASS | PASS |
| resize | PASS | PASS |
| natural exit | PASS | PASS |
| close | PASS | PASS |
| reopen | PASS | PASS |
| multi-session | PASS（9/9） | **BLOCKED**（ADB Engine 单连接限制） |
| isolation | PASS | 未验证（Gate C blocked） |

## 11. Principal / Capability Differences

- **ADB principal**：`uid=2000(shell)` / `u:r:shell:s0`（a2 实测）。
- **Local principal**：`uid=<app>` / `u:r:untrusted_app`（ATsh 实证）。
- `LOCAL_ADB_CAPABILITY_DIFFERENCE = DOCUMENTED`：同一 AT Contract，不同 execution principal/capability 面。这是架构证据（AT 抽象背靠不同 provider），非 Contract bug。

## 12. AT Contract Impact

- **AT_ENGINE_MODIFIED = NO / AT_CONTRACT_CHANGED = NO**。
- ADB Backend 在现有 Contract（ATExecutionRequest/Result/Outcome + ATPersistentBackend/ATTerminalSession）下可实现 one-shot + persistent 单会话。
- multi-session 阻塞源于 ADB Engine 传输层，不是 AT Contract 不足（Local 侧已验证 Contract 支持多 session）。

## 13. Remaining ADB Runtime Gaps

```text
并发多 interactive stream（单连接多 reader）— ADB Engine 传输限制（本轮 BLOCKED）
interactive read 无超时（reader 线程无法在 close 时及时退出）
pairing / discovery / recovery / roaming —— OUT_OF_SCOPE（本轮未触碰）
Human adb CLI —— OUT_OF_SCOPE
```

## 14. Regression

| 测试 | 结果 |
|---|---|
| LocalPersistentSliceTest | **11/11 PASS** |
| AppDebugInputTest | **7/7 PASS** |
| DynamicSessionPagesTest | **9/9 PASS** |
| at-engine unit tests | **6/6 PASS** |
| AdbBackendHeadlessTest（Gate A+B，c1 ignored） | a1–a5, b1–b8 各单独 PASS |

`:adb-engine` 仅做 1 个最小 bug 修复（keyDir 变化时重建 client），未重构协议/TLS/CNXN。

## 15. Final Verdict

```text
AT.V3-2 = PARTIAL

ADB_TERMINAL_DEV_ROLE = TEMPORARY_VERIFICATION_CONSUMER（headless instrumentation）
ADB_BACKEND_ROLE = EXECUTION_PROVIDER
ANDROID_TERMINAL_DEFAULT_BACKEND = LOCAL
HUMAN_ADB_UX = OUT_OF_SCOPE

DEPENDENCY_PATH = ADBTerminal Dev → AT Engine → ADB Backend → ADB Engine
APP_DIRECT_ADB_ENGINE_DEPENDENCY = NO

EXPLICIT_ENDPOINT = PASS
TRUSTED_IDENTITY = PASS

ONESHOT = PASS
ONESHOT_ECHO = PASS
ONESHOT_ID = PASS（uid=2000(shell)）
ONESHOT_PWD = PASS
STDOUT_MAPPING = PASS
STDERR_MAPPING = PASS
EXIT_CODE_MAPPING = PASS
NONZERO_IS_PROCESS_RESULT = PASS
CONNECTION_REUSE = PASS（1 TLS / 3 streams）

PERSISTENT_OPEN = PASS
PERSISTENT_WRITE = PASS
REMOTE_PTY = PASS
TTY_VERIFIED = PASS（/dev/pts/*）
CWD_CONTINUITY = PASS
ENV_CONTINUITY = PASS
INTERACTIVE_STDIN = PASS
CTRL_C = PASS
RESIZE = PASS
NATURAL_EXIT = PASS
EXPLICIT_CLOSE = PASS
REOPEN = PASS

MULTI_SESSION = BLOCKED
CWD_ENV_ISOLATION = NOT_VERIFIED（Gate C blocked）
STDIN_ISOLATION = NOT_VERIFIED
CTRL_C_ISOLATION = NOT_VERIFIED
RESIZE_ISOLATION = NOT_VERIFIED
CLOSE_ISOLATION = NOT_VERIFIED

ADB_TRANSPORT_MODEL = single connection / serial streams
TLS_HANDSHAKE_COUNT = 1（真机）
ADB_STREAM_COUNT = 3（one-shot）+ 1（persistent）
CONNECTION_MULTIPLEXING = serial VERIFIED / concurrent NOT_VERIFIED

ADB_PRINCIPAL = uid=2000(shell)
LOCAL_PRINCIPAL = app uid / untrusted_app
LOCAL_ADB_CAPABILITY_DIFFERENCE = DOCUMENTED

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_DEBUG_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
DYNAMIC_SESSION_REGRESSION = 9/9

AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
ADB_ENGINE_MODIFIED = YES（仅 1 个最小 keyDir bug 修复，无协议/传输重构）
LOCAL_BACKEND_MODIFIED = NO

PAIRING_PARITY = OUT_OF_SCOPE
DISCOVERY_PARITY = OUT_OF_SCOPE
RECOVERY_PARITY = OUT_OF_SCOPE

ANDROID_TERMINAL_DEV_INSTALLED = YES（com.androidterminal.app）
ADB_TERMINAL_DEV_INSTALLED = NO（本轮 headless-only，无独立 UI APK）

REAL_DEVICE_VALIDATION = PASS（Gate A+B）
EVIDENCE = 见 docs/evidence/at_v3_2/

GIT_COMMIT = （见 commit）
GIT_WORKTREE = CLEAN

NEXT = AT BACKEND PARITY / CLOSURE AUDIT（或先解决 ADB Engine 并发读）
```

### 架构问题回答

**A. ADBTerminal Dev 是否完全经过 AT Engine → ADB Backend → ADB Engine？** 是。consumer 只经 AT 接口；ADB Backend 是唯一 ADB 接触点。

**B. 为了支持 ADB，AT Engine 是否需要修改？** 不需要。`AT_ENGINE_MODIFIED = NO`。

**C. Local 与 ADB 能否实现同一套 AT Terminal Contract？** 是（one-shot + persistent 单会话均 PASS）。

**D. ADB persistent 是否真正 remote PTY？** 是。`tty → /dev/pts/*` + resize 生效，非 repeated one-shot。

**E. 多个 ADB TerminalSession 能否同时真实存在？** 当前**不能**（单连接单 reader 限制，第二个 open 失败并崩溃）。这是 ADB Engine 传输限制。

**F. close 一个 Session 是否影响其他/transport？** 单会话 close 正常；多会话未达验证条件。

**G. AG 后续能否只消费 AT TerminalSession 而不需要知道底层？** 是（本 bundle 已验证 AT 抽象隔离 ADB 细节）。

**H. 是否仅验证 Backend abstraction 未升格正式产品？** 是。ADB Terminal 仅 headless 验证 consumer，非正式产品；AndroidTerminal 默认仍 Local。

**结论**：AT.V3-2 PARTIAL。ADB Backend 作为 AT Engine 第二 Execution Provider 的 **one-shot + persistent 单会话** 链路在真机验证通过（remote PTY、shell principal、cwd/env/stdin/Ctrl-C/resize/exit/close/reopen 全 PASS）；**multi-session 并发受 ADB Engine 单连接 read 限制阻塞**（真实传输层发现，非 AT Contract 问题）。AT Engine / Contract 未改。Local 全部回归不退化。

本轮未开始 `AT.CONTRACT-1` / `AT.EMULATOR-0` / ADB runtime closure / Pairing/Discovery/Recovery / AG integration / SSH / Human adb CLI。任务结束，STOP。