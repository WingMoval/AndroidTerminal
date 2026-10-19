# AT → AndroidAgent Supplemental Consumer Handoff V1

> **SUPPLEMENTAL VERSIONED HANDOFF FOR CONSUMER EXECUTION / CLI / BOOTSTRAP.**
> 扩展 `AT_TO_AG_HANDOFF_V1`（historical published snapshot），**不重写** V1。
> **不是** AT_TO_AG_HANDOFF_V2。**不是** FINAL API FREEZE。**不是** binary ABI guarantee。
> 让 AG 不需要重新研究整个 AT repo 就能知道：**当前 AT 可依赖什么 consumer
> capability、不能依赖什么、禁止什么**。

---

## 1. Identity

```
HANDOFF_NAME           = AT_TO_AG_CONSUMER_HANDOFF_V1
HANDOFF_TYPE           = SUPPLEMENTAL CONSUMER CAPABILITY SNAPSHOT
SOURCE_BASELINE        = c9cee21
FREEZE_TAG             = AT.AG-CONSUMER-FREEZE-V1
BASE_HANDOFF           = AT_TO_AG_HANDOFF_V1
RELATION_TO_V1         = EXTENDS / DOES NOT REWRITE
AT_PHASE               = PRE_EMULATOR + POST_CONSUMER_BOOTSTRAP
AT_CONTRACT_STATUS     = DRAFT
FINAL_API_BINDING      = NO
```

本 Handoff 是 `AT_TO_AG_HANDOFF_V1` 的 **supplemental extension**，只覆盖
V1（base `700ca3b`）**没有**的 consumer capability：reusable `:at-cli` /
independent consumer / per-consumer identity / `AdbConsumerBootstrap` /
unified pairing / Product UI pairing / CLI `adb pair`。

V1 仍然有效（execution baseline / architecture / AG Core 边界）。**V1 未修改**。

## 2. Core Architecture（继承 V1，扩展 consumer 边界）

```
AG = Intelligence
AT = Execution
```

V1 已确立 AG Core **不**直接依赖 concrete AT implementation（concrete Tool /
Execution Adapter 在 AG Core 外做 AT composition）。本 supplemental handoff
在此边界内补充 **consumer capability 层**：

```
AGA / future Host
    ↓
Concrete Tool / AT Integration Boundary        （AG Core 外）
    ↓
AT reusable consumer capability
    ↓
AT Engine / :at-cli / AdbConsumerBootstrap
    ↓
Backend (local / adb) / AdbRuntime / ADB Engine
    ↓
Local / adbd
```

**AG Core 与 Agent Runtime 不得直接依赖 concrete AT implementation。**

## 3. What Future AG MAY Assume

```
1.  AT provides reusable Local terminal execution.
2.  AT provides reusable shell-visible adb CLI（:at-cli）。
3.  `adb shell ...` reaches shell UID through AT（uid=2000(shell)）。
4.  interactive `adb shell`（Remote PTY）is verified.
5.  `adb pair <host>:<port> <code>` exists.
6.  AdbConsumerBootstrap exists as the reusable bootstrap facade.
7.  each embedding consumer uses its own app-private identity.
8.  bootstrap can structurally report PAIRING_REQUIRED.
9.  pairing discovery / pairing / reconnect are AT-owned.
10. AdbRuntime is the unique lifecycle owner.
11. no external adb binary is required.
12. no adb server 5037 is required.
13. Consumer does not depend on AndroidTerminal :app.
14. Product UI is not required by an embedding consumer.
15. OpenCode is not a runtime / product dependency.
```

> 写的是 **semantic capability exists**（能力存在），**不是** final API guarantee。
> 不要把某个当前 Kotlin method signature 误写成 final frozen API。

## 4. What Future AG MUST NOT Assume

```
- final Kotlin signatures are frozen
- AT Contract is final
- Emulator exists
- ScreenState exists
- Screen Snapshot exists
- cursor semantics exist
- xterm semantics exist
- renderer exists
- Human-Agent shared terminal is frozen
- Handoff V2 exists
- current composite-build mechanism is permanent ABI
- `adb pair <code>` shorthand exists        （DEFERRED）
- multiple pairing endpoint UI exists        （DEFERRED）
```

## 5. Explicit AG Forbidden Patterns

**AG 禁止**：

```
- AG Core -> AT implementation
- AG Runtime -> AT implementation
- copy AndroidTerminal private adb key
- depend on :app
- directly own pairing protocol
- directly call AdbEngine.pair as its product integration path
- implement SPAKE2
- implement TLS pairing
- create second AdbRuntime
- create second recovery owner
- launch external adb binary
- launch adb server 5037
- maintain its own parallel AT terminal stack
```

AG 消费 AT 的 consumer capability 必须经 **concrete Tool / AT Integration
Boundary**（AG Core 外），使用 AT 提供的 `AdbConsumerBootstrap` facade，
**不**自己实现 pairing 协议、**不**复制 key、**不**创建第二 runtime。

## 6. Consumer Capability Snapshot

### 6.1 Reusable Terminal Environment

```
REUSABLE_AT_CLI            = VERIFIED
LOCAL_TERMINAL_TO_ADB_CLI  = VERIFIED
LOCAL_SHELL_PRINCIPAL      = CONSUMER APP UID
ADB_SHELL_PRINCIPAL        = uid=2000(shell)
REMOTE_EXIT_RETURNS_TO_LOCAL = VERIFIED
LOCAL_SESSION_RECREATED    = NO
```

### 6.2 Consumer Isolation

```
CONSUMER_DEPENDS_ON_APP   = NO
AT_CLI_DEPENDS_ON_APP     = NO
EXTERNAL_ADB_BINARY       = NO
ADB_SERVER_5037           = NO
```

### 6.3 Bootstrap

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

### 6.4 Canonical Consumer Terminal Path（VERIFIED）

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

Local Terminal 和 ADB shell **不是**两套 Consumer-owned execution systems；
ADB 切换发生在 **AT execution subsystem 内**。

### 6.5 Honest Bootstrap State（冻结语义）

```
无 identity
    → PAIRING_REQUIRED（有证据：无 key 材料）
已有 identity + generic connect failure
    → NOT automatically PAIRING_REQUIRED
    → CONNECT_FAILED / runtime diagnostic
```

**不得**告诉 AG「连接失败就要求重新配对」。Boolean connect 失败不是 auth 证据。

### 6.6 CLI Capability

**已验证交给 Consumer**：

```
adb shell [command...]
adb shell                                  （interactive Remote PTY）
adb pair <host>:<port> <code>
```

**DEFERRED / 未实现**：`adb pair <code>` shorthand / MULTIPLE endpoint UI
chooser。**不得宣传** push / pull / install / forward / reverse / devices
（当前 `:at-cli` 未实现）。

## 7. Module Availability（CAPABILITY SNAPSHOT）

`c9cee21` consumer-relevant modules（以 `settings.gradle.kts` 为准）：
`:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine` /
`:at-cli` / `:app` / `:at-cli-consumer-host`。

**区分**：CAPABILITY SNAPSHOT（冻结的能力存在性）vs CURRENT PHYSICAL MODULE
CONSUMPTION（AG 当前如何物理消费）。**不得**声称这些 Gradle module / API
是永久 ABI frozen。

## 8. Evidence Map（不复制巨量测试日志）

| capability | evidence source | verdict |
|---|---|---|
| Execution baseline | `AT.PRE-EMULATOR-FREEZE.md`（base `700ca3b`） | VERIFIED |
| Reusable CLI + Local -> adb | `AT.CLI-CONSUMER-0.md` | VERIFIED |
| Local -> adb shell -> uid=2000 | `AT.CLI-CONSUMER-0.md` §4.1（C2/C4） | VERIFIED |
| Per-consumer identity + restart reuse | `AT.CLI-CONSUMER-0.md` §4.3 + `AT.CONSUMER-BOOTSTRAP-0.md` §3.4 | VERIFIED |
| AdbConsumerBootstrap + pairing | `AT.CONSUMER-BOOTSTRAP-0.md` §3.1（B1-B5） | VERIFIED |
| CLI adb pair | `AT.CONSUMER-BOOTSTRAP-0.md` §3.2（P1-P3） | VERIFIED |
| Product UI pairing | `AT.CONSUMER-BOOTSTRAP-0.md` §3.5（U1-U3） | VERIFIED |
| Pairing count 1 / no :app / no second runtime | `AT.CONSUMER-BOOTSTRAP-0.md` §3.3 + `AT.CLI-CONSUMER-0.md` §4.2/§5 | VERIFIED |

## 9. Next Formal Main Sync

```
NEXT_AT_MAINLINE = AT.EMULATOR-0

NEXT_FORMAL_MAIN_HANDOFF =
AT.EMULATOR-0 COMPLETE
    ↓
AT.CONTRACT-1 REVIEW
    ↓
Controller authorization
    ↓
AT_TO_AG_HANDOFF_V2        （届时创建）
```

本 supplemental handoff **不**占用 V2 名称。AG 当前消费
`AT_TO_AG_CONSUMER_HANDOFF_V1`（FREEZE_TAG = `AT.AG-CONSUMER-FREEZE-V1`）。

## 10. Consistency Gate（H1-H14）

| # | 检查 | 结果 |
|---|---|---|
| H1 | SOURCE_BASELINE == c9cee21 | ✅（§1） |
| H2 | old freeze tag unchanged | ✅（AT.PRE-EMULATOR-FREEZE-V1 -> 7ccb1bf，未动） |
| H3 | new handoff does not claim Final API freeze | ✅（§1 FINAL_API_BINDING=NO） |
| H4 | new handoff does not claim Emulator/ScreenState exists | ✅（§4 = NO） |
| H5 | new handoff records :at-cli reusable | ✅（§6.1） |
| H6 | new handoff records Local -> adb shell principal transition | ✅（§6.1 ADB_SHELL_PRINCIPAL=uid=2000 + §6.4） |
| H7 | new handoff records AdbConsumerBootstrap | ✅（§6.3） |
| H8 | new handoff records per-consumer identity | ✅（§6.3 IDENTITY_OWNER=PER_CONSUMER_APP_PRIVATE） |
| H9 | new handoff forbids copied AT App key | ✅（§5 copy AndroidTerminal private adb key 禁止） |
| H10 | new handoff forbids second AdbRuntime/recovery owner | ✅（§5 create second AdbRuntime / recovery owner 禁止） |
| H11 | new handoff does not create Handoff V2 | ✅（§1/§9，V2 保留） |
| H12 | new handoff keeps next formal main sync = POST AT.EMULATOR-0 + CONTRACT-1 REVIEW | ✅（§9） |
| H13 | consumer capabilities are all backed by existing evidence | ✅（§8 evidence map，每项映射到 AT.CLI-CONSUMER-0 / AT.CONSUMER-BOOTSTRAP-0 / PRE-EMULATOR-FREEZE） |
| H14 | production code changes = 0 | ✅（docs-only） |

**H1-H14 全 PASS → AT_TO_AG_CONSUMER_HANDOFF_V1 = PUBLISHED。**
