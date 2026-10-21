# AndroidTerminal V4 — Roadmap

> **Date**: 2026-10-21
> **Status**: V4-0 COMPLETE / V4-1 COMPLETE / V4-2 COMPLETE / V4-4 IMPLEMENTATION_COMPLETE / V4-4.1 COMPLETE / V4-4.1A COMPLETE / V4-4.2 OPEN（Device Closure）

---

## V4 Phases

### AT.V4-0：基线建立与物理架构冻结

**Status**: COMPLETE

**Scope**:
- V3 transition closeout（docs-only commit + baseline tag）
- V4 repository creation（local git clone --no-hardlinks）
- V4 physical architecture design（Candidate A/B 比较 + 选择）
- V4 canonical DAG freeze
- V4 Master SSOT / Handoff / Architecture Freeze / Roadmap 创建

**Deliverables**:
- `AT.V4_MASTER_SSOT.md`
- `AT_V3_TO_V4_HANDOFF.md`
- `AT.V4-0_Physical_Architecture_Freeze.md`
- `AT.V4_ROADMAP.md`

**Gate**:
- V3 production source parity = PASS
- V4 worktree = CLEAN
- V4 HEAD = V3 transition HEAD
- V4 baseline tag = `AT.V3-TO-V4-BASELINE-V1`

---

### AT.V4-1：公共 API / SPI 骨架

**Status**: COMPLETE

**第一目标**：**PHYSICAL EXTRACTION OF EXISTING VERIFIED CONTRACTS**（只移动 V3 已存在的 verified contracts，不设计新 public semantic API）。

**Scope**:
- 创建 `:at-engine-api` module（pure contract / facade）
- 创建 `:at-engine-spi` module（backend SPI — **contracts/interfaces ONLY**）
- 移动 V3 已存在的 public execution/session contract 到 `:at-engine-api`：
  - `ATEngine`（facade）
  - `ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome`
  - `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`
  - `Launch` / `ATOpenSessionResult`
- 移动 V3 已存在的 backend SPI 到 `:at-engine-spi`：
  - `ATOneShotBackend`
  - `ATPersistentBackend`
- **不得在 V4-1 实现**（PROVISIONAL / DESIGN_REQUIRED）：
  - `ATProviderControl` / `ATStatus` / `ATPairingResult` / `ATPairingDiscovery` / `ATDiagnostic`
  - prepare/status/control-plane 新接口
- 更新 `settings.gradle.kts`（新增 `:at-engine-api` / `:at-engine-spi`）
- 更新 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli` 的 `build.gradle.kts`（依赖新 module）
- 验证：`:at-engine-api` 不包含 `:adb-engine` / `:at-backend-*` types
- 验证：`:at-engine-spi` 不包含 Android Context / ADB internals / concrete implementations

**Deliverables**:
- `:at-engine-api` module（Kotlin source + build.gradle.kts）
- `:at-engine-spi` module（Kotlin source + build.gradle.kts，**contracts/interfaces ONLY**）
- 更新后的 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli`（依赖新 module）
- 更新后的 `settings.gradle.kts`

**Gate**:
- `:at-engine-api` / `:at-engine-spi` 可以正常构建
- `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:at-cli` 可以正常构建（依赖新 module）
- `:at-engine-api` 不包含 forbidden types
- `:at-engine-spi` 不包含 Android Context / ADB internals / concrete implementations
- **V4-1 不引入新 public semantic API**（只物理迁移 existing verified contracts）

---

### AT.V4-2：Android Composition Root

**Status**: COMPLETE

**Scope**:
- 创建 `:at-composition-android` module（Android composition root）
- 实现 `ATAndroid.create(context, config)`（返回 `ATEngine`）
- `ATAndroid.create()` 内部：
  - 决定 app-private identity location（根据 Context）
  - 初始化 `AdbRuntime`（`AdbRuntime.initialize(context)`）
  - 构造 Local backend（`LocalOneShotBackend` / `LocalPersistentBackend`）
  - 构造 ADB backend（`AdbOneShotBackend` / `AdbPersistentBackend`）
  - 构造 AT Engine implementation（`ATEngine` with backends）
  - 返回 `ATEngine`（`:at-engine-api` contract）
- 更新 `:at-composition-android` 的 `build.gradle.kts`（依赖 `:at-engine-api` / `:at-engine` / `:at-backend-local` / `:at-backend-adb`）
- 验证：`:at-composition-android` 可以正常构建
- 验证：`ATAndroid.create()` 可以正确 wiring Local / ADB backends

**Deliverables**:
- `:at-composition-android` module（Kotlin source + build.gradle.kts）
- `ATAndroid` class（composition entrypoint）
- 更新后的 `settings.gradle.kts`

**Gate**:
- `:at-composition-android` 可以正常构建
- `ATAndroid.create()` 可以正确 wiring Local / ADB backends
- `ATAndroid.create()` 返回的 `ATEngine` 可以执行 one-shot / persistent 命令

---

### AT.V4-3：Local / ADB Backend 迁移 + Injection

**Status**: ABSORBED_BY_V4_1_AND_V4_2

**Note**: V4-3 的 scope 已在 V4-1 + V4-2 中完成：
- V4-1: Backend SPI extraction（`:at-engine-spi` 创建，backend modules 依赖 SPI）
- V4-2: Android composition root（`:at-composition-android` 创建，backend injection）

**RECOMMENDED_NEXT** = AT.V4-4

---

### AT.V4-4：Consumer Boundary Closure

**Status**: IMPLEMENTATION_COMPLETE（device closure 拆出为 AT.V4-4.2）

**Scope** (actual):
- 创建 `ATAndroidAdbControl`（consumer semantic facade，PROJECTION_ONLY）
- `ATAndroidComposition` 增加 `adbControl` 字段
- 简化 `ATCliExecution` SPI（删除 `adbIdentityDir()` / `consumerBootstrap()`，增加 `pairingState()` / `pair()` / `pairDiscovered()`）
- `:at-cli` 不再依赖 `:adb-engine`（AdbPairingResultV2 处理移到 host 侧）
- `:app` 迁移到使用 `ATAndroidComposition`（删除 backend constructor / DefaultATEngine）
- `:at-cli-consumer-host` 迁移到使用 `ATAndroidComposition`
- `:app` / `:at-cli-consumer-host` 保留 TRANSITIONAL `:adb-engine` 依赖（for AdbRuntimeState/AdbBootstrapState types）
- 验证：build + unit tests PASS
- 验证：static import audit PASS（no forbidden imports）
- Device smoke: BLOCKED_BY_ENVIRONMENT

**Deliverables**:
- `ATAndroidAdbControl`（:at-composition-android）
- 简化后的 `ATCliExecution` SPI（:at-cli）
- 迁移后的 `:app` / `:at-cli-consumer-host`（使用 composition）
- 更新后的 Gradle 依赖

**Gate**:
- Build: PASS
- Unit tests: PASS
- Static import audit: PASS
- Device smoke: PENDING（device not connected）

---

### AT.V4-MIGRATION-AUDIT-0：V3 → V4 全量迁移完整性审计

**Status**: COMPLETE

**Scope**（docs/evidence only，无 production/gradle/test 修改）:
- 基于 git tree（V3 `75d5832` / V4 `84936f8`）逐层审计：module / production / native / resource / test / docs
- 34 项 capability（handoff 26 项 + 补充审计项）逐项验证 V4 实现
- 专项审计：bootstrap/identity 分类规则 / recovery lifecycle / CLI full path / Human UI / DebugBridge / test host / evidence-freeze

**Verdict**:
- V3_FULL_MIGRATION_PARITY = PASS_AT_IMPLEMENTATION_LEVEL
- MISSING = 0 / UNCLASSIFIED = 0 / UNAUTHORIZED_DROP = 0 / OWNERSHIP_REGRESSION = 0
- DEVICE_PARITY = NOT_EVALUATED

**Deliverables**:
- `docs/audit/AT.V4-MIGRATION-AUDIT-0_V3_to_V4_Full_Migration_Audit.md`
- `docs/audit/AT.V4-MIGRATION-AUDIT-0_V3_V4_Source_Mapping.csv`
- `docs/audit/AT.V4-MIGRATION-AUDIT-0_Capability_Matrix.csv`

**RECOMMENDED_NEXT** = AT.V4-4.1（Consumer Semantic Type Closure）

---

### AT.V4-4.1：Consumer Semantic Type Closure

**Status**: COMPLETE

**Scope**（actual）:
- 在 `:at-composition-android` 建立 Consumer-facing ADB semantic types（PURE MAPPING，无新状态机）：
  `ATAdbReadinessState` / `ATAdbBootstrapState` / `ATAdbDiscoveredService` / `ATAdbPairingDiscovery` / `ATAdbPairingResult` / `ATAdbLifecycleListenerHandle`
- `ATAndroidAdbControl` public surface 收口（零 `com.adb.engine.*` 类型）
- 闭合 `:app` / `:at-cli-consumer-host` 的 transitional `:adb-engine` 依赖（Gradle 删除）
- `:at-cli` 删除全部 transitional 依赖（`:at-engine-spi` / `:at-engine` / `:at-backend-*` / `:adb-engine`），只保留 `:at-engine-api`
- PairActivity / AppRuntime / AtCliConsumerHost 收口到 composition 语义（移除 provider concrete type 直接持有）
- `ATCliExecution` 结构化 pairing 结果（CLI-owned `ATCliPairingState` / `ATCliPairingResult`；single formatter 在 CLI boundary）
- Consumer test 迁移（11 个 app androidTest + AtCliConsumerTest 零 `com.adb.engine` import）
- Migration audit 文件级 count 修正为 MUTUALLY_EXCLUSIVE（= 355）
- WIRE_PROTOCOL_DELTA = NONE / SEMANTIC_DELTA = NONE / ATEngine_API_CHANGED = NO

**Gate**:
- Build: PASS（全量 assembleDebug + 10 module test）
- Android test compile: PASS
- Consumer import gate: `com.adb.engine` import = 0（main + test）
- Composition public API leak gate: 0
- Device: NOT_EVALUATED（留给 V4-4.2）

**Deliverables**:
- `docs/implementation/AT.V4-4.1_Consumer_Semantic_Type_Closure.md`
- 更新后的 `AT.V4_MASTER_SSOT.md` / `AT.V4_ROADMAP.md` / `AT.V4-MIGRATION-AUDIT-0_V3_to_V4_Full_Migration_Audit.md`（count 修正）

**RECOMMENDED_NEXT** = AT.V4-4.1A（Final Consumer Dependency Closure）

---

### AT.V4-4.1A：Final Consumer Dependency Closure

**Status**: COMPLETE

**Scope**（actual）:
- `:app` 删除直接 `implementation(:at-backend-local)` / `implementation(:at-backend-adb)`
  （HISTORICAL 遗留：V3 无 composition 时 app 直接构造 backend；V4 后零 source 需求）
- `:at-composition-android` 的 `api(:at-engine-spi)` → `implementation(:at-engine-spi)`
  （public surface 零 SPI 类型：`ATAndroidComposition` 只暴露 `ATEngine` + composition 类型）
- `:at-engine-api` 保持 `api`（`ATAndroidComposition.localEngine/adbEngine` 是 public `ATEngine` 属性，consumer 需 compile 可见）
- 删除 3 个 androidTest dead import（`com.at.engine.ATPersistentBackend`，V3 遗留失效路径）
- APK native packaging 验证：`libat_local_backend.so` / `libadbd_engine.so` / `libatcli_env.so` / `libatapp_env.so` 全部经 composition transitive 进入 APK
- Gradle dependency graph 证据：`:app` debugCompileClasspath 顶级仅 `:at-engine-api` / `:at-composition-android` / `:at-cli`

**Gate**:
- Build: PASS（全量 assembleDebug + 9 module test）
- Android test compile: PASS
- Consumer forbidden import: 0（spi / backend.* / adb.engine / engine.impl）
- Consumer forbidden direct dep: 0
- Composition public SPI/backend/adb leak: 0
- APK 4 个 native .so: 全部 YES
- Device: NOT_EVALUATED（留给 V4-4.2）

**Deliverables**:
- `docs/implementation/AT.V4-4.1A_Final_Consumer_Dependency_Closure.md`
- 更新后的 `AT.V4_MASTER_SSOT.md` / `AT.V4_ROADMAP.md`

**RECOMMENDED_NEXT** = AT.V4-4.2A（Device Validation Readiness Audit）

---

### AT.V4-4.2A：Device Validation Readiness Audit

**Status**: COMPLETE

**Scope**:
- 全量 validation carrier build matrix（5 modules × main + androidTest）
- 全量 test inventory（31 files / ~200 methods / V3 vs V4 parity）
- AppDebugInputTest 根因分类（V4_TEST_HARNESS_REGRESSION；first bad commit 84936f8）
- test-host / shellless-host 编译 blocker 根因 + introduction commit
- Runtime init contract（PRODUCT + TEST）
- Identity ownership matrix（5 packages）
- First-use pairing paths
- Destructive test identification（5 tests）
- Device preconditions
- Connection reuse + runtime ownership observability
- V3/V4 diff evidence（20 modified test files）
- Device gate coverage matrix（34 gates）

**Gate**:
- UNKNOWN_COUNT = 0
- ALL_BUILD_BLOCKERS_IDENTIFIED = YES
- ALL_IDENTITY_PRECONDITIONS_IDENTIFIED = YES
- ALL_DESTRUCTIVE_TESTS_IDENTIFIED = YES
- ALL_V4_4_2_GATES_HAVE_VALIDATOR_MAPPING = YES

**Deliverables**:
- `docs/audit/AT.V4-4.2A_Device_Validation_Readiness_Audit.md`
- `docs/audit/AT.V4-4.2A_Test_Harness_Matrix.csv`
- `docs/audit/AT.V4-4.2A_Device_Gate_Coverage_Matrix.csv`
- `docs/audit/AT.V4-4.2A_Blocker_Matrix.csv`
- `docs/evidence/at_v4_4_2a/`（8 evidence files）

**RECOMMENDED_NEXT** = AT.V4-4.2A.1（Validation Audit Evidence Normalization）

---

### AT.V4-4.2A.1：Validation Audit Evidence Normalization

**Status**: COMPLETE

**Scope**:
- Method-level exact test inventory（251 methods / 26 classes / 31 files）
- CSV parser integrity validation（strict parsing; 0 errors）
- Mechanical classification recount（SUM = 251; UNKNOWN = 0）
- V3/V4 method-level parity（246 migrated + 5 V4-new = 251）
- 36 device gates coverage validation（0 uncovered; 0 broken refs）
- Blocker recount（8 blockers; P0=0/P1=3/P2=3/P3=2）
- SSOT/Roadmap normalization
- B1/B2/B3 结论确认不变

**Gate**:
- EXACT_TEST_METHOD_TOTAL = MATRIX_DATA_ROW_COUNT = 251
- SUM_PRIMARY_CLASSIFICATIONS = 251
- UNKNOWN_COUNT = 0
- CSV_PARSE_ERROR_COUNT = 0
- BROKEN_VALIDATOR_REFERENCE_COUNT = 0
- UNCOVERED_GATE_COUNT = 0
- ORPHAN_BLOCKER_COUNT = 0
- ENCODING_ERROR_COUNT = 0

**Deliverables**:
- `docs/audit/AT.V4-4.2A.1_Evidence_Normalization.md`
- Updated: `AT.V4-4.2A_Test_Harness_Matrix.csv`（251 rows, method-level）
- Updated: `AT.V4-4.2A_Device_Gate_Coverage_Matrix.csv`（36 gates）
- Updated: `AT.V4-4.2A_Blocker_Matrix.csv`（8 blockers）
- `docs/evidence/at_v4_4_2a_normalization/`（normalization evidence）

**RECOMMENDED_NEXT** = AT.V4-4.2A.1F（Audit Encoding Finalization）

---

### AT.V4-4.2A.1F：Audit Encoding Finalization

**Status**: COMPLETE

**Scope**:
- Formal audit artifact UTF-8 strict decode validation（7 files）
- Mojibake detection + restoration（23 occurrences: U+922B→→, U+9225→—）
- U+FFFD scan（0）
- CSV strict parser revalidation（0 errors, 0 bad rows）
- Frozen count verification（251/36/8 unchanged）
- Zero semantic changes

**Gate**:
- UTF8_STRICT_DECODE_FAIL_COUNT = 0
- U_FFFD_COUNT = 0
- KNOWN_MOJIBAKE_OCCURRENCE_COUNT = 0
- CSV_PARSE_ERROR_COUNT = 0
- FROZEN_COUNT_MISMATCH_COUNT = 0
- SEMANTIC_CHANGE_COUNT = 0

**RECOMMENDED_NEXT** = AT.V4-4.2B（Validation Infrastructure Repair）

---

### AT.V4-4.2B：Validation Infrastructure Repair

**Status**: COMPLETE

**Scope**:
- B1: AppDebugInputTest 加 `AppRuntime.initialize(ctx)`（@Before，test-only）
- B2: AdbTransportLossAuditTest.kt:77 FQN 修正（1 行，test-only）
- B3: ShelllessRealDeviceTest.kt g7 CJK→ASCII（Kotlin 1.9.24 CJK compiler issue，pre-existing V3 debt）
- 不修改 production code / Gradle / Manifest
- 修复后 build matrix 全量 PASS

**Actual**:
- B1 fixed / B2 fixed / B3 fixed
- 5 validation carriers main+androidTest 10/10 PASS
- Core regression 8/8 PASS
- B1 targeted device 7/7 PASS
- 0 production change / 0 Gradle / 0 Manifest

**Gate**:
- 5 modules × androidTest = ALL PASS
- PRODUCTION_CHANGED = NO
- SEMANTIC_DELTA = NONE

**RECOMMENDED_NEXT** = AT.V4-4.2B.1（UTF-8 Semantic Restoration）

---

### AT.V4-4.2B.1：UTF-8 Semantic Restoration

**Status**: COMPLETE

**Scope**:
- B3 ASCII workaround 修正为 Unicode escape（`\u4E2D` 等）
- Byte-level split inside U+4E2D（E4 B8 AD）
- 恢复 multibyte UTF-8 validation semantic
- 不修改 production code / Gradle / Manifest

**Actual**:
- Unicode escape used（source ASCII-safe）
- Runtime multibyte UTF-8 restored（中文测试）
- Byte-level split inside U+4E2D at offset 6
- G7 targeted device 1/1 PASS
- 0 production change

**Gate**:
- SHELLLESS_MAIN_BUILD = PASS
- SHELLLESS_ANDROIDTEST_BUILD = PASS
- G7_DEVICE_RESULT = PASS
- PRODUCTION_CHANGED = NO

**RECOMMENDED_NEXT** = V4-4.2 RETRY（Device Closure）

---

### AT.V4-4.2：Device Closure

**Status**: IN_PROGRESS — IDENTITY RECONCILED / closure gates PARTIAL

**Identity reconciliation (controller-accepted):**
- `AT_CANONICAL_ADB_IDENTITY_COUNT = 1`（Product `files/adb_identity`, fingerprint `cc7a9884...`）
- `AT_FIRST_USE_PAIRING_COUNT = 1`, `AT_NORMAL_REPAIRING_COUNT = 0`
- `V4_PRODUCT_IDENTITY_REGRESSION = NO`
- `V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS`
- `debt1_e4` / `debt1_cont` = `VALIDATION_ONLY_FRESH_IDENTITY_SCENARIOS` → `EXCLUDED_VALIDATION_HARNESS`（`MULTI_PAIRING_REQUIREMENT = VALIDATION_HARNESS_OVERREACH`）

**Scope**（本轮已执行 + 证据）:
- ✅ Product Local L1-L7 (7/7 PASS, UI evidence)
- ✅ Product ADB A1-A10 (10/10 PASS, UI + DebugBridge evidence)
- ✅ Shellless 15/15 (15/15 PASS, XML persisted: `17_ShelllessRealDeviceTest.xml`)
- ✅ CLI C1-C3 (coreFunctionalGates PASS) + **C4-C6** (i2/i4/i6 PASS, `16_AdbCliInteractiveTest_app.xml`)
- ✅ **A5 (Ctrl-C)** i7 PASS, **A10 (natural exit)** i10 PASS, **CONNECTION_REUSE** i13 PASS (同一 adb_identity, XML persisted)
- ✅ APP_RESTART identity (fingerprint before==after, no re-pair)
- ✅ RUNTIME_OWNERSHIP (sharedRuntimeEvidence PASS)
- ✅ DebugBridge parity DBG1-6 (hello/list/attach/write-read/detach/pong)
- ⚠️ HOST_RESTART — ConsumerHost restart-reuse PASS this round (Gradle) 但 XML 被后续运行覆盖，未持久化 → evidence-persistence gap
- ❌ Transport Loss (I14) — FAIL (output-capture harness gap, 连接已建立但 post-loss marker 未捕获)
- ❌ A6 (resize i8) / A7-A8 (multi-session i15) — secondary FAIL (harness capture); primary `ProgrammaticAdbConsumerTest` 本轮未重跑
- ⊘ ADB_ONLY debt1_* — EXCLUDED_VALIDATION_HARNESS (raw 14/27 FAIL 不变, 非 Product blocker)

**Gate**:
- 单一 identity 语义 = 完整验证 (parity PASS)
- C4-C6 / A5 / A10 / CONNECTION_REUSE / RUNTIME_OWNERSHIP = PASS-with-evidence
- **未达 "no REQUIRED item remains open"**：Transport-Loss + resize/multi-session primary validators 仍 open（harness/observability + evidence-persistence 缺口，非 identity/architecture 回归）
- **V4-4.2 NOT marked COMPLETE**（见 `15_final_gate_reconciliation.md`）

---

### AT.V4-5：Consumer Conformance + Architecture Enforcement

**Status**: OPEN

**Scope**:
- 创建 `:at-consumer-conformance-host` module（只依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli` optional）
- `:at-consumer-conformance-host` 覆盖：
  - Local one-shot / Local persistent
  - ADB one-shot / ADB persistent
  - Readiness / Bootstrap / Identity
  - CLI（shell-visible adb）
- 建立 Gradle dependency rules（验证 Consumer 不依赖 forbidden modules）
- 建立 architecture test（验证 `:at-engine-api` 不包含 forbidden types + DAG 无环）
- 验证：`:at-consumer-conformance-host` 可以正常构建 + 运行 Local / ADB / CLI 全路径

**Deliverables**:
- `:at-consumer-conformance-host` module（Kotlin source + build.gradle.kts）
- Gradle dependency rules（forbidden dependencies）
- Architecture test（public signature leak test + DAG cycle test）

**Gate**:
- `:at-consumer-conformance-host` 可以正常构建
- `:at-consumer-conformance-host` 只依赖 `:at-engine-api` + `:at-composition-android` + `:at-cli`
- Gradle dependency rules 验证 `:app` / `:at-cli-consumer-host` / `:at-consumer-conformance-host` 不依赖 forbidden modules
- Architecture test 验证 `:at-engine-api` 不包含 forbidden types + DAG 无环

---

### AT.V4-6：Device Parity + V4 Consumer Freeze

**Status**: OPEN

**Scope**:
- 真机全量 parity 验证（V3 vs V4）：
  - Local one-shot / Local persistent
  - ADB one-shot / ADB persistent
  - CLI `adb shell` / `adb shell` interactive / `adb pair`
  - Pairing / Bootstrap / Identity
  - Recovery / Connection reuse
  - Multi-session / Resize / Ctrl-C
- 发布 V4 consumer handoff（`AT_V4_CONSUMER_HANDOFF.md`）
- 发布 V4 AAR bundle（`at-engine-api-debug.aar` + 其他 AAR）
- 冻结 V4 consumer boundary（`AT.V4-CONSUMER-FREEZE-V1` tag）

**Deliverables**:
- 真机 parity 验证报告
- `AT_V4_CONSUMER_HANDOFF.md`
- V4 AAR bundle
- `AT.V4-CONSUMER-FREEZE-V1` tag

**Gate**:
- 真机全量 parity = PASS（V3 vs V4 语义 delta = NONE）
- V4 consumer handoff 发布
- V4 AAR bundle 发布
- V4 consumer boundary frozen

---

## Post-V4 Phases（V4 Physical Closure 后）

| Phase | Scope | Status |
|---|---|---|
| `AT.EMULATOR-0` | Terminal Emulator / ScreenState（AT Engine domain） | DEFERRED（直到 V4 physical closure 完成） |
| `AT.ARTIFACT-BUNDLE-0` | Maven publication / AAR bundle redesign | DEFERRED |
| `AG.INTEGRATION-0` | AG integration adapter（只依赖 `:at-engine-api`） | DEFERRED |
| `AT.CONTRACT-1` | AT Contract freeze（final API） | DEFERRED（AT Contract 仍 DRAFT） |

---

## Migration Principle

**COPY / MOVE VERIFIED IMPLEMENTATION**，NOT REIMPLEMENT FROM MEMORY。

每一个 capability 迁移都必须：

```
V3 source reference
  → V4 implementation
  → static parity
  → build
  → targeted test
  → eventual device parity
```

**禁止**"顺便优化算法"。

---

## Parity Oracle

V3 是 V4 migration oracle。

所有 V4 functional task 必须能回答：

```
V3 reference path = ...
V4 path = ...
semantic delta = NONE / EXPLICIT
```

如果 `semantic delta != NONE`，必须单独授权。
