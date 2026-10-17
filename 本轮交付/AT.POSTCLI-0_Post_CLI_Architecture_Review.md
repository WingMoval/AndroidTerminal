# AT.POSTCLI-0 — Post-CLI Architecture Review & Next-Mainline Freeze

> 性质：Architecture Review / Boundary Audit / Roadmap Normalization / SSOT Closure。
> **非**生产功能开发。本轮 PRODUCTION_CODE_CHANGED = NO / TEST_CODE_CHANGED = NO（docs/audit only）。
> 基线：`9fe62fa`（AT.CLI-0.2 automation closure）。
> 原则：REVIEW / NORMALIZE / DECIDE / STOP。

---

## 1. CLI Final Closure（§2 闭合）

Human 真机手动验证（HONOR ALI-AN00 / Android 15）已确认 §37 全部子项 PASS：

| 子项 | 结果 |
|---|---|
| A. Local `id` → app UID | PASS |
| B. 无参 `adb shell` → 真实 Remote PTY | PASS |
| C. remote `id` → uid=2000(shell) / `tty` → /dev/pts/* | PASS |
| D. persistent cwd + env（cd /data/local/tmp + export/echo YES） | PASS |
| E. remote `sleep 30` → CTRL-C → sleep 终止、shell 存活 → `echo AFTER_CTRL_C` | PASS |
| F. remote `stty size` → RESIZE → `stty size` 变化（**REMOTE_PTY_RESIZE_HUMAN_GATE = PASS**） | PASS |
| G. remote `exit` → 回 Local → `id` → app UID | PASS |
| H. 退出后 `adb shell id`（one-shot 仍正常） | PASS |

**正式冻结**：

```
REAL_HUMAN_INTERACTIVE_SHELL = PASS
AT.CLI-0.2                   = CLOSED
AT_CLI_0_OVERALL_STATUS      = COMPLETE
SHELL_HOSTED_ADB_CLI         = COMPLETE
FULL_ADB_CLI                 = ONESHOT + LIFECYCLE + LIVE_STATUS + INTERACTIVE
```

SSOT / Manifest / Evidence Index / Vertical Slice / INTERACTIVE_SHELL_CLOSURE 已归一化为
CLOSED/PASS（不再写 PENDING / "Human Gate 待执行" / "automation only"）。

---

## 2. POST_CLI_CAPABILITY_MATRIX

基于真实 repo（`git` 审计 + 源文件 surface 盘点）：

### A. ADB Engine（`:adb-engine`，13 源文件）

| 能力 | 状态 | 证据 |
|---|---|---|
| pairing | VERIFIED | `AdbEngine.pair`（host,port,code,keyDir）→ AdbPairingResult |
| identity | VERIFIED | keyDir 持久化 identity |
| TLS / CNXN | VERIFIED | AdbNative transport（shell,v2） |
| connection lifecycle | VERIFIED | connect/disconnect/connectionState |
| wireless discovery | VERIFIED | AdbAndroidNsdDiscoveryBackend |
| recovery | VERIFIED | AdbRecoveryCoordinator（ONE owner，bounded） |
| one-shot streams | VERIFIED | `executeShell` → AdbShellResult（stdout/stderr/exit 三通道分离） |
| interactive streams | VERIFIED | `openInteractive(service)` → AdbInteractiveStream |
| multi-stream / concurrent | VERIFIED | 每 session 独立 handle；AdbPersistentBackend multi-session 已验证 |
| flow control | VERIFIED | StreamHub（native RX owner） |

### B. AdbRuntime（`:adb-engine`，lifecycle owner）

| 能力 | 状态 |
|---|---|
| readiness | VERIFIED |
| Auto-WD（WRITE_SECURE putInt + bounded retry） | VERIFIED |
| endpoint lifecycle（store） | VERIFIED |
| connectAuto | VERIFIED |
| transport loss projection（read-only observer，1.5s poll，ONLINE→!ONLINE edge） | VERIFIED |
| bounded recovery（ONE owner，3-attempt） | VERIFIED |
| lifecycle observation（addLifecycleListener，read-only） | VERIFIED |

### C. AT Engine（`:at-engine`，5 源文件，public surface = DRAFT）

| 能力 | 状态 | 证据 |
|---|---|---|
| one-shot execution | VERIFIED | `ATEngine.execute` |
| persistent terminal | VERIFIED | `ATEngine.openSession` |
| backend-neutral TerminalSession | VERIFIED | `ATTerminalSession`（write/read/setListener/resize/close/state） |
| byte-stream events | VERIFIED | `ATSessionEvent`（Data/Resized/Exited/Eof/Failed/Closed） |
| Local Backend | VERIFIED | `:at-backend-local` |
| ADB Backend | VERIFIED | `:at-backend-adb` |
| shell independence | VERIFIED | AT Engine 无 shell/ADB import（依赖审计 PASS） |
| **Terminal Emulator** | **NOT_STARTED / DEFERRED** | ATSession.kt 注释明确 "DEFERRED … does NOT invent their API" |
| **Screen State** | **NOT_STARTED / DEFERRED** | 同上 |

> **关键事实**：AT Engine 当前输出契约是**纯 byte-stream**（`Data(bytes)` + lifecycle
> events）。**没有** ANSI/VT 解析、screen grid、cursor state、screen snapshot。"已有 raw
> byte output" ≠ "已有 Terminal Emulator"。

### D. Human App（`:app`）

| 能力 | 状态 |
|---|---|
| Local pages | VERIFIED |
| ADB pages | VERIFIED |
| dynamic multi-session | VERIFIED |
| SharedSessionHandle（Human+Debug fan-out） | VERIFIED |
| shell-hosted adb CLI | VERIFIED |
| one-shot adb | VERIFIED |
| interactive adb | VERIFIED |
| status projection（0.1B live command-time） | VERIFIED |
| **Human renderer** | **RAW TERMINAL PREVIEW**（DEV PREVIEW，append-only TextView，无 Emulator） |

### E. 未完成 capability

| 能力 | 分类 |
|---|---|
| Terminal Emulator | NOT_STARTED |
| Screen State | NOT_STARTED |
| structured terminal observation | NOT_STARTED |
| ANSI/VT parsing | NOT_STARTED |
| renderer（非 raw） | NOT_STARTED（raw preview 存在，非 product renderer） |
| First-run product onboarding | DEFERRED（底层 capability VERIFIED，产品 onboarding 未实现） |
| Agent Terminal consumption | NOT_STARTED（readiness 见 §10） |

---

## 3. Architecture Boundary Audit（§20 再确认，不重开）

| 域 | owns | 状态 |
|---|---|---|
| AT Engine | one-shot / persistent terminal semantics / TerminalSession / **future Emulator / Screen State** | 确认（Emulator 属 AT，尚未实现） |
| ADB Engine | ADB protocol / provider mechanics（pairing/identity/TLS/streams） | 确认 |
| Backend | AT ↔ provider adaptation（local / adb） | 确认 |
| Human UI | rendering / input UX | 确认（当前 raw preview） |
| AndroidAgent | reasoning / tool policy | 确认（未实现，见 §10） |

**无偏离**，无 debt。

### Dependency Audit（§21）— **POST_CLI_DEPENDENCY_AUDIT = PASS**

```
:app            → :at-engine（+ cli bridge 消费）        ✓
:at-backend-adb → :at-engine + :adb-engine（无 :app）     ✓  15 imports = com.at.engine.* + com.adb.engine.*
:at-backend-local → :at-engine（无 :adb / :app）          ✓  9 imports = com.at.engine.*
:adb-engine     → 0 import of :at-engine / :app          ✓
:at-engine      → 0 import of :adb-engine / :app         ✓
```

---

## 4. Persistent Terminal Maturity（§6）

三条真实路径成熟度：

| 维度 | Local Persistent | ADB Persistent | nested interactive |
|---|---|---|---|
| byte stream | ✓ | ✓ | ✓ |
| stdin | ✓ | ✓ | ✓（I6 cat） |
| stdout | ✓ | ✓ | ✓ |
| Ctrl-C | ✓ | ✓ | ✓（I7） |
| resize | ✓ | ✓ | ✓（I8 + relay） |
| cwd / env continuity | ✓ | ✓ | ✓（I4/I5） |
| natural exit | ✓ | ✓ | ✓（I10，Eof→T_EXIT） |
| explicit close | ✓ | ✓ | ✓（onInteractiveClose） |
| multi-session | ✓ | ✓ | ✓（I15） |
| connection reuse | n/a | ✓ | ✓（I13） |
| transport loss | n/a | ✓ | ✓（I14，no resume/replay） |
| cleanup | ✓ | ✓ | ✓ |

**`PERSISTENT_EXECUTION_CAPABILITY = MATURE_ENOUGH_FOR_EMULATOR`**。

byte-stream 输入/输出、lifecycle、resize、cleanup、multi-session、connection reuse、transport
loss 全部已在真实 Human Local PTY path 验证（I1-I16 + 回归全绿）。Emulator 所需的
"稳定 byte 输入 + 可观察输出流" 已经具备。**无缺失 execution Gate**。

---

## 5. Terminal Emulator Gap（§7）

**当前 AndroidTerminal 输出仍然是**：**raw bytes**（append-only text buffer，`MainActivity`
注释 "RAW terminal preview (no Terminal Emulator / Screen State)"）。

**缺失**：

```
ANSI escape interpretation   — 无
cursor movement              — 无
screen grid (rows×cols cells)— 无
line wrapping semantics      — 无（依赖 TextView 自然换行，非 terminal 语义）
erase operations (ED/EL)     — 无
alternate screen             — 无
scroll region                — 无
cursor state                 — 无
terminal modes               — 无
screen snapshots             — 无
```

**Boundary（Canonical）**：

```
Terminal Emulator + Screen State  → AT Engine domain（未来）
Android View / Compose renderer   → Human Product/UI domain
```

Canvas / TextView / Compose / 字体渲染 **不得**写进 Emulator Core。

---

## 6. Minimal Screen State Necessity（§8）

**问题**：AT Engine 当前只有 byte-stream output/events，是否足以支持未来 Human terminal /
Agent TerminalTool / TUI observation / screen snapshot？

**回答**：byte-stream 足以支持 **raw terminal consumer**（当前 Human raw preview 已证明）。
但对 **screen-aware consumer** 不足：

| Consumer 类型 | byte-stream 是否够 | 缺什么 |
|---|---|---|
| raw byte consumer（当前 Human preview / one-shot / 字节 relay） | 够 | — |
| screen-aware consumer（TUI 观察 / screen snapshot / 结构化状态） | **不够** | ANSI 解析 + screen grid + cursor + erase + snapshot |

**判断**：已经到了需要引入 **Minimal Terminal Emulator + Minimal Screen State** 的阶段——
因为 Persistent 能力已 MATURE（§4），下一价值增量就在"让 consumer 看到稳定 screen"而非
更多 byte plumbing。

---

## 7. Contract Freeze Gate（§9）

```
AT_CONTRACT_STATUS           = DRAFT（AT.CONTRACT-0 = Minimum Contract v0 Draft，NOT Final Freeze）
AT_CONTRACT_FREEZE_AUTHORIZED = NO
```

**唯一 blocker**：Minimal Emulator / Screen semantics 仍未验证。

理由：AT Contract 的 persistent 部分目前是 byte-stream 契约。在引入 Emulator/Screen State
**之前**冻结 contract，会把"consumer 只看 raw bytes"固化进 contract，未来加 Screen State
需要 breaking redesign。正确顺序是：先做 AT.EMULATOR-0 让真实 terminal/screen 行为反向
验证 contract，再做 AT.CONTRACT-1。

**禁止**仅因"目前 API 看起来够用"就提前 Freeze。

---

## 8. Human UI Status（§10）

**RAW_TERMINAL_UI = RAW TERMINAL PREVIEW（DEV PREVIEW）**。

本轮 Human 操作观察登记的 debt（只登记，不修）：

| Debt | 分类 | Blocking? |
|---|---|---|
| 顶部 `adbStateLabel` 显示 `ADB: DISCONNECTED`（来自 `AppRuntime.adbState()` 轮询），而 CLI 已 `[adb] connected`（session 投影）——两者时序短暂不一致 | **UI_STATE_PROJECTION_DEBT** | **NON_BLOCKING** |
| READY 状态下偶尔 `adb shell id` 出现单独 `[adb] checking connection...`（fast-path backfill） | **STATUS_DEDUPE_UX_DEBT** | **NON_BLOCKING** |

两者均为 UX polish，不阻塞任何 execution capability，不阻塞 Emulator。

---

## 9. First-run Status（§11）

正式语义保持（不变）：

```
FRESH_INSTALL_ZERO_TOUCH           = NOT_COMPLETE
FIRST_USE_MANUAL_BOOTSTRAP         = REQUIRED
POST_BOOTSTRAP_AUTO_WD             = VERIFIED
POST_BOOTSTRAP_ONE_COMMAND_COLD_READY = VERIFIED
POST_BOOTSTRAP_ZERO_MANUAL_COLD_READY = VERIFIED（限定 post-bootstrap）
```

**First-run product onboarding = PRODUCTIZATION_TRACK / DEFERRED**。

区分：**底层 capability 已验证**（Auto-WD + cold ready）≠ **产品 onboarding 已实现**
（首次引导 UI 未做）。First-run 未完成**不是** ADB Engine capability blocker，不列为 NEXT。

---

## 10. AndroidAgent Readiness（§12，review only，禁实现）

| 能力 | 状态 | 依据 |
|---|---|---|
| **AG_RAW_TERMINAL_CAPABILITY**（Agent 经 TerminalTool 执行 shell command + 消费 persistent byte-stream session） | **ELIGIBLE** | AT Engine 的 `execute`（one-shot）+ `openSession`（persistent byte-stream）public surface 已足够；Agent 只消费 bytes，不需 Emulator |
| **AG_SCREEN_AWARE_TERMINAL**（Agent 理解/观察 TUI screen / screen snapshot） | **BLOCKED_BY_EMULATOR** | 无 ANSI 解析 + screen grid + snapshot；byte-stream 无法让 Agent 读"当前 screen 状态" |

**关键区分**：

- Agent **执行** shell command（raw byte consumer）→ 现在就可以（ELIGIBLE）。
- Agent **观察** TUI screen（screen-aware）→ 需 Emulator/Screen State（BLOCKED_BY_EMULATOR）。

本轮**不**新增 TerminalTool / AG Adapter / AG dependency。

---

## 11. Human-Agent Shared Session（§13）

**HUMAN_AGENT_SHARED_SESSION = DEFERRED**（保持）。

SharedSessionHandle / DebugBridge / CLI 的存在只证明**技术能力存在**，不改变此前产品/架构
决策（Human/Agent 默认不共享 session）。Post-CLI review 不重开此决策。

---

## 12. ADB Engine / Runtime 后续主攻判断（§14）

```
ADB_ENGINE_BLOCKS_AT_NEXT   = NO
ADB_RUNTIME_BLOCKS_AT_NEXT  = NO
```

pairing / identity / connection / recovery / streams（one-shot + interactive）/ persistent /
multi-stream 都已满足当前 AT 建设（CLI 全 4 slice 已在其上完成）。ADB Engine 后续转为
**independent maintenance / future capability track**，不因 CLI 完成后没任务而继续增功能。

---

## 13. Technical Debt Register（§22）

| Debt | 分类 |
|---|---|
| 顶部 ADB state stale projection（UI_STATE_PROJECTION_DEBT） | UX_POLISH（NON_BLOCKING） |
| READY fast-path transient `checking` line（STATUS_DEDUPE_UX_DEBT） | UX_POLISH（NON_BLOCKING） |
| First-run manual bootstrap（产品 onboarding 未实现） | PRODUCTIZATION（DEFERRED） |
| raw renderer 无 emulator | **BLOCKING_NEXT**（这是 NEXT 本身，非 blocker——Emulator 是目标） |
| Screen State absent | 同上（Emulator 目标的一部分） |
| Contract not frozen | DEFERRED_ARCHITECTURE（依赖 Emulator 先验证） |
| Human-Agent shared session deferred | DEFERRED_ARCHITECTURE |
| DebugBridge debug-only | NON_BLOCKING（产品 CLI Bridge 是独立 product surface） |

**无真正阻塞 NEXT 的 execution blocker**。§4 已证 Persistent = MATURE_ENOUGH_FOR_EMULATOR。

---

## 14. Candidate Comparison（§15）

| 候选 | WHY_NOW | WHY_NOT_NOW | DEPENDENCY | ARCHITECTURE_VALUE | RISK |
|---|---|---|---|---|---|
| **A. AT.EMULATOR-0**（Minimal Terminal Emulator + Screen State） | Persistent 已 MATURE；raw output 是当下最大价值缺口；解锁 screen-aware Human + Agent；反向验证 contract | —（无未决前置） | Persistent byte-stream（已 VERIFIED） | **高**（AT Engine 从 byte-stream 升级到 screen-aware，Canonical 核心新能力） | 中（Emulator 语义需精确，但 scope 可最小化） |
| B. AT.CONTRACT-1（Contract Freeze） | 想固化 API | **Emulator/Screen 未验证就 freeze 会把 raw-bytes-only 固化**，未来 breaking redesign | 依赖 A 先验证 screen 行为 | 中 | 高（过早 freeze → 返工） |
| C. AT.FIRSTRUN-*（产品 onboarding） | 用户体验 | 底层 capability 已 VERIFIED，onboarding 是 productization 非架构主线；不解锁 Emulator/Agent | 无强依赖 | 低（产品 polish） | 低 |
| D. AG.TERMINAL-*（Agent 集成） | Agent 是重要 consumer | AG raw 已 ELIGIBLE 但 screen-aware 被 Emulator 卡；先做 AG 会绕过 AT 主线 | 依赖 A（screen-aware 部分） | 中 | 中（提前做 AG adapter = 过度设计，违反 §0 禁实现 AG） |
| E. ADB CLI expansion（devices/push/pull/forward） | — | OUT_OF_CURRENT_AT_CLI_SCOPE（§5）；不阻塞 AT 架构；CLI 是 convenience frontend 非总线 | — | 低 | 低（但偏离 AT 主线） |

---

## 15. Final NEXT Decision（§16/§17/§18）

**`NEXT = AT.EMULATOR-0 — Minimal Terminal Emulator + Minimal Screen State`**

证据支持（非历史路线预设）：
- §4：Persistent execution = **MATURE_ENOUGH_FOR_EMULATOR**（无缺失 execution Gate）。
- §5/§6：raw byte output 是当下最大价值缺口；screen-aware consumer 需要 Emulator。
- §7：Contract freeze 被 Emulator 阻塞（先验证 screen 行为再 freeze）。
- §10：AG screen-aware 被 Emulator 阻塞；AG raw 已 ELIGIBLE（不需提前做 AG）。
- §12：ADB Engine/Runtime 不阻塞 NEXT。

### AT.EMULATOR-0 目标边界（只定义，不实现）

```
byte output
   ↓
terminal parser（ANSI/VT 子集）
   ↓
minimal screen model（rows×cols grid + cursor + 属性）
   ↓
observable stable screen state（snapshot）
```

**最小 scope 优先覆盖**（不锁死具体库/API/数据结构——那是下一任务调研决策）：

```
printable text / CR / LF / BS / TAB
cursor movement subset
erase subset（ED/EL）
SGR / basic attributes
resize
screen snapshot
```

**Emulator Core 明确不包含**（§18，避免 scope 膨胀）：

```
Android View / Compose renderer
字体 / touch selection / clipboard / IME UI / keyboard UI / tabs UI
ADB pairing UI
Agent Tool
First-run onboarding
```

**现有 raw Human UI 的角色**（§19）：**保留**，作为 Regression Oracle / Debug View /
Byte-stream evidence。下一阶段允许 `raw bytes → emulator → screen model` 并存低层 evidence
path，**不**一上来替换旧 renderer。

**禁止本轮预设**：libvterm / Termux emulator / 自研 parser 的选择（属下一任务决策）。

---

## 16. STOP 检查（§29）

无 STOP 条件触发：

- SSOT 对 CLI Final Closure 无事实冲突（本轮已归一化）。
- repo 与文档 dependency boundary 一致（§3 审计 PASS）。
- Persistent capability 达到文档声称状态（§4 MATURE）。
- 无新 execution blocker 需先解决。
- 本轮仅改 docs，未改 production code。
- 未重开已 CLOSED 的 ADB/CLI 阶段。
- Canonical 路线与真实 repo 无重大偏离。

---

## 17. 本轮改动清单

**docs/audit only**（PRODUCTION_CODE_CHANGED = NO / TEST_CODE_CHANGED = NO）：

```
新增：docs/audit/AT.POSTCLI-0_Post_CLI_Architecture_Review.md
更新：docs/evidence/at_cli_0/INTERACTIVE_SHELL_CLOSURE.md   （Human Gate PENDING → PASS/CLOSED）
更新：docs/architecture/AndroidTerminal_Canonical_V1.md     （CLI CLOSED + POSTCLI 状态 + NEXT=AT.EMULATOR-0）
更新：docs/migration/Migration_Manifest_V2_to_V3.md          （NEXT → AT.EMULATOR-0）
更新：docs/evidence/V3_Evidence_Index.md                     （CLI CLOSED + POSTCLI 行）
更新：docs/implementation/AT.CLI-0_Shell_Hosted_ADB_CLI_Vertical_Slice.md（Human Gate CLOSED）
本轮交付/ 刷新（扁平）
```
