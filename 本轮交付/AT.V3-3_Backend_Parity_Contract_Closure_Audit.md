# AT.V3-3 — Backend Parity & Contract Closure Audit

**后端一致性与契约收口审计（Local / ADB Backend Parity + AT Execution Contract Readiness）**

- 日期：2026-10-11
- 基线：V3 `a6113f4`（AT.V3-2.1 完成后 clean）
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
- 文件：`docs/implementation/AT.V3-3_Backend_Parity_Contract_Closure_Audit.md`
- 结论：**STATUS = COMPLETE（审计完成）**；
  **AT_EXECUTION_CLOSURE = BLOCKED_BY_ADB_ENGINE_BUG**（ADB one-shot 大输出 flow-control，见 §7）；
  **AT_CONTRACT_FREEZE_READINESS = BLOCKED_BY_BACKEND_BUG**（最小修复后预期转 READY_FOR_EMULATOR_VALIDATION）。

---

## 1. Objective

在 Local 与 ADB 两个真实 Execution Provider 都已完成 one-shot + persistent + multi-session 验证之后，
不再扩功能，而是通过统一真机测试、行为矩阵、失败分类和边界审计，回答：

1. AT Contract 对 Local + ADB 的 execution semantics 是否已稳定、backend-independent；
2. 现存差异各属于 AT Contract / Backend implementation / ADB Runtime / Emulator 阶段哪一类。

本轮为 Audit：不改 AT Contract、不改公共 Backend API、不做 Emulator/Recovery/UI。
新增内容仅限：audit tests、evidence、文档（+ 边界探针）。

## 2. Baseline

```text
V3 HEAD = a6113f4（worktree clean）
V2 HEAD = 726a441
V2_PREEXISTING_DIRTY = 43 entries（mtime 均 < 2026-10-11；AT.V3-2.1 已核验，本轮零写入）
device = APLMUT4312005935 / ALI-AN00 / Android 15
endpoint 起点 = 127.0.0.1:38793；transport-loss 测试后 = 127.0.0.1:39731（有意 toggle，已恢复）
```

## 3. AT Public Surface Inventory（AT_PUBLIC_SURFACE_AUDIT）

`AT_BACKEND_PUBLIC_API = 5 files / 12 public types`（`at-engine/src/main/java/com/at/engine/`）：

| Public type | 成员 / 语义 | 模式 | Backend 参与 | 泄漏检查 |
|---|---|---|---|---|
| `ATEngine` | `execute(request)`, `openSession(launch)`, `empty()` | facade（两模式 peer） | 注入式（composition） | 无 Local/ADB/UI/AG 类型 |
| `ATOneShotBackend` | `execute(request): ATExecutionResult` | one-shot capability | provider 实现 | 无 |
| `ATExecutionRequest` | `command`, `timeoutMs`（0=不限；是否支持=backend capability） | input | — | 无 |
| `ATExecutionOutcome` | `COMPLETED` / `TRANSPORT_ERROR` / `UNSUPPORTED` | enum | — | 无 |
| `ATExecutionResult` | `outcome`, `stdout`, `stderr`, `exitCode`, `hasError`, `error` | output | — | 三通道分离（进程输出 vs 执行层诊断） |
| `ATPersistentBackend` | `openSession(launch): ATOpenSessionResult`；`Launch(command, initialRows, initialCols, env)` | persistent capability | provider 实现 | 无；`env` 标注 DEFERRED |
| `ATOpenSessionResult` | `Success(session)` / `Failure(reason)` | output | — | 无 |
| `ATTerminalSession` | `state`, `write(ByteArray):Int`, `read(Int):ByteArray?`, `setListener((ATSessionEvent)->Unit)`, `resize(Int,Int):Boolean`, `close(Boolean)` | session interface | provider 实现 | 无 Emulator/ScreenState 概念 |
| `ATSessionState` | `OPENING / ACTIVE / EXITED / FAILED / CLOSED` | lifecycle | — | 无 UI 语义 |
| `ATSessionEvent` | `Data / Resized / Exited(exitCode) / Eof / Failed(error) / Closed` | event | — | 无 |
| `AtEngineMarker` / `LocalBackendMarker` / `AdbBackendMarker` | 构建占位 | — | — | 无 |

审计回答：

- one-shot API = `ATEngine.execute` + `ATOneShotBackend`；
- persistent API = `ATEngine.openSession` + `ATPersistentBackend` + `ATTerminalSession`；
- backend-neutral：全部 public 类型均为 AT 域值/接口，无 provider 类型；
- **无 Local 类型泄漏 / 无 ADB 类型泄漏 / 无 UI-Page 泄漏 / 无 AG 泄漏**（编译期即可验证：`:at-engine` 不依赖任何 backend 模块，见 §4）；
- unused / historical surface：`ATSessionState.OPENING` 当前两 provider 都在 `openSession` 返回前完成启动（未观察到 OPENING 外露）、`ATSessionEvent.Failed` 与 `ATExecutionOutcome.UNSUPPORTED` 目前无 provider 产生（如表所述为 contract 预留，见 §9/§17 GAP）；
- 同一语义双表达：无（`read()` 与 `setListener()` 是同一 stream 的两种消费模式，由 C1 不变式约束，见 §13）。

```text
AT_PUBLIC_SURFACE_AUDIT = PASS（12 types inventoried；无 provider/UI/AG 泄漏；2 个未触达枚举值记录为 GAP 证据）
```

## 4. Dependency Audit（AT_ENGINE_DEPENDENCY_INDEPENDENCE）

gradle 真实依赖：

```text
:at-engine          → 无 project 依赖（仅 stdlib）
:at-backend-local   → :at-engine（+ 自有 native PTY）
:at-backend-adb     → :at-engine + :adb-engine
:adb-engine         → 无 project 依赖（独立 provider）
:app                → :at-engine + :at-backend-local（+ :adb-engine? 见下）
:test-host          → :at-engine + :at-backend-local + :at-backend-adb（androidTest 亦含 :adb-engine）
```

- `:at-engine` 不依赖 `:adb-engine` / 任一 backend / `:app` / `:test-host` / AG ✅
- 不存在 production consumer 绕过 AT 直接进入 Backend/ADB Engine：`:app` 的 session 路径全部经 AT 域（SessionPageController → ATTerminalSession）；唯一直接持有 `:adb-engine` 的是 `:at-backend-adb`（adapter 职责）与 test-host（测试）。
- `:app` 是否声明 `:adb-engine`？无（app 只依赖 local backend；ADB 尚未接产品）。

```text
AT_ENGINE_DEPENDENCY_INDEPENDENCE = PASS
```

## 5. Canonical Backend Parity Matrix

完整四列矩阵（Semantic / AT Contract / Local observed / ADB observed + 差异分类）见
`docs/architecture/AT_Execution_Contract_Parity_Matrix.md`。统计：

```text
PARITY                        = 25 项
EXPECTED_PROVIDER_DIFFERENCE  = 4 项（principal / cwd 根 / PTY 实现 / 连接模型）
ADB_ENGINE_BUG                = 1 项（one-shot 大输出 flow control）
LOCAL_BACKEND_BUG             = 1 项（Launch 初始尺寸被忽略）
AT_CONTRACT_GAP               = 2 项（EOF/FAILED 合并、persistent exitCode 不投递）
UNVERIFIED                    = 0 项
```

## 6. One-shot Semantics（O1–O5，双后端实测）

| Probe | Local | ADB | 语义 |
|---|---|---|---|
| O1 `echo hello` | PASS | PASS | COMPLETED / stdout / stderr="" / exit 0 |
| O2 `printf ERRONLY 1>&2` | PASS | PASS | stdout 空 / stderr=ERRONLY / exit 0 |
| O3 `exit 7` | PASS | PASS | exit 7 → **COMPLETED**（非失败） |
| O4 stdout+stderr+exit 9 | PASS | PASS | OUTO4 / ERRO4 / exit 9 / COMPLETED |
| O5 `true` | PASS | PASS | 空/空/0/COMPLETED |

```text
ONESHOT_SEMANTIC_PARITY = PASS（小输出域）
```

## 7. Large-output Audit（ADB one-shot 专项 → 发现 BLOCKER）

| 档位 | Local | ADB |
|---|---|---|
| 64 KiB（66560B / 1024 行） | PASS 21s | **FAIL：300s 超时** |
| 256 KiB | NOT_RUN（理由见下） | NOT_RUN（同） |
| 1 MiB | NOT_RUN（同） | NOT_RUN（同） |

NOT_RUN 理由：64 KiB 已稳定复现机制性卡死，更大档无新信息；1 MiB 还会引入无意义 5–10 分钟级等待（任务 §10 允许，须记录理由）。

**证据（决定性）**：

```text
one-shot（ack_writes=false）: 收到第 1 个 WRTE（70B，即 1 行 65B 输出）后 300 秒无后续；
persistent（ack_writes=true）: 同一设备命令 67931B 全量到达（23s）；
设备端基线（USB 标准 adb shell）: 66560B / 1024 行 / tail 正确；
```

结论：adbd 在首个未被 OKAY credit 的 WRTE 后停止发送；one-shot 路径从不回 OKAY。
（这也解释了历史所有 one-shot 用例为何全绿：均为单 WRTE 小输出。）

```text
ADB_ONESHOT_FLOW_CONTROL_BUG = CONFIRMED（ADB_ENGINE_BUG，BLOCKER）
MINIMAL_FIX（NEXT，本轮不实施）= one-shot 改为逐 WRTE 回 OKAY（ack_writes=true）或等价初始 credit；
VALIDATION_PLAN = l2/l3/l4 通过 + a1–a5 回归 + 整类运行。
```

## 8. Persistent Semantics（p1–p8，双后端实测）

全部 PASS：open→ACTIVE、write→Data、cwd/env continuity、stdin(cat)、Ctrl-C、
resize、natural exit、close、reopen、多会话隔离（§11）。
p8 边界探针发现（LOCAL_BACKEND_BUG）：`Launch(initialRows=33, initialCols=99)` 下
Local 实际 `stty size = 0 0`（未应用），ADB `= 33 99`（应用）。Local 显式 `resize()` 正常（P5）。

```text
PERSISTENT_SEMANTIC_PARITY = PASS（显式操作域；唯一差异 = p8 初始尺寸，分类 LOCAL_BACKEND_BUG）
```

## 9. EOF / State Semantics（重点审计）

场景 → 双后端观测：

| 场景 | read() | state before→after | 事件序列 | 错误信息 |
|---|---|---|---|---|
| natural exit（`exit`） | null | ACTIVE→EXITED | Eof | 无 |
| explicit close | null | ACTIVE→CLOSED | Closed | 无 |
| remote CLSE（ADB） | null | ACTIVE→EXITED（经 Eof 路径） | Eof | 无 |
| transport loss（ADB 实测） | null | ACTIVE→EXITED | **Eof（非 Failed）** | **无** |
| backend failure（Local read 错误） | null | ACTIVE→EXITED（同路径） | Eof | 无 |

回答 §12 的问题：**AT consumer 只看 AT state/event 无法区分“正常结束”与“失败”**——
两者都折叠为 `EXITED + Eof`，`Failed` 事件与 `FAILED` 状态在实际 provider 中不可达
（两后端行为一致，故不是 parity failure，而是 Contract Gap）。

```text
EOF_STATE_SEMANTICS = GAP（AT_CONTRACT_GAP-1：EOF vs FAILED 折叠；两后端一致，本轮只记录不改）
```

## 10. Explicit Close Semantics

双后端一致（e2 PASS）：close → `CLOSED` + `Closed` 事件；bounded（实测 < 2.5s，ADB 1ms 级）；
read→null；write→-1；resize→false；重复 close 幂等；listener reader 退出（b9/b10 历史证据）。

```text
EXPLICIT_CLOSE_SEMANTICS = PASS
```

## 11. Multi-session Parity

m1/m2/m3（双后端）：3 会话同 ACTIVE；cwd/env/stdin/Ctrl-C/resize/close/natural-exit 全隔离；
A/C 存活时重开 D。ADB 侧附 transport 证据（1 conn / 1 TLS / N streams，见 §12）。
Local 侧为独立 PTY（provider 实现差异，语义一致）。

```text
MULTI_SESSION_PARITY = PASS
```

## 12. Cross-mode Audit

x1（Local）/ x2（ADB）PASS：persistent A ACTIVE 时执行 one-shot →
A 保持 ACTIVE 并继续工作；ADB logcat 证明 `CONNECT_START=1 / TLS_START=1`（同一条物理连接上
one-shot 新开 logical stream，无串扰）。

```text
CROSS_MODE_ONESHOT_PERSISTENT = PASS（两模式为 peer capability，非互斥）
```

## 13. read() / listener Consumption Audit

- Contract 表述：`read()` 与 `setListener()` 为**同一 stream 的互斥消费方式**（C1 不变式，AT.PERSIST-0）。
- 运行期 guard：两后端在 listener 已设置时 `read()` 返回 null（r1 实测），pull 模式无 listener 时
  `read()` 超时返回空数组（r2 实测）；**无 runtime 强制禁止“先 read 后 setListener”或双重 listener**：
  属 `CONTRACT_ONLY_INVARIANT`。
- Consumer 检查：App（SessionPageController）仅 listener；AppDebugInput/DynamicSessionPages 走 UI 写入口；
  TestHost headless 仅 listener；LocalPersistentSliceTest 仅 pull read；**无任何 consumer 同时 read+listener**，
  未发现 double-consume 路径。

```text
READ_LISTENER_CONTRACT = PASS（行为 guard 存在；跨模式混合使用 = CONTRACT_ONLY_INVARIANT，记录不改）
```

## 14. Byte-stream Input Audit

- `ATTerminalSession.write(bytes: ByteArray)` 为真正 byte-stream；无 command-string API（编译期保证）。
- 实测（b1，双后端）：`write("echo ...\n")` 返回字节数；`write(byteArrayOf(0x03))` 返回 1 且
  Ctrl-C 生效（p4/m2）；Human debug 输入与 headless 输入均经同一 `write` 路径（App/Headless 测试共用 AT 域）。

```text
BYTE_STREAM_INPUT_CONTRACT = PASS
```

## 15. Principal / Capability Differences

实测（c1）：

```text
LOCAL: uid=10335(u0_a335) ... context=u:r:untrusted_app:s0:c79,...
ADB  : uid=2000(shell) ... context=u:r:shell:s0
```

- 分类：**EXPECTED_PROVIDER_DIFFERENCE**（Canonical/provider 事实，非 parity failure）。
- 相关：Local 不能执行部分 /system 二进制（历史 ATsh 记录）/ 不能进入 /data/local/tmp（测试用 filesDir 规避）。
- 是否需要 Contract 暴露 principal/capability metadata？当前 Consumer（Human/AG 前瞻）在不知 Backend 的情况下
  无法预判权限——但没有任何现有 consumer 依赖“同一命令在两 provider 下行为相同”。
  **NO_CONTRACT_CHANGE_REQUIRED（现阶段）**；记录为未来 capability-metadata 提案候选（不实现）。

## 16. ADB Transport-loss State Audit（真实断链）

方法：listener 激活中 host 执行 `settings put global adb_wifi_enabled 0`（随后恢复 `=1`；
USB 控制链不受影响；identity/Dev App 未受影响；端口 38793→39731，显式 connect 新端口 PASS）。

```text
after_loss : state=EXITED eof=true failed=null connOnline=true
WRITE_AFTER_LOSS=-1
CONN_STATE_AFTER_LOSS=ONLINE
NEW_EXEC_AFTER_LOSS = TRANSPORT_ERROR（5.0s 有界拒绝，error=OPEN timeout waiting for OKAY）
```

回答三问：

- A：**是**——session failure 在有界时间内正确反馈（EXITED + Eof + write -1）；新执行不会被静默接受（有界 TRANSPORT_ERROR）。
- B：stale `AdbEngine.connectionState()==Online` 目前只是 **ADB runtime diagnostic/state projection** 语义，未导致 AT contract semantic error（没有“假装成功”的结果）。
- C：**不阻塞 execution contract closure**（执行不被错误接受、session 状态正确终态）。
  但注意：失败以 Eof 呈现属 §9 的 Contract Gap（与 stale projection 相互独立）。

```text
ADB_TRANSPORT_LOSS_SESSION_SIGNAL = PASS
ADB_TRANSPORT_STATE_CLASSIFICATION = ADB_RUNTIME_FOLLOWUP（NON-BLOCKING）
```

## 17. Failure Taxonomy

| 类别 | Local 观测 | ADB 观测 | AT 类型/状态/事件 | 分类 |
|---|---|---|---|---|
| PROCESS_NONZERO | exitCode=N, COMPLETED | exitCode=N, COMPLETED | `ATExecutionResult(COMPLETED, exitCode=N)` | AT_CONTRACT_OK |
| SESSION_NORMAL_EXIT | EXITED + Eof | EXITED + Eof | 一致 | AT_CONTRACT_OK |
| EXPLICIT_CLOSE | CLOSED + Closed | CLOSED + Closed | 一致 | AT_CONTRACT_OK |
| BACKEND_OPEN_FAILURE | `Failure(reason)` | `Failure(reason)` | `ATOpenSessionResult.Failure` | AT_CONTRACT_OK |
| BACKEND_IO_FAILURE | EXITED + Eof | EXITED + Eof | 无 FAILED 传播 | **AT_CONTRACT_GAP-1** |
| TRANSPORT_FAILURE | （无独立传输层） | EXITED + Eof；新执行 TRANSPORT_ERROR | 部分 | **AT_CONTRACT_GAP-1** + ADB_RUNTIME_FOLLOWUP |
| PROTOCOL_FAILURE | N/A | 映射 TRANSPORT_ERROR（AdbBackend） | 无独立 outcome | AT_CONTRACT_GAP（记录，低优先） |
| INVALID_REQUEST | UNSUPPORTED（无 backend wired） | 同（facade 层） | `ATExecutionOutcome.UNSUPPORTED` | AT_CONTRACT_OK |

```text
FAILURE_TAXONOMY = COMPLETE（8 类，4 个落点分类见上；consumer 无需知道 backend 即可处理“执行结果”，
但“失败 vs 自然结束”的持久会话区分仍为 GAP-1）
```

## 18. AG Consumer Readiness

从 Contract 角度（不开发 AG）：`TerminalTool → Execution Adapter → AT Engine` 已可实现：
open terminal（`openSession`）、write bytes、observe output（listener/pull）、resize、close、
multiple handles（multi-session parity PASS）、one-shot（`execute`；
**注意 ADB 大输出 blocker 修复前避免经 ADB 跑大输出 one-shot**）。
AG 无需知道 Local PTY / system/bin/sh / ADB / TLS / CNXN / stream id（§3 surface 检查）。

```text
AG_TERMINALTOOL_EXECUTION_READINESS = READY_WITH_KNOWN_LIMITS
KNOWN_LIMITS = 1) ADB one-shot 大输出 blocker；2) 失败原因区分（GAP-1）；3) persistent exitCode 不投递（GAP-2）
```

## 19. Human Consumer Boundary

`:app`（MainActivity / SessionPageController / AppTerminal）只消费 AT 抽象；
Page/active page/tab 概念全部留在 `:app`（AT 域无 page/tab/UI 类型，§3 编译期验证）。

```text
HUMAN_CONSUMER_BOUNDARY = PASS
```

## 20. Deferred Capability Boundary

明确排除在 AT execution closure 之外（本轮零开发）：

```text
ADB Pairing            = ADB_RUNTIME_FOLLOWUP
ADB Discovery (mDNS)   = ADB_RUNTIME_FOLLOWUP
ADB Recovery           = ADB_RUNTIME_FOLLOWUP（loss 观察本轮已审，recovery 未实现）
Wireless debugging readiness = ADB_RUNTIME_FOLLOWUP
Human adb CLI          = OUT_OF_SCOPE
SSH                    = OUT_OF_SCOPE
AG integration         = OUT_OF_SCOPE
```

## 21. Emulator / ScreenState Boundary

本轮闭环（若 flow-control 修复后）为 **Terminal Execution Contract**；
**Terminal Presentation / Emulator / ScreenState 尚未实现**（无 emulator、无屏幕状态、无 full-screen TUI 语义）。
因此即使审计其余全 PASS，也不执行 AT.CONTRACT-1 Freeze；Canonical 顺序保持：
execution closure → AT.EMULATOR-0 → 真实 emulator 交互反馈 → AT.CONTRACT-1。

```text
EMULATOR_SCREENSTATE = NOT_IMPLEMENTED / FOLLOWUP
```

## 22. Regression

| 套件 | 结果 |
|---|---|
| ADB_BACKEND (AdbBackendHeadlessTest) | OK (25 tests) |
| LOCAL (LocalPersistentSliceTest) | OK (11 tests) |
| AT_ENGINE unit | 6/6 |
| APP_DEBUG (AppDebugInputTest) | OK (7 tests) |
| HEADLESS (TestHostHeadlessInputTest) | OK (1 test) |
| DYNAMIC_SESSION (DynamicSessionPagesTest) | OK (9 tests) |
| PARITY_AUDIT_TESTS | 26 PASS / 1 FAIL(l2，即被测 bug) / 2 NOT_RUN(l3/l4) + t1 PASS |

无“为了 parity 修改实现”：本轮 V3 代码零修改（仅新增测试与文档）。

## 23. Closure Classification

```text
A. AT_CONTRACT_OK
   - one-shot 结果三通道语义（含 non-zero ≠ failure）：双后端 PASS
   - openSession/write/resize/close/multi-session/cross-mode/byte-stream/read-listener 行为 guard

B. AT_CONTRACT_GAP
   - GAP-1：EOF vs FAILED 折叠（transport/backend 失败 = EXITED+Eof；FAILED/Failed 不可达）
   - GAP-2：persistent 会话 exitCode 不投递（Exited 事件两后端均不产生；已文档化 DEFERRED）
   - GAP-3：read/listener 跨模式混合 = CONTRACT_ONLY_INVARIANT（无 runtime 强制）

C. LOCAL_BACKEND_BUG
   - LOCAL-P8：LocalPersistentBackend 忽略 Launch.initialRows/Cols（实测 0 0）
     影响面低（显式 resize() 可用）；最小修复 = openSession 时应用 Launch 尺寸

D. ADB_BACKEND_BUG
   - 无（:at-backend-adb adapter 本身无缺陷发现）

E. ADB_ENGINE_BUG
   - E-1（BLOCKER）：one-shot WRTE/OKAY flow-control（64KiB 卡死；差分证明）
   - E-2（非阻塞）：registry 极端 no-CLSE 场景依赖 local close/failAll 注销（确定性、有界；正常 lifecycle 清零）

F. ADB_RUNTIME_FOLLOWUP
   - connectionState stale Online after loss（非阻塞）
   - pairing / discovery / recovery / readiness 未真机

G. EMULATOR_FOLLOWUP
   - Terminal Emulator / ScreenState / 退出码呈现

H. EXPECTED_PROVIDER_DIFFERENCE
   - principal（app uid vs shell uid）/ cwd 可访问目录 / Local PTY vs remote PTY / 连接模型

I. NON_BLOCKING_TECH_DEBT
   - protocol failure 无独立 AT outcome（映射 TRANSPORT_ERROR）
   - timeoutMs 未被任一 backend 实现（Contract 已声明为 capability，非承诺）
```

## 24. Final Verdict

```text
AT.V3-3 FINAL VERDICT

STATUS = COMPLETE（审计完成）

AT_PUBLIC_SURFACE_AUDIT = PASS
AT_ENGINE_DEPENDENCY_INDEPENDENCE = PASS

LOCAL_ONESHOT = PASS
ADB_ONESHOT = PASS（小输出域）
ONESHOT_SEMANTIC_PARITY = PASS（小输出域）

ADB_LARGE_OUTPUT_64K = FAIL
ADB_LARGE_OUTPUT_256K = NOT_RUN（64K 已确认机制，记录理由）
ADB_LARGE_OUTPUT_1M = NOT_RUN（同上）
ADB_ONESHOT_FLOW_CONTROL_BUG = YES（CONFIRMED；差分证明：persistent 路径 67931B 全量通过）

LOCAL_PERSISTENT = PASS
ADB_PERSISTENT = PASS
PERSISTENT_SEMANTIC_PARITY = PASS（1 项 LOCAL_BACKEND_BUG：Launch 初始尺寸）

EOF_STATE_SEMANTICS = GAP（GAP-1：EOF/FAILED 折叠，双后端一致）
EXPLICIT_CLOSE_SEMANTICS = PASS
NATURAL_EXIT_SEMANTICS = PASS

MULTI_SESSION_PARITY = PASS
CROSS_MODE_ONESHOT_PERSISTENT = PASS

READ_LISTENER_CONTRACT = PASS（跨模式混合 = CONTRACT_ONLY_INVARIANT）
BYTE_STREAM_INPUT_CONTRACT = PASS

PRINCIPAL_DIFFERENCE = EXPECTED_PROVIDER_DIFFERENCE

ADB_TRANSPORT_LOSS_SESSION_SIGNAL = PASS
ADB_CONNECTION_STATE_AFTER_LOSS = ONLINE（stale）
ADB_TRANSPORT_STATE_CLASSIFICATION = ADB_RUNTIME_FOLLOWUP（NON_BLOCKING）

FAILURE_TAXONOMY = COMPLETE（GAP-1 标注）

AG_TERMINALTOOL_EXECUTION_READINESS = READY_WITH_KNOWN_LIMITS
HUMAN_CONSUMER_BOUNDARY = PASS

PAIRING = ADB_RUNTIME_FOLLOWUP
DISCOVERY = ADB_RUNTIME_FOLLOWUP
RECOVERY = ADB_RUNTIME_FOLLOWUP
HUMAN_ADB_CLI = OUT_OF_SCOPE

EMULATOR_SCREENSTATE = NOT_IMPLEMENTED / FOLLOWUP

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_DEBUG_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
DYNAMIC_SESSION_REGRESSION = 9/9
ADB_BACKEND_REGRESSION = 25/25
PARITY_AUDIT_TESTS = 26 PASS / 1 FAIL(l2=被测 bug) / 2 NOT_RUN(l3,l4)；t1 loss PASS

AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
LOCAL_BACKEND_PUBLIC_CONTRACT_CHANGED = NO
ADB_BACKEND_PUBLIC_CONTRACT_CHANGED = NO

AT_CONTRACT_GAPS = GAP-1（EOF/FAILED 折叠）、GAP-2（persistent exitCode）、GAP-3（read/listener CONTRACT_ONLY）
BACKEND_BUGS = LOCAL-P8（ Launch 初始尺寸）；ADB adapter 无
ADB_RUNTIME_FOLLOWUPS = stale connectionState；pairing/discovery/recovery/readiness
EMULATOR_FOLLOWUPS = Emulator/ScreenState/exit-code presentation
NON_BLOCKING_TECH_DEBT = protocol-outcome、timeoutMs、registry 极端路径

AT_EXECUTION_CLOSURE = BLOCKED_BY_ADB_ENGINE_BUG
AT_CONTRACT_FREEZE_READINESS = BLOCKED_BY_BACKEND_BUG
  （修复 one-shot flow-control 后预期转为 READY_FOR_EMULATOR_VALIDATION）

REAL_DEVICE_VALIDATION = PASS（审计执行面）
EVIDENCE = docs/evidence/at_v3_3/EVIDENCE.md

GIT_COMMIT = 3453ec9（AT.V3-3 audit commit）
GIT_WORKTREE = CLEAN

NEXT = <blocker 修复阶段：AT.V3-2.2 — ADB one-shot WRTE/OKAY credit fix>
       → 之后 AT.EMULATOR-0
```