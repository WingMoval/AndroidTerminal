# AT.AG-CONSUMER-FREEZE — Known-Good AG Consumer Capability Snapshot Freeze

> 性质：DOCS ONLY / KNOWN-GOOD CONSUMER CAPABILITY SNAPSHOT / FREEZE PUBLICATION。
> **非**功能开发。**非** FINAL AT API FREEZE。**非** AT_TO_AG_HANDOFF_V2。
> PRODUCTION_CODE_CHANGED = NO / TEST_CODE_CHANGED = NO / BUILD_CHANGE = NO /
> DEVICE_TEST = NO / EMULATOR_IMPLEMENTATION = NO。

---

## 1. Identity

```
FREEZE_NAME          = AT.AG-CONSUMER-FREEZE-V1
SOURCE_BASELINE      = c9cee21
BASE_HISTORICAL_FREEZE = AT.PRE-EMULATOR-FREEZE-V1   (-> 7ccb1bf)
RELATION             = EXTENDS CONSUMER CAPABILITY SNAPSHOT
                       DOES NOT REWRITE HISTORICAL FREEZE
AT_CONTRACT_STATUS   = DRAFT
FINAL_API_FREEZE     = NO
NEXT_AT_MAINLINE     = AT.EMULATOR-0
```

`SOURCE_BASELINE = c9cee21`（AT.CONSUMER-BOOTSTRAP-0 CLOSED + AT.CLI-CONSUMER-0 CLOSED）
是**已真机验证完成的 consumer source baseline**。本轮 docs-only commit 是
**FREEZE_PUBLICATION_COMMIT**，新 tag `AT.AG-CONSUMER-FREEZE-V1` 指向它
（类比历史 `700ca3b` = execution source / `7ccb1bf` = freeze publication）。

## 2. Why This Freeze Exists

版本历史：

```
700ca3b   AT.POSTCLI execution source baseline
7ccb1bf   AT.PRE-EMULATOR-FREEZE-V1（AG Handoff V1 的 known-good snapshot）
c7eb84f   AT.CLI-CONSUMER-0（reusable :at-cli + independent consumer + Local sh -> adb shell -> uid=2000）
c9cee21   AT.CONSUMER-BOOTSTRAP-0（AdbConsumerBootstrap + per-consumer identity + unified pairing + Product UI + CLI adb pair）
```

旧 Handoff V1（base `700ca3b`）**不包含** `c7eb84f` / `c9cee21` 新增的 consumer
capability（reusable `:at-cli` / 独立 consumer host / per-consumer identity /
`AdbConsumerBootstrap` / 统一 pairing / Product UI 配对 / CLI `adb pair`）。

若 AG 直接切到 `c9cee21` 却仍声称 `AT_HANDOFF = AT.PRE-EMULATOR-FREEZE-V1`，
则 **CODE SNAPSHOT** 与 **FORMAL HANDOFF SNAPSHOT** 不一致。本轮只解决这个
**版本化交接问题**，不实现任何新功能。

## 3. Freeze Semantics（正式区分）

```
FREEZE_SEMANTICS = KNOWN_GOOD_CONSUMER_CAPABILITY_SNAPSHOT
```

明确**不是**：

```
FINAL_API_FREEZE          = NO
FINAL_KOTLIN_API_GUARANTEE = NO
BINARY_ABI_GUARANTEE      = NO
HANDOFF_V2                = NO
RELEASE_TAG               = NO
```

**冻结**（capability existence / architecture ownership / verified consumer path /
source baseline / integration assumptions / forbidden duplication）：

- capability existence（§8）
- architecture ownership（§9）
- verified consumer path（§10/§11）
- source baseline（`c9cee21`）
- integration assumptions（§16 of Handoff）
- forbidden duplication（§9 禁止 AG 误读）

**不冻结**（final Kotlin signatures / Emulator API / ScreenState API / final
TerminalSession API / final AG Adapter API / binary packaging / Maven-AAR form）。

## 4. What Is Frozen For Consumer Integration

### A. Execution（继承 PRE-EMULATOR-FREEZE，已在 `700ca3b` 验证）

```
ONE_SHOT          = VERIFIED
PERSISTENT_TERMINAL = VERIFIED
LOCAL_BACKEND     = VERIFIED
ADB_BACKEND       = VERIFIED
RAW_BYTE_IO       = VERIFIED
WRITE_INPUT       = VERIFIED
CTRL_C            = VERIFIED
RESIZE            = VERIFIED
MULTI_SESSION     = VERIFIED
NATURAL_EXIT      = VERIFIED
EXPLICIT_CLOSE    = VERIFIED
```

### B. Reusable Terminal Environment（AT.CLI-CONSUMER-0 验证）

```
REUSABLE_AT_CLI            = VERIFIED
LOCAL_TERMINAL_TO_ADB_CLI  = VERIFIED
LOCAL_SHELL_PRINCIPAL      = CONSUMER APP UID（consumer host: u0_a381）
ADB_SHELL_PRINCIPAL        = uid=2000(shell)
REMOTE_EXIT_RETURNS_TO_LOCAL = VERIFIED
LOCAL_SESSION_RECREATED    = NO
```

### C. Consumer Isolation（AT.CLI-CONSUMER-0 §25 验证）

```
CONSUMER_DEPENDS_ON_APP   = NO
AT_CLI_DEPENDS_ON_APP     = NO
EXTERNAL_ADB_BINARY       = NO
ADB_SERVER_5037           = NO
```

### D. Bootstrap（AT.CONSUMER-BOOTSTRAP-0 验证）

```
AdbConsumerBootstrap         = VERIFIED
BOOTSTRAP_LOCATION           = :adb-engine
PAIRING_IMPLEMENTATION_COUNT = 1
IDENTITY_OWNER               = PER_CONSUMER_APP_PRIVATE
SELF_GENERATED_IDENTITY      = VERIFIED
PRIVATE_KEY_COPY             = NO
PAIRING_DISCOVERY            = VERIFIED
PAIRING                      = VERIFIED
RESTART_IDENTITY_REUSE       = VERIFIED
PRODUCT_UI_PAIRING           = VERIFIED
CLI_ADB_PAIR                 = VERIFIED
```

## 5. Architecture Ownership Freeze

```
AG / Consumer            = consumer
AT Engine                = terminal execution semantics
Local Backend            = local provider
ADB Backend              = ADB execution provider
AdbRuntime               = UNIQUE ADB lifecycle owner
ADB Engine               = ADB protocol / transport / pairing primitives
AdbConsumerBootstrap     = consumer-facing ADB first-use / bootstrap facade
:at-cli                  = shell-visible CLI frontend / reusable terminal capability
:app                     = AndroidTerminal Product / UI consumer
```

**禁止未来 AG 解释成**：

- `AdbConsumerBootstrap` = second runtime
- `:at-cli` = ADB lifecycle owner
- `:app` 是 consumer 必需依赖
- OpenCode 是 runtime / product 依赖

## 6. Canonical Consumer Terminal Path（VERIFIED）

```
Consumer
    ↓
AT Local Terminal
    ↓
/system/bin/sh
    ↓
shell-visible `adb`
    ↓
app_process / AtCliMain
    ↓
Reusable CLI Bridge（:at-cli，UDS，同 UID）
    ↓
same Consumer-owned AT composition
    ↓
ADB Backend
    ↓
AdbRuntime / ADB Engine
    ↓
adbd
    ↓
uid=2000(shell)
```

**VERIFIED**（consumer host C2/C4 + 产品回归）。

**明确**：Local Terminal 和 ADB shell **不是**两套 Consumer-owned execution
systems。ADB 切换发生在 **AT execution subsystem 内**（同一个 consumer-owned
AT composition，经 `:at-cli` bridge 路由）。

## 7. Consumer Bootstrap Path（VERIFIED）

```
Consumer app-private identityDir
    ↓
AdbConsumerBootstrap
    ↓
bootstrapState()
    ↓
PAIRING_REQUIRED   （if no usable identity）
    ↓
discoverPairing()
    ↓
pair() / pairDiscovered()
    ↓
AdbEngine.pair primitive
    ↓
AdbRuntime connectAuto
    ↓
READY
```

**Consumer 不拥有**：SPAKE2 / TLS pairing / certificate generation semantics /
CNXN / ADB transport / recovery。这些全部由 AT 下方 ADB subsystem
（AdbEngine / AdbRuntime）拥有。

## 8. Honest Bootstrap State Semantics（冻结）

```
无 identity
    → PAIRING_REQUIRED（有证据：无 key 材料）

已有 identity + generic connect failure
    → NOT automatically PAIRING_REQUIRED
    → CONNECT_FAILED / runtime diagnostic
```

**不得**在 Handoff 中告诉 AG「连接失败就要求重新配对」。Boolean connect 失败
不是 auth 证据；配对只作为 remediation 提供。

## 9. CLI Capability Freeze

**正式交给 Consumer**（已验证）：

```
adb shell [command...]
adb shell                                  （interactive Remote PTY）
adb pair <host>:<port> <code>
```

已验证：one-shot / interactive / stdin / Ctrl-C / resize / cwd-env continuity /
return to Local shell / connection reuse / bad args / bad pairing code。

**明确未实现 / DEFERRED**：

```
adb pair <code>                            （shorthand，DEFERRED）
MULTIPLE endpoint Product UI chooser       （DEFERRED）
```

**不得宣传**（除非当前真实 CLI 已实现）：push / pull / install / forward /
reverse / devices。当前 `:at-cli` 只实现 `shell` / `pair`（+ `version` 等基础
诊断），**不**实现 push/pull/forward/reverse/devices。

## 10. Module Availability Snapshot（CAPABILITY SNAPSHOT）

`c9cee21` 当前 consumer-relevant modules（以 `settings.gradle.kts` 为准）：

```
:at-engine             — AT Engine（reusable terminal execution capability）
:at-backend-local      — Local Backend adapter
:at-backend-adb        — ADB Backend adapter
:adb-engine            — ADB Engine + AdbRuntime + AdbConsumerBootstrap
:at-cli                — shell-visible CLI frontend / reusable terminal capability
:app                   — AndroidTerminal Product / UI consumer
:at-cli-consumer-host  — 独立第三方 consumer host（TEST / VALIDATION）
```

（另有 `:test-host` / `:adb-only-test-host` / `:at-shellless-test-host` 为历史
test/verification host，非本轮 consumer-relevant 焦点。）

**必须区分**：**CAPABILITY SNAPSHOT**（本 freeze 冻结的能力存在性）vs
**CURRENT PHYSICAL MODULE CONSUMPTION**（AG 当前如何物理消费这些 Gradle
module / API）。**不得**声称这些 Gradle module / API 是永久 ABI frozen。

## 11. Evidence Mapping（不复制巨量测试日志）

| capability | evidence source | verdict |
|---|---|---|
| Execution baseline（one-shot / persistent / byte I/O / Ctrl-C / resize / multi-session / exit / close） | `AT.PRE-EMULATOR-FREEZE.md`（base `700ca3b`） | VERIFIED |
| Reusable `:at-cli` + Local Terminal -> `adb` CLI | `AT.CLI-CONSUMER-0.md`（C1-C10 + §25 隔离） | VERIFIED |
| Local -> `adb shell` -> uid=2000（consumer host 独立） | `AT.CLI-CONSUMER-0.md` §4.1（C2/C4） | VERIFIED |
| Per-consumer SELF_GENERATED identity + restart reuse | `AT.CLI-CONSUMER-0.md` §4.3 + `AT.CONSUMER-BOOTSTRAP-0.md` §3.4 | VERIFIED |
| `AdbConsumerBootstrap` + unified pairing（B1-B5） | `AT.CONSUMER-BOOTSTRAP-0.md` §3.1 | VERIFIED |
| CLI `adb pair <host>:<port> <code>`（P1-P3） | `AT.CONSUMER-BOOTSTRAP-0.md` §3.2 | VERIFIED |
| Product UI pairing（U1-U3） | `AT.CONSUMER-BOOTSTRAP-0.md` §3.5 | VERIFIED |
| PAIRING_IMPLEMENTATION_COUNT = 1 / 无第二 runtime / 无 :app 依赖 | `AT.CONSUMER-BOOTSTRAP-0.md` §3.3 + `AT.CLI-CONSUMER-0.md` §4.2/§5 | VERIFIED |

**重点证明**：LOCAL -> `adb shell` -> uid=2000 / independent consumer / no
:app dependency / self-generated identity / pairing implementation count 1 /
restart reuse / no second runtime。

## 12. Handoff Relationship

```
AT_TO_AG_HANDOFF_V1          = HISTORICAL PUBLISHED SNAPSHOT（base 700ca3b，不修改）
AT_TO_AG_CONSUMER_HANDOFF_V1 = 本轮新增 SUPPLEMENTAL CONSUMER CAPABILITY SNAPSHOT
                               （base c9cee21，EXTENDS V1 / DOES NOT REWRITE）
AT_TO_AG_HANDOFF_V2          = 仍未创建
                               （保留至 POST AT.EMULATOR-0 + CONTRACT-1 REVIEW +
                                Controller authorization）
```

新 Handoff **不是** V1 INVALIDATED，**不是** V2，**不是** FINAL API FREEZE。

## 13. Final Freeze Verdict

```
CONSUMER_FREEZE_BASELINE      = ESTABLISHED
SOURCE_BASELINE               = c9cee21
FREEZE_SEMANTICS              = KNOWN_GOOD_CONSUMER_CAPABILITY_SNAPSHOT
REUSABLE_AT_CLI               = VERIFIED
LOCAL_TO_ADB_SHELL            = VERIFIED
ADB_SHELL_PRINCIPAL           = uid=2000(shell)
CONSUMER_DEPENDS_ON_APP       = NO
ADB_CONSUMER_BOOTSTRAP        = VERIFIED
PAIRING_IMPLEMENTATION_COUNT  = 1
IDENTITY_OWNER                = PER_CONSUMER_APP_PRIVATE
PRIVATE_KEY_COPY              = NO
PAIRING_DISCOVERY             = VERIFIED
PAIRING                       = VERIFIED
RESTART_IDENTITY_REUSE        = VERIFIED
ADB_RUNTIME_OWNER             = AdbRuntime（UNIQUE）
SECOND_ADB_RUNTIME            = NO
SECOND_RECOVERY_OWNER         = NO
EXTERNAL_ADB_BINARY           = NO
ADB_SERVER_5037               = NO
PAIRING_REQUIRED_SEMANTICS    = HONEST
AT_CONTRACT_STATUS            = DRAFT
FINAL_API_FREEZE              = NO
HANDOFF_V2_CREATED            = NO
NEXT_AT_MAINLINE              = AT.EMULATOR-0
```
