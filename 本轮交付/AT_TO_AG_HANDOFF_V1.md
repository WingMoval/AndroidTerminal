# AT → AndroidAgent Handoff V1

> **Versioned capability / architecture snapshot** for AndroidAgent (AG) consumption.
> 不是完整 AT 开发历史；不是 binary ABI guarantee；不是 final Kotlin API freeze。
> 足够让 AG 不需要重新研究整个 AT repo 就能知道"当前 AT 可依赖什么、不能依赖什么"。

---

## 9. Identity

```
HANDOFF_NAME           = AT_TO_AG_HANDOFF_V1
AT_HANDOFF_VERSION     = V1
SOURCE_PROJECT         = AndroidTerminal-V3
SOURCE_BASELINE        = 700ca3b
FREEZE_TAG             = AT.PRE-EMULATOR-FREEZE-V1
AT_PHASE               = PRE_EMULATOR
NEXT_AT_PHASE          = AT.EMULATOR-0
CONTRACT_STATUS        = DRAFT
```

**Handoff V1 = capability / architecture snapshot**
**不是**：binary ABI guarantee / final Kotlin API freeze。

AG 消费的是 **versioned Handoff**，不追踪 AT 每个 commit。

---

## 10. Core Architecture

```
AG = Intelligence
AT = Execution
```

推荐长期关系：

```
AGA / AGC
   ↓
AG Core
   ↓
Tool Contract / Registry
   ↓
Concrete Tool / Execution Adapter      ← AT composition 在这里（AG Core 之外）
   ↓
AT Engine
   ↓
Backend (local / adb)
   ↓
Local / ADB
```

**AG Core 不得直接依赖具体 AT implementation。** AG Core 可以定义 Tool contract /
Tool registry / execution intent；**concrete Android Terminal Tool / Execution Adapter
在 AG Core 外完成 AT composition**。

> 本 Handoff **不**把 "AG Core → AT implementation" 写成推荐路径。

---

## 11. AG_ALLOWED_ASSUMPTIONS_V1（可安全假设）

> 写的是 **semantic capability exists**（能力层面存在），**不是** final API guarantee。
> 不要把某个当前 Kotlin method signature 误写成 final frozen API。

```
AT one-shot execution exists
AT persistent terminal session exists
backend-neutral terminal execution exists
raw byte output exists
byte input / write exists
resize exists
Ctrl-C / control-byte input works
close / lifecycle events exist
Local backend exists
ADB backend exists
multiple sessions exist
ADB connection / recovery 由 AT 下方现有 ADB lifecycle subsystem 负责
```

**Agent 不需要自己**：pair / TLS / discover / reconnect / 管理 adbd transport。

---

## 12. AG_FORBIDDEN_ASSUMPTIONS_V1（不得假设）

```
Terminal Emulator exists               = NO
Screen State exists                    = NO
Screen Snapshot exists                 = NO
cursor state API exists                = NO
TUI semantic observation exists        = NO
final TerminalSession API frozen       = NO
final Screen API frozen                = NO
final AG Adapter shape frozen          = NO
Human-Agent shared session             = NO / DEFERRED
full xterm compatibility               = NO
renderer API available to AG           = NO
```

**不得**让 AG 根据 Handoff V1 提前设计 `ScreenSnapshotTool` / `CursorTool` /
`TuiObservationTool` 等依赖尚不存在 Screen contract 的接口。

---

## 13. Agent Readiness

```
AG_RAW_TERMINAL_CAPABILITY = ELIGIBLE
```

含义：Agent 已可以（在未来通过适配层）消费 **one-shot shell command** + **persistent
byte-stream session**。**本轮不实现 adapter**。

```
AG_SCREEN_AWARE_TERMINAL = BLOCKED_BY_EMULATOR
```

含义：Agent 当前**没有权威** screen grid / cursor / alternate buffer / terminal mode /
screen snapshot。**不能**把 raw escape bytes 当成 Screen State。

---

## 14. Human-Agent Shared Session

```
HUMAN_AGENT_SHARED_SESSION = DEFERRED
```

不得因当前已存在 SharedSessionHandle / DebugBridge / CLI bridge 就推导"Agent 应默认
attach Human session"。本 Handoff 只说明相关**技术基础/实验能力存在**，**不**改变
产品/架构决策。

---

## 15. AT Contract Status（给 AG 的解释）

```
当前 AT Contract = DRAFT, EXECUTION-VALIDATED, NOT FINAL FROZEN
```

- 已验证：**byte-oriented execution semantics**。
- 未验证：**screen-oriented semantics**。

因此 AG 可以针对**能力层面的存在性**规划工具架构；但**不得**针对**当前具体 AT Kotlin
API** 建立长期不可变假设。

---

## 16. Next Sync Point

```
NEXT_AT_SYNC_POINT_FOR_AG =
AT.EMULATOR-0 COMPLETE
   ↓
AT.CONTRACT-1 REVIEW
   ↓
AT → AG HANDOFF V2
```

或若 AT.EMULATOR-0 发现必须修改 execution contract：

```
AT.EMULATOR-0 STOP → CONTRACT_GAP REVIEW → 更新 AT Contract → 发布新 handoff
```

AG 只消费 versioned Handoff，不持续追踪 AT 每个 commit。

---

## 17. Change-Control Policy（AG 视角）

后续 AT.EMULATOR-0 若出现 Emulator implementation inconvenience，**不得**因此修改被
冻结 execution layer。只有"现有 execution semantics 无法承载真实 terminal semantics"
这种真实 contract gap 才允许 STOP → CONTRACT_GAP → Controller review。AG 依赖的是
**versioned Handoff**，contract gap 会触发新 Handoff 版本，AG 按版本切换即可。

---

## 26. Handoff Consistency Gate（H1-H10）

| # | 检查 | 结果 |
|---|---|---|
| H1 | 没有要求 AG Core import concrete AT implementation | ✅（§10 明确 adapter 在 AG Core 外） |
| H2 | 没有把 current Kotlin signature 写成 final frozen API | ✅（§11 标注 semantic，非 final） |
| H3 | 没有声称 ScreenState 已实现 | ✅（§12 = NO） |
| H4 | 没有声称 Emulator 已实现 | ✅（§12 = NO） |
| H5 | 没有声称 Human-Agent shared session 已确定 | ✅（§14 = DEFERRED） |
| H6 | 明确 raw terminal = ELIGIBLE | ✅（§13） |
| H7 | 明确 screen-aware = BLOCKED_BY_EMULATOR | ✅（§13） |
| H8 | 明确 next sync point = post Emulator / Contract review | ✅（§16） |
| H9 | 明确 AT lifecycle/recovery 不由 AG ownership | ✅（§11 ADB recovery 由 AT 下方 subsystem） |
| H10 | 明确 Handoff V1 是 versioned capability snapshot，不是永久 API promise | ✅（Identity + §9） |

**H1-H10 全 PASS → Handoff V1 = PUBLISHED。**
