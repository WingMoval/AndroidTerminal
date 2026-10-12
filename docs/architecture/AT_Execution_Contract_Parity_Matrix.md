# AT Execution Contract — Backend Parity Matrix

> 状态：**CURRENT ACTUAL（verified by AT.V3-3 audit + AT.V3-2.2 closure real-device tests）** — 本文件不是 Frozen 文档，
> 是后续 **AT.CONTRACT-1** 的直接输入。只记录已实测语义、已知 gap 与 deferred 语义。
>
> 基线：V3 `a6113f4`（AT.V3-3）+ `1311750→AT.V3-2.2`（flow-control closure，2026-10-12，设备 APLMUT4312005935）。
> 证据：`docs/evidence/at_v3_3/EVIDENCE.md`（审计）、`docs/evidence/at_v3_2_2/EVIDENCE.md`（flow-control 闭环）；
> 报告：`docs/implementation/AT.V3-3_Backend_Parity_Contract_Closure_Audit.md`、
> `docs/implementation/AT.V3-2.2_ADB_OneShot_Flow_Control_Closure.md`。

## 0. 图例

```text
PARITY                        Local 与 ADB 观测语义一致（且符合 AT Contract）
EXPECTED_PROVIDER_DIFFERENCE  provider 事实差异，非 Contract 违反
BACKEND_BUG                   provider/adapter 缺陷（含定位）
AT_CONTRACT_GAP               Contract 未表达/未规定，或意图与实际不符
UNVERIFIED                    尚无真机证据
```

## 1. One-shot 语义（ATEngine.execute / ATOneShotBackend）

| Semantic | AT Contract | Local observed | ADB observed | 分类 |
|---|---|---|---|---|
| one-shot success | `COMPLETED`，stdout 保留 | `echo` → COMPLETED, stdout 完整 | 同左 | PARITY |
| stdout/stderr 分离 | 两通道 verbatim | `printf ERRONLY 1>&2` → stdout 空 / stderr ERRONLY | 同左 | PARITY |
| zero exit | exitCode 0 | 0 | 0 | PARITY |
| non-zero exit | **仍为 COMPLETED**，exitCode=N | `exit 7` → COMPLETED/7 | 同左 | PARITY |
| stdout+stderr+non-zero | 三通道同时正确 | OUTO4/ERRO4/9/COMPLETED | 同左 | PARITY |
| no-output success | 空/空/0/COMPLETED | 同左 | 同左 | PARITY |
| infrastructure failure | `TRANSPORT_ERROR` + hasError + error；不可混入 stderr | spawn fail → TRANSPORT_ERROR | connect/OPEN fail → TRANSPORT_ERROR | PARITY |
| connection reuse | 无 Contract 语义（provider 内部） | 无连接概念 | 3 连发 1 conn/1 TLS | EXPECTED_PROVIDER_DIFFERENCE（provider 内部优化，对 AT 不可见） |
| **大输出（>首个 WRTE）** | 结果不因输出量改变 | 64 KiB PASS（66560B/1024 行） | **AT.V3-2.2 修复后：64K 66560B / 256K 266240B / 1M（分块）1048582B 真机完整 PASS** | PARITY（原 ADB_ENGINE_BUG-E1 已闭环） |
| timeoutMs | capability（0=不限；是否支持由 backend 定） | 未实现（无限等待） | 未实现 | PARITY（=均不支持；Contract 已声明为 capability） |
| UNSUPPORTED | 未接线 backend → UNSUPPORTED | facade 行为 | facade 行为 | PARITY |

## 2. Persistent 会话生命周期（ATEngine.openSession / ATPersistentBackend / ATTerminalSession）

| Semantic | AT Contract | Local observed | ADB observed | 分类 |
|---|---|---|---|---|
| openSession | Success(session)/Failure(reason) | PTY 启动失败→Failure | connect/open 失败→Failure | PARITY |
| state after open | ACTIVE（OPENING 内部） | ACTIVE | ACTIVE | PARITY |
| write(bytes) | 返回接受字节 / -1 | 字节数；非 ACTIVE→-1 | 同左 | PARITY |
| output Data | listener 推 Data / pull 读字节 | 两模式实测一致 | 同左 | PARITY |
| resize | true=backend 接受 | true + stty 生效 | 同左 | PARITY |
| Ctrl-C（0x03） | 字节流输入，无专用 API | 前台进程终止、shell 存活 | 同左 | PARITY |
| natural exit | EXITED + （Eof 或 Exited） | EXITED + Eof；read null；write -1 | 同左 | PARITY |
| EOF/end-of-stream | read 语义 null=结束；原因由 state/event 区分 | null；EXITED | null；EXITED | AT_CONTRACT_GAP-1（见 §5） |
| explicit close | CLOSED + Closed；幂等 | bounded；read null/write -1/resize false；重复 close 幂等 | 同左 | PARITY |
| reopen | 新 session 可用 | 同左 | 同左 | PARITY |
| Launch.initialRows/Cols | “Terminal size at open” | **未应用（实测 0 0）** | 应用（33x99 实测） | BACKEND_BUG（LOCAL-P8；workaround：显式 resize()） |
| Launch.env | DEFERRED 多数 provider | 未实现 | 未实现 | PARITY（均 DEFERRED） |
| Launch.command | 启动目标 | 固定 `/system/bin/sh -i`（忽略参数） | 固定 `shell,v2,pty:`（忽略参数） | PARITY（均忽略；记录） |
| exitCode 投递（persistent） | `ATSessionEvent.Exited(exitCode)` | 从不产生 | 从不产生 | AT_CONTRACT_GAP-2 |
| FAILED 状态/Failed 事件 | 传输/backend 失败时传播 | 不可达（折叠为 EXITED+Eof） | 不可达（同左） | AT_CONTRACT_GAP-1 |

## 3. 多会话（multi-session）

| Semantic | AT Contract | Local observed | ADB observed | 分类 |
|---|---|---|---|---|
| 3 会话同 ACTIVE | 无数量限制语义 | PASS | PASS（1 conn/1 TLS/3 streams） | PARITY |
| cwd isolation | 每 session 独立 | PASS（filesDir vs /） | PASS（/data/local/tmp vs /system） | PARITY |
| env isolation | 每 session 独立 | PASS | PASS | PARITY |
| stdin isolation | 每 session 独立 | PASS | PASS | PARITY |
| Ctrl-C isolation | 只影响目标 session | PASS | PASS | PARITY |
| resize isolation | 互不影响 | PASS（40x120 vs 30x100） | PASS | PARITY |
| close isolation | 不关其他/不关 transport | PASS | PASS（transport 仍 ONLINE） | PARITY |
| natural-exit isolation | 其他 session 不受影响 | PASS | PASS | PARITY |
| reopen（他者存活时） | 允许 | PASS | PASS | PARITY |

## 4. 消费模式与输入

| Semantic | AT Contract | Local observed | ADB observed | 分类 |
|---|---|---|---|---|
| listener 模式 | 推 Data/生命周期事件 | PASS | PASS | PARITY |
| pull 模式 | 阻塞读，超时→空数组 | PASS（ByteArray(0)） | PASS | PARITY |
| read/listener 互斥 | “同一 stream 单一消费模式”（C1） | listener 时 read→null（行为 guard） | 同左 | PARITY（跨模式混用=CONTRACT_ONLY_INVARIANT） |
| byte-stream write | write(ByteArray)，无 command API | PASS（0x03 单字节） | PASS | PARITY |

## 5. 已知 Gaps（记录；AT.CONTRACT-1 输入）

| ID | Gap | 现状（双后端一致） | 影响 | 建议方向（不实现） |
|---|---|---|---|---|
| GAP-1 | EOF vs FAILED 折叠 | transport/backend 失败 = `EXITED+Eof`；`FAILED`/`Failed` 不可达 | consumer 无法区分“正常退出”与“失败” | CONTRACT-1 明确：失败必须产生 `Failed`（或 Eof 携带 reason）；两 backend 统一实现 |
| GAP-2 | persistent exitCode 不投递 | `Exited(exitCode)` 从不产生（已文档化 DEFERRED） | consumer 无退出码 | CONTRACT-1 决定是否把 Exited 纳入必达（Local 需 waitpid 提取；ADB 需 shell-v2 EXIT 帧透传） |
| GAP-3 | read/listener 跨模式混用 | 仅行为 guard（listener 时 read→null） | 混用可能丢事件（无 runtime 强制） | CONTRACT-1 决定是否加显式错误/状态机 |
| (note) | protocol failure 无独立 outcome | ADB 映射 TRANSPORT_ERROR | 细化诊断 | 可选 outcome（低优先） |

## 6. Provider 差异登记（EXPECTED，不做统一）

```text
principal          Local=app uid / untrusted_app     ADB=uid=2000(shell) / shell domain
可访问目录          Local 受限（filesDir 等）           ADB 全 shell 可见（/data/local/tmp 等）
PTY 实现            Local posix PTY + sh -i           ADB remote PTY（shell,v2,pty）
连接模型            per-session 进程/PTY              1 TCP/TLS conn + N logical streams
```

## 7. Deferred（明确不在 execution contract 范围）

```text
Terminal Emulator / ScreenState / full-screen TUI semantics   → AT.EMULATOR-0 之后
Pairing / Discovery / Recovery / WD readiness                 → ADB Runtime Track
Human adb CLI / SSH / AG integration                          → OUT_OF_SCOPE (本轮)
```

## 8. 本矩阵对应的实测清单

- One-shot O1–O5（双后端）+ l1/l5（大输出对照）+ l2/l3/l4/l6/l7/l9（ADB 大输出档位 + mixed + reuse）
- Persistent p1–p8（双后端）
- EOF/close：e1/e2；多会话：m1/m2/m3；跨模式：x1/x2/x3；消费：r1/r2；字节流：b1；principal：c1
- Transport loss：t1（+恢复验证）；失败路径：f1（ADB mid-stream close）
- AT.V3-2.2 整类：BackendParityAuditTest 34/34、AdbBackendHeadlessTest 25/25；
  回归：Local 11/11、AT unit 6/6、App 7/7、Headless 1/1、Dynamic 9/9
- 全部原始结果与 logcat 摘录：
  `docs/evidence/at_v3_3/EVIDENCE.md`（审计）、`docs/evidence/at_v3_2_2/EVIDENCE.md`（flow-control 闭环）