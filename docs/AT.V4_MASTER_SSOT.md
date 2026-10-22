# AndroidTerminal V4 — Master SSOT

> **Version**: 4.0.0-v4-5
> **Date**: 2026-10-22
> **Status**: V4-4.2 Device Closure — **COMPLETE** / **DEVICE_CLOSURE = CLOSED** / V4-4.3 First-Use Auto-WD Bootstrap — **COMPLETE** / V4-5 Consumer Conformance + Architecture Enforcement — **COMPLETE** (CONSUMER_CONFORMANCE = CLOSED; ARCHITECTURE_ENFORCEMENT = CLOSED; CONSUMER_CONTRACT_GAP = NO; AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0; FORBIDDEN_DIRECT_DEPENDENCY_COUNT = 0; MODULE_DAG_CYCLE_COUNT = 0; AT_ENGINE_PUBLIC_API_CHANGE = NO)

---

## A. V4 Mission

V4 的首要目标不是增加能力，而是让以下架构在 **Gradle module / package visibility / dependency direction / composition ownership / compile-time enforcement** 层面真正成立：

```
Consumer / Product
        ↓
AT Engine Public API
        ↓
AT Engine Implementation
        ↓
Backend SPI
        ↓
Concrete Backend
        ↓
Execution Provider
```

**V3 Canonical Architecture** = **PRESERVED**（AT Engine / ADB Engine / Backend 的 canonical responsibilities 不重新讨论）。

**V3 clean architecture migration** = **VALID**（V3 从 V2 迁移 verified implementation 的过程是有效的）。

**V4_TRIGGER** =
POST_V3_CAPABILITY_PLACEMENT_DRIFT
+ CONSUMER_BOUNDARY_NOT_FULLY_PHYSICALIZED
+ COMMON_COMPOSITION_DUPLICATION

**V4 = CANONICAL_ARCHITECTURE_NORMALIZATION + PHYSICAL_BOUNDARY_CLOSURE**

**V4 不是**：
- NEW AT ARCHITECTURE
- rewrite / new execution engine / new ADB implementation / new PTY implementation
- 重新讨论 AT Engine / ADB Engine / Backend 的 canonical responsibilities

**V4 是**：
- 物理化 V3 已定义的 canonical architecture
- 修正 post-V3 capability placement drift
- 闭合 consumer boundary（Gradle module / dependency direction / compile-time enforcement）
- 消除 common composition duplication

---

## B. V3 Inherited Capabilities

以下能力全部视为 V4 migration oracle，不得重新设计：

- ONE_SHOT_EXECUTION / PERSISTENT_TERMINAL
- LOCAL_BACKEND / ADB_BACKEND
- LOCAL_REAL_PTY / ADB_REMOTE_PTY
- RAW_BYTE_IO / STDIN / STDOUT / STDERR / EXIT_CODE
- CTRL_C / RESIZE / CWD / ENV
- MULTI_SESSION / NATURAL_EXIT / EXPLICIT_CLOSE
- ADB_CONNECTION_REUSE
- WIRELESS_ADB_TLS_1_3 / ADB shell-v2
- PAIRING / PERSISTENT_PER_CONSUMER_IDENTITY
- CLI `adb shell <cmd>` / `adb shell` interactive / `adb pair <host>:<port> <code>`
- LOCAL → adb CLI → remote shell → return Local
- AdbRuntime lifecycle ownership / AdbRecoveryCoordinator recovery ownership

---

## C. Frozen Ownership

以下 ownership 在 V4 不允许改变：

| Owner | Scope |
|---|---|
| `AdbEngine` | ADB protocol |
| `AdbRuntime` | UNIQUE ADB lifecycle owner |
| `AdbRuntime` + `AdbRecoveryCoordinator` | UNIQUE ADB recovery owner |
| `AdbEngine.pair` | Pairing primitive |
| `AdbConsumerBootstrap` | First-use bootstrap implementation |
| Local backend | Local PTY |
| ADB backend / ADB Engine | Remote ADB stream |
| AT Engine | Terminal execution semantics |
| AG | Intelligence (outside AT) |

---

## D. V4 Canonical Layers

```
Layer 1: Consumer / Product Shell
  ├── :app (AT Product App)
  ├── :at-cli-consumer-host (reference consumer)
  └── future AG integration adapter

Layer 2: AT Engine Public API
  └── :at-engine-api (public consumer contract)

Layer 3: AT Engine Implementation
  └── :at-engine (engine implementation)

Layer 3/4 implementation contract:
  └── :at-engine-spi (backend SPI — contracts/interfaces ONLY)

Layer 4: Backend Adapter
  ├── :at-backend-local (Local backend — concrete implementations)
  └── :at-backend-adb (ADB backend — concrete implementations)

Layer 5: Execution Provider
  ├── Local PTY (/system/bin/sh -i)
  └── ADB Engine → adbd (:adb-engine)

Android Composition Layer (assembly root):
  └── :at-composition-android (Context / identity / AdbRuntime init / backend construction)
```

**关键区分**：
- `:at-engine-spi` = **backend SPI**（contracts/interfaces ONLY，不包含 concrete implementations）
- `:at-backend-local` / `:at-backend-adb` = **concrete backend implementations**（实现 `:at-engine-spi` 的 interfaces）
- `:at-engine` = **engine implementation**（execution orchestration / session implementation，不依赖 concrete backends）

---

## E. V4 Physical Module DAG

**Selected Candidate: B（API + SPI + Engine + Backends + Composition）**

```
:at-engine-api
  └── (no project dependencies)

:at-engine-spi
  └── implementation(:at-engine-api)

:at-engine
  ├── implementation(:at-engine-api)
  └── implementation(:at-engine-spi)

:at-backend-local
  ├── implementation(:at-engine-api)
  └── implementation(:at-engine-spi)

:at-backend-adb
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine-spi)
  └── implementation(:adb-engine)

:at-composition-android   （assembly root — ASSEMBLY-ROOT INTERNAL DEPENDENCY）
  ├── api(:at-engine-api)
  ├── implementation(:at-engine-spi)   ← V4-4.1A：从 api 降级（public surface 无 SPI 类型）
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)   ← composition 内部直接依赖（PROVIDER_WIRING_MODEL = A）

:at-cli
  └── implementation(:at-engine-api)   （ONLY — V4-4.1 CLOSED）

:app
  ├── implementation(:at-engine-api)
  ├── implementation(:at-composition-android)
  └── implementation(:at-cli)
  （NO :adb-engine / :at-backend-local / :at-backend-adb / :at-engine-spi / :at-engine
   — V4-4.1 + V4-4.1A CLOSED；backend/native 经 composition transitively 进入 runtime + APK）

:at-cli-consumer-host
  ├── implementation(:at-engine-api)
  ├── implementation(:at-composition-android)
  └── implementation(:at-cli)
  （NO :adb-engine / :at-backend-* / :at-engine-spi — V4-4.1 + V4-4.1A CLOSED）
```

**NO CYCLES** ✓

**Consumer DAG 最终态（V4-4.1A）**：

```
:app                  → :at-engine-api → :at-composition-android → :at-cli
:at-cli-consumer-host → :at-engine-api → :at-composition-android → :at-cli
:at-cli               → :at-engine-api
```

（`:at-composition-android → :adb-engine` / `:at-backend-*` 是 assembly-root internal
dependency，不是 Consumer violation；backend classes + native .so 经 composition 的
implementation 依赖 transitively 进入 Consumer runtime classpath 与 APK。）

---

## F. Allowed Dependency Rules

| Module | Allowed Dependencies |
|---|---|
| `:at-engine-api` | (none) |
| `:at-engine-spi` | `:at-engine-api` |
| `:at-engine` | `:at-engine-api`, `:at-engine-spi` |
| `:at-backend-local` | `:at-engine-api`, `:at-engine-spi` |
| `:at-backend-adb` | `:at-engine-api`, `:at-engine-spi`, `:adb-engine` |
| `:adb-engine` | (none) |
| `:at-composition-android` | `:at-engine-api`（api）, `:at-engine-spi`（impl）, `:at-engine`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine`（assembly-root internal） |
| `:at-cli` | `:at-engine-api` ONLY（V4-4.1） |
| `:app` | `:at-engine-api`, `:at-composition-android`, `:at-cli`（NO `:adb-engine` / `:at-backend-*` / `:at-engine-spi` / `:at-engine` — V4-4.1A） |
| `:at-cli-consumer-host` | `:at-engine-api`, `:at-composition-android`, `:at-cli`（NO `:adb-engine` / `:at-backend-*` / `:at-engine-spi` — V4-4.1A） |

---

## G. Forbidden Dependency Rules

| Module | Forbidden Dependencies |
|---|---|
| `:app` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-cli-consumer-host` | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| AG integration adapter | `:at-engine`, `:at-engine-spi`, `:at-backend-local`, `:at-backend-adb`, `:adb-engine` |
| `:at-engine-api` | `android.content.Context`, `AdbRuntime`, `AdbEngine`, `AdbConsumerBootstrap`, backend classes |
| `:at-engine` | `:at-backend-local`, `:at-backend-adb` (no concrete backend dependency) |

---

## H. Consumer Boundary

**Normal Product Consumer**（`:app` / `:at-cli-consumer-host` / future AG adapter）只应该看到：

- Execution request / result
- Terminal session contract
- Session state / events
- Execution target / intention
- Readiness / status
- Structured user-action-required
- Bootstrap / pairing semantic result
- Diagnostic semantic projection

**不得看到**：
- `AdbRuntime` / `AdbEngine` / `AdbConsumerBootstrap`
- `AdbConnectionRequest` / `AdbEndpoint` / `AdbInteractiveStream`
- `LocalPtySession` / `AdbOneShotBackend` / `AdbPersistentBackend` / `LocalPersistentBackend`
- `identityDir` / `Context` in runtime methods

---

## I. Composition Boundary

**Android Composition Layer**（`:at-composition-android`）职责：

- 接收 Android Context
- 决定 app-private identity location
- 初始化 AdbRuntime
- 构造 provider adapters
- 构造 Local backend
- 构造 ADB backend
- 构造 AT Engine implementation
- 返回 `:at-engine-api` contract

**不得拥有**：
- ADB protocol
- Recovery ownership
- Terminal execution semantics
- Product UI
- AG logic

---

## J. CLI Boundary

`:at-cli` 保持 **optional frontend**，职责仍然是 shell-visible `adb`。

**不是**：Local executor / ADB lifecycle owner / composition root。

**ACTUAL（V4-4.1 CLOSED）**：`:at-cli` 只依赖 `:at-engine-api` ONLY。
pairing surface 使用 CLI-owned semantic types（`ATCliPairingState` /
`ATCliPairingResult`，host 映射自 composition semantic types）；
presentation string 只在 CLI boundary（`AdbCliBridgeServer`）最终生成。

**不得直接依赖**：`:adb-engine` / `:at-backend-adb` / `:at-backend-local` /
`:at-engine-spi` / `:at-engine`（transitional 依赖已全部移除）。

---

## K. AG Boundary

- **AG Core** → NO concrete AT dependency
- **AG integration adapter** → `:at-engine-api` ONLY
- **AGA / Android Host Composition Root** → `:at-composition-android` → 构造 ATEngine → 注入 integration adapter

**AG integration 不需要**：Context / identityDir / AdbRuntime / backend classes。

---

## L. Emulator Relationship

**Terminal Emulator / ScreenState** 属于 **AT Engine domain**（Layer-3/4），不属于 UI / ADB Engine / AG。

**V4 DAG 必须预留位置，但不实现**（`AT.EMULATOR-0` 仍然暂停，直到 V4 physical closure 完成）。

---

## M. Current Phase

**V4-4.2A.1**：Validation Audit Evidence Normalization（COMPLETE）。

**V4-4.2A.1F**：Audit Encoding Finalization（COMPLETE）。

**V4-4.2B**：Validation Infrastructure Repair（COMPLETE）。B1/B2/B3 全部 REPAIRED；5 validation carriers main+androidTest 10/10 PASS；core regression 8/8 PASS；B1 targeted device 7/7 PASS。

**V4-4.2B.1**：UTF-8 Semantic Restoration（COMPLETE）。B3 CJK→ASCII workaround 修正为 Unicode escape（`\u4E2D` 等）+ byte-level split inside U+4E2D；G7 device 1/1 PASS；multibyte UTF-8 validation semantic restored。

**V4-4.2**：**COMPLETE** / **DEVICE_CLOSURE = CLOSED**（2026-10-22）。所有 REQUIRED gates PASS（C4-C6, A5, A6, A7, A8, A10, CONNECTION_REUSE, RUNTIME_OWNERSHIP, HOST_RESTART, Transport-Loss, APP_RESTART）；REQUIRED_AND_STILL_OPEN = 0；V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS。Product FIRST_USE_PAIRING_COUNT=1 / NORMAL_REPAIRING_COUNT=0；ConsumerHost FIRST_USE_PAIRING_COUNT=1 / NORMAL_REPAIRING_COUNT=0。debt1_* = EXCLUDED_VALIDATION_HARNESS。i14 harness observability repair (TEST-only)。UTP uninstall/reinstall 分类为 VALIDATION_ENVIRONMENT_ARTIFACT。

**V4-4.3**：**COMPLETE**（2026-10-22）。First-use Auto-WD permission self-grant bootstrap 闭环。PAIR_SUCCESS → READY → self-grant WRITE_SECURE_SETTINGS（via embedded ADB shell, uid=2000）→ POST_BOOTSTRAP_AUTO_WD = PASS。FIRST_USE_ONE_MANUAL_PAIRING_THEN_AUTONOMOUS_WD = YES。PRODUCTION_CHANGE_SCOPE = FIRST_USE_BOOTSTRAP_ONLY。Gate B/C/D/E 全部 PASS（direct am instrument）。

---

## N. Closed Tasks

| Task | Status |
|---|---|
| AT.V4-0 | COMPLETE（V4 baseline established / physical architecture frozen） |
| AT.V4-0.1 | COMPLETE（V4 historical / DAG / migration semantics normalization） |
| AT.V4-1 | COMPLETE（`:at-engine-api` + `:at-engine-spi` extraction；`ATEngine` interface + `DefaultATEngine` impl；SEMANTIC_DELTA=NONE） |
| AT.V4-2 | COMPLETE（`:at-composition-android` composition root；PROVIDER_WIRING_MODEL = A） |
| AT.V4-3 | ABSORBED（V4-1 + V4-2） |
| AT.V4-4 | IMPLEMENTATION_COMPLETE（consumer boundary 代码迁移；device closure 拆出为 V4-4.2） |
| AT.V4-MIGRATION-AUDIT-0 | COMPLETE（V3→V4 全量迁移审计；PASS_AT_IMPLEMENTATION_LEVEL；V4-4.1 修正文件级 count 为 MUTUALLY_EXCLUSIVE） |
| AT.V4-4.1 | COMPLETE（Consumer Semantic Type Closure；`com.adb.engine` consumer 泄漏闭合） |
| AT.V4-4.1A | COMPLETE（Final Consumer Dependency Closure；`:app` 直接 `:at-backend-*` 依赖删除；composition `api(:at-engine-spi)` → `implementation`；APK native packaging 经 composition transitive 验证；CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED） |
| AT.V4-4.2 | **COMPLETE** / **DEVICE_CLOSURE = CLOSED**（2026-10-22；所有 REQUIRED gates PASS；REQUIRED_AND_STILL_OPEN = 0；V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS） |
| AT.V4-4.3 | **COMPLETE**（2026-10-22；First-use Auto-WD permission self-grant bootstrap；POST_BOOTSTRAP_AUTO_WD = PASS；FIRST_USE_ONE_MANUAL_PAIRING_THEN_AUTONOMOUS_WD = YES） |
| AT.V4-4.2A | COMPLETE（Device Validation Readiness Audit；全量 blocker 识别；UNKNOWN=0；RECOMMENDED_NEXT=V4-4.2B） |
| AT.V4-4.2A.1 | COMPLETE（Validation Audit Evidence Normalization；method-level inventory 251 methods；CSV parser integrity PASS；mechanical recount PASS；36 gates 0 coverage gap；SSOT/Roadmap 同步） |
| AT.V4-4.2A.1F | COMPLETE（Audit Encoding Finalization；23 mojibake restored；U+FFFD=0；CSV revalidation PASS；0 semantic changes） |
| AT.V4-4.2B | COMPLETE（Validation Infrastructure Repair；B1 @Before initialize / B2 FQN fix / B3 CJK→ASCII（Kotlin CJK compiler issue）；10/10 build PASS；core 8/8 PASS；B1 device 7/7 PASS；0 production change） |
| AT.V4-4.2B.1 | COMPLETE（UTF-8 Semantic Restoration；B3 ASCII→Unicode escape + byte-level split inside U+4E2D；G7 device 1/1 PASS；multibyte UTF-8 semantic restored；0 production change） |
| AT.V4-5 | **COMPLETE**（2026-10-22；Consumer Conformance + Architecture Enforcement；`:at-consumer-conformance-host` reference consumer + `:at-architecture-gate` machine-enforced gates；8/8 conformance gates PASS；13/13 architecture tests PASS；CONSUMER_CONFORMANCE = CLOSED；ARCHITECTURE_ENFORCEMENT = CLOSED；CONSUMER_CONTRACT_GAP = NO） |

---

## O. Open Gaps

| Gap | Status |
|---|---|
| `:at-engine-api` module creation | COMPLETED（V4-1） |
| `:at-engine-spi` module creation | COMPLETED（V4-1） |
| `:at-composition-android` module creation | COMPLETED（V4-2） |
| Backend SPI implementation migration | ABSORBED（V4-1 + V4-2） |
| Consumer semantic type boundary（`:app` / `:at-cli-consumer-host` / `:at-cli` 的 `:adb-engine` 泄漏） | COMPLETED（V4-4.1） |
| Consumer 直接 concrete backend / SPI 依赖（`:app` → `:at-backend-local`/`:at-backend-adb`；composition `api(:at-engine-spi)`） | COMPLETED（V4-4.1A — CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED，Gradle + source + packaging 三维度） |
| Device closure（L1-L7 / A1-A10 / C1-C6 / H1-H7 / restart identity / runtime ownership device evidence） | **COMPLETED**（**V4-4.2** DEVICE_CLOSURE = CLOSED；所有 REQUIRED gates PASS） |
| First-use Auto-WD permission self-grant bootstrap | **COMPLETED**（**V4-4.3**；POST_BOOTSTRAP_AUTO_WD = PASS） |
| Validation infrastructure repair（B1/B2/B3 test-only blocker） | COMPLETED（**V4-4.2B**） |
| Consumer Conformance / architecture enforcement | **COMPLETED**（**V4-5**；CONSUMER_CONFORMANCE = CLOSED；ARCHITECTURE_ENFORCEMENT = CLOSED；CONSUMER_CONTRACT_GAP = NO） |
| Device parity / V4 Consumer Freeze | OPEN（V4-6） |

---

## P. Next Task

**AT.V4-1**：COMPLETED — 公共 API / SPI 骨架（创建 `:at-engine-api` + `:at-engine-spi` module，移动 public types）。

**AT.V4-2**：COMPLETED — Android Composition Root（创建 `:at-composition-android`，provider wiring closure）。

**AT.V4-3**：ABSORBED — Backend SPI implementation migration 已在 V4-1 + V4-2 完成。

**AT.V4-4**：IMPLEMENTATION_COMPLETE — Consumer boundary closure（代码迁移完成）。

**AT.V4-MIGRATION-AUDIT-0**：COMPLETE — V3→V4 全量迁移完整性审计。

**V3_FULL_MIGRATION_AUDIT** = PASS（PASS_AT_IMPLEMENTATION_LEVEL）
- MISSING_PRODUCTION_CAPABILITY = 0 / UNCLASSIFIED = 0 / UNAUTHORIZED_DROP = 0 / OWNERSHIP_REGRESSION = 0
- 文件级分类 = MUTUALLY_EXCLUSIVE（316 EXACT_UNCHANGED + 33 MIGRATED_MODIFIED + 2 REPLACED_BY_EQUIVALENT + 2 TEST_REFERENCE_ONLY + 2 SUPERSEDED_BY_STRUCTURE = 355）
- DEVICE_PARITY = NOT_EVALUATED（V4-4.2 / V4-6）

**AT.V4-4.1**：COMPLETED — Consumer Semantic Type Closure（consumer 语义类型闭合；`:app` / `:at-cli-consumer-host` / `:at-cli` 的 `:adb-engine` 依赖全部删除；composition semantic projection types 建立；ATCliExecution 结构化 pairing result；WIRE_PROTOCOL_DELTA = NONE；BUILD/TEST = PASS）。

**AT.V4-4.1A**：COMPLETED — Final Consumer Dependency Closure（`:app` 删除直接 `:at-backend-local`/`:at-backend-adb` 依赖（HISTORICAL 遗留，无 source 需求）；composition `api(:at-engine-spi)` → `implementation`（public surface 零 SPI 类型）；3 个 dead import 删除；APK 4 个 native .so 经 composition transitive packaging 验证；`CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED`（Gradle dependency + source import 双维度）；BUILD/TEST/APK = PASS）。

**AT.V4-4.2**：**COMPLETE** / **DEVICE_CLOSURE = CLOSED**（2026-10-22）— 所有 REQUIRED gates PASS（C4-C6, A5, A6, A7, A8, A10, CONNECTION_REUSE, RUNTIME_OWNERSHIP, HOST_RESTART, Transport-Loss, APP_RESTART）；REQUIRED_AND_STILL_OPEN = 0；V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS。

**AT.V4-4.3**：**COMPLETE**（2026-10-22）— First-use Auto-WD permission self-grant bootstrap。POST_BOOTSTRAP_AUTO_WD = PASS；FIRST_USE_ONE_MANUAL_PAIRING_THEN_AUTONOMOUS_WD = YES。

**AT.V4-4.2A**：COMPLETE — Device Validation Readiness Audit（全量 validation carrier / test / build / identity / observability 审计；UNKNOWN=0；8 blockers identified；NO production regression；RECOMMENDED_NEXT=V4-4.2B Validation Infrastructure Repair）。

**AT.V4-4.2A.1**：COMPLETE — Validation Audit Evidence Normalization（method-level exact inventory = 251 test methods / 26 classes / 31 files；CSV parser integrity = PASS（0 parse errors, 0 bad rows）；mechanical classification recount = PASS（SUM=251）；36 device gates 0 coverage gap 0 broken refs；blocker recount = 8（P0=0/P1=3/P2=3/P3=2）；SSOT/Roadmap 同步；B1/B2/B3 结论不变；NO new blockers）。

**AT.V4-5**：**COMPLETE**（2026-10-22）— Consumer Conformance + Architecture Enforcement。新建 `:at-consumer-conformance-host`（reference consumer，只依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli`）+ `:at-architecture-gate`（machine-enforced 架构 gates）。8/8 conformance gates PASS（direct am instrument，identity 保留）；13/13 architecture gate tests PASS（4 negative + 2 sanity + 7 production）。CONSUMER_CONFORMANCE = CLOSED；ARCHITECTURE_ENFORCEMENT = CLOSED；CONSUMER_CONTRACT_GAP = NO；AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0；FORBIDDEN_DIRECT_DEPENDENCY_COUNT = 0；FORBIDDEN_CONSUMER_IMPORT_COUNT = 0；MODULE_DAG_CYCLE_COUNT = 0；AT_ENGINE_PUBLIC_API_CHANGE = NO。

**AT.V4-4.2A.1**：COMPLETE — Validation Audit Evidence Normalization。

**AT.V4-4.2B**：COMPLETE — Validation Infrastructure Repair（B1: AppDebugInputTest @Before 加 AppRuntime.initialize(ctx)；B2: AdbTransportLossAuditTest FQN 修正；B3: ShelllessRealDeviceTest g7 CJK→ASCII（Kotlin 1.9.24 CJK compiler issue，pre-existing V3 debt）；3 test files changed / 0 production / 0 gradle / 0 manifest；build matrix 10/10 PASS；core regression 8/8 PASS；B1 targeted device 7/7 PASS）。

**AT.V4-4.2B.1**：COMPLETE — UTF-8 Semantic Restoration（B3 ASCII workaround 修正：Unicode escape `\u4E2D\u6587\u6D4B\u8BD5` + byte-level split inside U+4E2D（E4 B8 AD）offset 6；G7 device 1/1 PASS；multibyte UTF-8 validation semantic fully restored；1 test file changed / 0 production）。

**RECOMMENDED_NEXT** = AT.V4-6 — Device Parity + V4 Consumer Freeze。

**NEXT_OPEN_AUTHORIZED = NO**（等待 Controller 授权 V4-6）。

---

## Q. Version Provenance

- **V3_SOURCE_BASELINE** = `75d5832`（AT.V3: close consumer-boundary audits before V4）
- **V3_BASELINE_TAG** = `AT.V3-TO-V4-BASELINE-V1`
- **V4_START_HEAD** = `75d5832`
- **V4_REPO** = `D:\codebank\AndCode\AndroidTerminal-V4`

---

## R. Known-Good References

- `D:\codebank\AndCode\AndroidTerminal-V3`（READ_ONLY KNOWN-GOOD REFERENCE）
- `D:\codebank\AndCode\AndroidTerminal-V2`（READ_ONLY Verified Reference / Evidence / Regression Oracle / Migration Source）
- `D:\codebank\AndCode\AndroidTerminal`（legacy + SSOT 来源）
- `D:\codebank\AndCode\AndroidTerminal-Public`（known-good production 来源）
