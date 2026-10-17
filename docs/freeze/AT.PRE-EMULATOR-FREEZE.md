# AT.PRE-EMULATOR — Pre-Emulator Known-Good Execution Baseline Freeze

> 性质：BASELINE FREEZE / SSOT NORMALIZATION / CROSS-PROJECT HANDOFF PUBLICATION。
> **非**功能开发。DOCS ONLY。
> PRODUCTION_CODE_CHANGED = NO / TEST_CODE_CHANGED = NO / BUILD_CHANGE = NO /
> DEVICE_TEST = NO / EMULATOR_IMPLEMENTATION = NO。

---

## 1. Purpose

在 AT.POSTCLI-0（`700ca3b`）完成 post-CLI 架构复核之后，将 AndroidTerminal-V3 当前
**已验证成熟**的 execution architecture 正式固化为一个**可追溯的 Pre-Emulator
Known-Good Baseline**，作为后续 AT.EMULATOR-0 与 AndroidAgent（AG）的输入前提。

同时明确：哪些能力/边界**已稳定可依赖**，哪些仍**DRAFT / NOT_STARTED**（不得被下游
误认为已冻结）。

## 2. Source Baseline

```
SOURCE_BASELINE = 700ca3b
                (AT.POSTCLI-0: review post-CLI architecture and freeze next mainline)
```

本轮 Freeze 文档提交后，其 commit 将成为 **`PRE_EMULATOR_FREEZE_COMMIT`**，并打本地
annotated tag **`AT.PRE-EMULATOR-FREEZE-V1`**（§13/§23）。

## 3. Freeze Semantics（正式区分）

本轮 **Freeze = Known-Good Baseline Freeze**，**不是**：

- ❌ "这些代码以后永远不能改"
- ❌ "AT public API 已 final"

**含义**：

- 当前 execution capability 已有完整证据（§5）；
- 当前 architecture boundary 已有完整证据（§4）；
- 后续阶段**不得无理由重开**这些已冻结问题；
- 后续若修改被冻结区域，必须由**新的明确任务授权**（§14 Change Control）；
- 修改后必须证明**没有破坏当前 Known-Good semantics**；
- `700ca3b` 及本轮 Freeze commit 可作为 **regression / reference baseline**（§13）。

```
FROZEN BASELINE  ≠  FINAL API FREEZE
```

## 4. Frozen Architecture Boundaries

| 域 | owns | 冻结 |
|---|---|---|
| **AT Engine** | one-shot execution semantics / persistent terminal semantics / backend-neutral TerminalSession / input-write semantics / resize semantics / lifecycle-events / **future Terminal Emulator** / **future Screen State** | ✅ |
| **ADB Engine** | pairing / identity / TLS/CNXN / transport / streams / shell-v2 / ADB protocol state / low-level connection capability | ✅ |
| **AdbRuntime** | readiness / discovery / connectAuto / endpoint lifecycle / transport-loss observation / bounded recovery / lifecycle projection（**unique ADB lifecycle owner**） | ✅ |
| **Backend**（local / adb） | AT ↔ provider adaptation | ✅ |
| **Human UI** | rendering / input UX | ✅ |
| **AndroidAgent** | intelligence / reasoning / tool policy | ✅ |

**不得重新把 ADB lifecycle / pairing / TLS / recovery 塞回 AT Engine**（已 CLOSED 边界，
AT.POSTCLI-0 §3 审计确认）。

## 5. Frozen Execution Capability Matrix

当前已 VERIFIED 的 execution semantics（三路径：Local Persistent / ADB Persistent /
nested interactive，真实 Human Local PTY path 验证）：

```
AT_ONE_SHOT                   = VERIFIED
AT_PERSISTENT_SESSION         = VERIFIED
LOCAL_PERSISTENT              = VERIFIED
ADB_PERSISTENT                = VERIFIED
BYTE_STREAM                   = VERIFIED
STDIN                         = VERIFIED
STDOUT                        = VERIFIED
CTRL_C                        = VERIFIED
RESIZE                        = VERIFIED
CWD_CONTINUITY                = VERIFIED
ENV_CONTINUITY                = VERIFIED
NATURAL_EXIT                  = VERIFIED
EXPLICIT_CLOSE                = VERIFIED
MULTI_SESSION                 = VERIFIED
ADB_CONNECTION_REUSE          = VERIFIED
ADB_TRANSPORT_LOSS_SEMANTICS  = VERIFIED
CLEANUP                       = VERIFIED
SHELL_INDEPENDENCE            = VERIFIED

PERSISTENT_EXECUTION_CAPABILITY = MATURE_ENOUGH_FOR_EMULATOR
```

## 6. Frozen CLI Baseline

```
AT.CLI-0 = CLOSED
FULL_ADB_CLI = ONESHOT + LIFECYCLE + LIVE_STATUS + INTERACTIVE
SHELL_HOSTED_ADB_CLI = COMPLETE
```

shell-hosted CLI 定位：**Human-facing compatibility / convenience frontend**。
**不是**：AT public API / AG Tool contract / execution bus / ADB lifecycle owner。

**不得**因为以后 Agent 接入就扩展 `adb devices/push/pull/forward/reverse` 作为 AG 前置
条件（OUT_OF_CURRENT_AT_CLI_SCOPE）。

## 7. Dependency Baseline（POST_CLI_DEPENDENCY_AUDIT = PASS）

```
:at-engine        → 0 import of :adb-engine / :app          ✅
:adb-engine       → 0 import of :at-engine / :app           ✅
:at-backend-local → :at-engine（无 :adb / :app）             ✅
:at-backend-adb   → :at-engine + :adb-engine（无 :app）       ✅
:app              → AT composition                          ✅
```

作为 baseline 保存。

## 8. Raw Preview Role（冻结定位）

```
RAW_TERMINAL_UI = RAW TERMINAL PREVIEW / DEV PREVIEW
```

冻结其角色：**Regression Oracle / Debug View / Raw Byte Evidence Path**。

后续 AT.EMULATOR-0 **不得一开始删除它**。目标结构允许并存：

```
raw byte evidence path
        │
        ├────────────────→ Regression / Debug
        ▼
Terminal Emulator  (NOT_STARTED)
        ↓
Screen State       (NOT_STARTED)
```

当前 raw preview **不是**正式 Emulator / Screen State / product renderer。

## 9. ADB Engine / Runtime Status（冻结）

```
ADB_ENGINE_BLOCKS_AT_NEXT  = NO
ADB_RUNTIME_BLOCKS_AT_NEXT = NO
ADB_ENGINE = INDEPENDENT_MAINTENANCE_TRACK
```

这不表示 ADB Engine development permanently finished，而表示**当前 AT.EMULATOR-0 没有
ADB Engine prerequisite**。除非出现新的真实 blocker，不得因"顺手优化"重改 pairing /
identity / TLS / CNXN / StreamHub / recovery / Auto-WD / endpoint lifecycle / CLI transport。

## 10. Explicitly NOT Frozen（防误用，最重要）

```
AT_CONTRACT_STATUS           = DRAFT
AT_CONTRACT_FREEZE_AUTHORIZED = NO
```

**以下不得标记为 FROZEN**：

- final AT public API
- final ATTerminalSession API shape
- final callback / Flow / coroutine model
- Emulator API / ScreenState API / ScreenSnapshot API
- cursor observation API / terminal mode observation API
- final multi-session public API
- attach / handoff API
- concrete AG ↔ AT adapter API
- Human-Agent shared-session semantics

```
TERMINAL_EMULATOR              = NOT_STARTED
SCREEN_STATE                   = NOT_STARTED
STRUCTURED_TERMINAL_OBSERVATION = NOT_STARTED
```

**原因**：Minimal Emulator / Screen semantics 尚未经过真实实现验证。

**冻结规则**：

```
EXECUTION BASELINE    = FROZEN
ARCHITECTURE BOUNDARY = FROZEN
AT FINAL CONTRACT     = NOT FROZEN
```

## 11. Contract Status

```
AT_CONTRACT_STATUS           = DRAFT   (AT.CONTRACT-0 = Minimum Contract v0 Draft, NOT Final Freeze)
AT_CONTRACT_FREEZE_AUTHORIZED = NO
```

当前已验证的是 **byte-oriented execution semantics**；尚未验证 **screen-oriented
semantics**。因此 AT final contract 必须等 AT.EMULATOR-0 实现后由真实 terminal/screen
行为反向验证，再做 AT.CONTRACT-1。**禁止**仅因"当前 API 看起来够用"提前 freeze。

## 12. Emulator Readiness

```
PERSISTENT_EXECUTION_CAPABILITY = MATURE_ENOUGH_FOR_EMULATOR
```

byte-stream 输入/输出、lifecycle、resize、cleanup、multi-session、connection reuse、transport
loss 全部已在真实 path 验证。Emulator 所需的"稳定 byte 输入 + 可观察输出流"已具备。
**无缺失 execution Gate** → AT.EMULATOR-0 可启动（但本轮不实现）。

## 13. Regression Baseline Policy

```
REGRESSION_REFERENCE = 700ca3b + PRE_EMULATOR_FREEZE_COMMIT
```

后续 AT.EMULATOR-0 出现问题时，Freeze tag 用于区分：

- **execution regression**（破坏 Known-Good execution semantics）
- **emulator / parser / screen-state bug**（新引入的 Emulator 层问题）

Freeze tag 是 **Known-Good Execution Reference**，**不是**长期 release tag，**不是**
public API compatibility promise。

## 14. Change Control Rule

后续 AT.EMULATOR-0 如果出现 **Emulator implementation inconvenience**，**不得**因此直接
修改被冻结 execution layer。

**只有**出现"现有 execution semantics 无法正确承载真实 terminal semantics"这种**真实
contract gap**时，才允许：

```
STOP → CONTRACT_GAP report → Controller review → 更新 AT Contract → 发布新 handoff
```

例：可以因为 `TerminalSession` 根本无法表达 Emulator 所必需的数据/lifecycle 提出修改；
**不能**因为"换个接口写起来更舒服"修改 Known-Good execution baseline。

## 15. Next Phase

```
NEXT = AT.EMULATOR-0  (Minimal Terminal Emulator + Minimal Screen State)
```

本轮**不**实现 Emulator（见 AT.POSTCLI-0 §17/§18 的 scope 边界）。

## 16. Next Cross-Project Sync Point

```
NEXT_AT_SYNC_POINT_FOR_AG =
AT.EMULATOR-0 COMPLETE → AT.CONTRACT-1 REVIEW → AT → AG HANDOFF V2
```

或若 AT.EMULATOR-0 发现必须修改 execution contract：

```
AT.EMULATOR-0 STOP → CONTRACT_GAP REVIEW → 更新 AT Contract → 发布新 handoff
```

AG **不需要**持续追踪 AT 每个开发 commit，**只消费 versioned AT Handoff**
（本轮 = `AT_TO_AG_HANDOFF_V1`，见 `docs/handoff/AT_TO_AG_HANDOFF_V1.md`）。

## 17. Final Freeze Verdict

```
PRE_EMULATOR_FROZEN_BASELINE = ESTABLISHED
SOURCE_BASELINE              = 700ca3b
FREEZE_SEMANTICS             = KNOWN_GOOD_BASELINE
ARCHITECTURE_BOUNDARY        = FROZEN_BASELINE
EXECUTION_BASELINE           = FROZEN
CLI_BASELINE                 = FROZEN
ADB_ENGINE_BASELINE          = FROZEN_FOR_CURRENT_AT_MAINLINE
ADB_RUNTIME_BASELINE         = FROZEN_FOR_CURRENT_AT_MAINLINE
AT_CONTRACT_STATUS           = DRAFT
AT_CONTRACT_FREEZE_AUTHORIZED = NO
TERMINAL_EMULATOR            = NOT_STARTED
SCREEN_STATE                 = NOT_STARTED
AT_TO_AG_HANDOFF             = V1_PUBLISHED
NEXT                         = AT.EMULATOR-0
```
