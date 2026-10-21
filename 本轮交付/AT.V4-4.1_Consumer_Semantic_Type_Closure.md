# AT.V4-4.1 — Consumer Semantic Type Closure

> **Date**: 2026-10-21
> **Scope**: Consumer 语义类型闭合 / 最后 :adb-engine 泄漏清理
> **TYPE**: ARCHITECTURAL BOUNDARY CLOSURE / SEMANTIC PROJECTION / DEPENDENCY CLEANUP / STATIC+BUILD VERIFICATION
> **DEVICE_TEST**: NO（本轮禁止；留给 AT.V4-4.2）

---

## 1. Baseline

| Item | Value |
|---|---|
| V4_REPO | `D:\codebank\AndCode\AndroidTerminal-V4` |
| V3_REPO (READ-ONLY) | `D:\codebank\AndCode\AndroidTerminal-V3` |
| START_HEAD | `05f317a`（AT.V4-MIGRATION-AUDIT-0） |
| START_WORKTREE | CLEAN |
| V3_BASELINE | `75d5832`（tag `AT.V3-TO-V4-BASELINE-V1`） |
| V3_FULL_MIGRATION_PARITY | PASS_AT_IMPLEMENTATION_LEVEL（继承，不重开） |

本轮不承担任何 V3 capability 补迁；只处理 **V4_CURRENT_BOUNDARY_DEBT**。

## 2. Existing Boundary Debt（V4-4 遗留）

- `:app` 直接依赖 `:adb-engine`（`AdbRuntimeState` / `AdbBootstrapState` / `AdbPairingResultV2` / `AdbRuntime` / `AdbEngine` / `AdbConsumerBootstrap` / `AdbRuntimeDiagnostic`）。
- `:at-cli-consumer-host` 直接依赖 `:adb-engine`（同族类型 + test-compat `consumerBootstrap()` / `adbIdentityDir()` / `connectAuto()`）。
- `:at-cli` 保留 transitional `:at-engine-spi` / `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine` 依赖。
- `ATAndroidAdbControl` 虽是 facade，public surface 仍返回 provider 类型（`AdbRuntimeState` / `AdbBootstrapState` / `AdbPairingDiscovery` / `AdbPairingResultV2` / `AdbDiscoveredService` / `AdbRuntime.AdbRuntimeListenerHandle`）。

## 3. Internal Type Leak Inventory（Phase A 审计结果）

| Consumer | 泄漏的 provider 类型（main） | 泄漏的 provider 类型（test） |
|---|---|---|
| `:app` | `AdbRuntimeState`, `AdbRuntime`, `AdbEngine`, `AdbConsumerBootstrap`, `AdbBootstrapState`, `AdbPairingResultV2`, `AdbRuntimeDiagnostic` | 同族 + 直接 `AdbEngine.pair` / `AdbRuntime.discoverPairing` / `AdbRuntime.canWriteSecureSettings` |
| `:at-cli-consumer-host` | `AdbRuntime`, `AdbConsumerBootstrap`, `AdbRuntimeDiagnostic`, `AdbRuntimeState` | `AdbEngine`, `AdbRuntime`, `AdbRuntimeState`, `AdbConsumerBootstrap`, `AdbBootstrapState` |
| `:at-cli` | （无 main 泄漏；Gradle transitional 依赖） | — |

## 4. Projection Type Design（Phase B）

新增 composition-owned semantic types（`:at-composition-android`，`ATAdbSemantic.kt`）：

| Composition 类型 | 投影来源（provider） | 性质 |
|---|---|---|
| `ATAdbReadinessState`（enum，9 值） | `AdbRuntimeState` | 纯 1:1 映射，无新状态机 |
| `ATAdbBootstrapState`（enum，6 值） | `AdbBootstrapState` | 纯 1:1 映射，honest classification 保持 |
| `ATAdbDiscoveredService`（data class） | `AdbDiscoveredService` | 字段级纯映射 |
| `ATAdbPairingDiscovery`（data class） | `AdbPairingDiscovery` | 字段级纯映射 |
| `ATAdbPairingResult`（data class + `Outcome` enum） | `AdbPairingResultV2` | 字段级纯映射 |
| `ATAdbLifecycleListenerHandle`（class） | `AdbRuntime.AdbRuntimeListenerHandle` | 包装句柄；`remove()` 委托单例 AdbRuntime |

**Honest classification 保持**（§13）：`NO IDENTITY → PAIRING_REQUIRED`；`EXISTING IDENTITY + connect failure → CONNECT_FAILED`（不自动映射成 PAIRING_REQUIRED）。投影是 1:1 映射，无任何重分类。

**类型归属理由**（§11）：这些类型描述 Android ADB provider control plane（readiness / bootstrap / pairing），不是 provider-neutral execution semantics，因此属于 `:at-composition-android`，**不**属于 `:at-engine-api`。

## 5. Mapping Table

```
AdbRuntimeState          → ATAdbReadinessState        (from() 1:1)
AdbBootstrapState        → ATAdbBootstrapState        (from() 1:1)
AdbDiscoveredService     → ATAdbDiscoveredService     (from() 1:1)
AdbPairingDiscovery      → ATAdbPairingDiscovery      (from() 1:1)
AdbPairingResultV2       → ATAdbPairingResult         (from() 1:1)
AdbRuntimeListenerHandle → ATAdbLifecycleListenerHandle (create() 包装)
```

CLI 侧新增 CLI-owned types（`:at-cli`，`ATCliPairing.kt`，§20/§21）：

```
ATAdbPairingState（host 映射）→ ATCliPairingState      (READY / PAIRING_REQUIRED / CONNECT_FAILED / NOT_READY)
ATAdbPairingResult（host 映射）→ ATCliPairingResult    (Outcome: SUCCESS/REJECTED/SERVICE_NOT_FOUND/MULTIPLE_ENDPOINTS/FAILED)
```

关系：composition semantic type → host mapping → CLI semantic type → `AdbCliBridgeServer` single formatter → wire text。

## 6. ATAndroidAdbControl Old / New Surface

**OLD（V4-4）**：

```kotlin
fun bootstrapState(): AdbBootstrapState
fun hasIdentity(): Boolean
fun discoverPairing(timeoutMs): AdbPairingDiscovery
fun pair(host, port, code): AdbPairingResultV2
fun pairDiscovered(code, timeoutMs): AdbPairingResultV2
fun ensureReady(onStatusLine): Boolean
fun readinessState(): AdbRuntimeState
fun addLifecycleListener(f: (AdbRuntimeState, AdbRuntimeState) -> Unit): AdbRuntime.AdbRuntimeListenerHandle
fun removeLifecycleListener(handle: AdbRuntime.AdbRuntimeListenerHandle)
```

**NEW（V4-4.1）**（public surface 零 provider 类型）：

```kotlin
fun bootstrapState(): ATAdbBootstrapState
fun hasIdentity(): Boolean
fun discoverPairing(timeoutMs: Long = 10_000L): ATAdbPairingDiscovery
fun pair(host: String, port: Int, code: String): ATAdbPairingResult
fun pairDiscovered(code: String, timeoutMs: Long = 10_000L): ATAdbPairingResult
fun ensureReady(onStatusLine: (String) -> Unit): Boolean
fun readinessState(): ATAdbReadinessState
fun addLifecycleListener(f: (ATAdbReadinessState, ATAdbReadinessState) -> Unit): ATAdbLifecycleListenerHandle
fun removeLifecycleListener(handle: ATAdbLifecycleListenerHandle)
// test-only probes（READ-ONLY 委托，无 lifecycle 效果）:
fun canWriteSecureSettings(): Boolean
fun isRuntimeInitialized(): Boolean
```

implementation 内部仍使用 provider 类型（composition 是 assembly/internal integration boundary，§15）。`from()` mapper 标记 `internal`（provider 参数类型不出现在 consumer-facing 签名）。

## 7. PairActivity Migration（Phase C）

- 删除 `AdbConsumerBootstrap` 字段 + `AppRuntime.consumerBootstrap()` 调用。
- 改用 `AppRuntime.adbControl`（composition semantic facade）：`bootstrapState()` / `pairDiscovered(code)` / `ensureReady` / `readinessState()`。
- 异常 fallback 用 `ATAdbPairingResult(success=false, outcome=FAILED)`。
- UI 布局 / 用户步骤 / pairing code semantics / status text 语义 **不变**（SEMANTIC_DELTA = NONE）。

## 8. AppRuntime Migration（Phase D）

- 删除全部 `com.adb.engine` import 与 internal accessor：`consumerBootstrap()` / `adbIdentityDir(): String` / `adbState(): AdbRuntimeState` / `connectAuto(): AdbRuntimeDiagnostic` / `adbRuntime()`。
- 保留：`composition()` / `adbControl` / `localEngine()` / `adbEngine()` / `adbState(): ATAdbReadinessState` / `initStatusHub` / status hub 系列。
- 新增 test-only：`adbIdentityDir(): File`（路径语义不变 `filesDir/adb_identity`）；`connectAuto(): Boolean`（委托 `adbControl.ensureReady`，供 10 个 product test 迁移用）。
- **不** public 暴露 `AdbConsumerBootstrap` / `AdbRuntime` / `AdbRuntimeState` / `identityDir: String`。

## 9. ConsumerHost Migration（Phase E）

- 删除全部 `com.adb.engine` import；`adbState(): ATAdbReadinessState`；`ensureReady(): Boolean`（委托 facade）。
- 删除 test-compat：`consumerBootstrap()` / `adbState(): AdbRuntimeState` / `connectAuto(): AdbRuntimeDiagnostic`。
- 新增：`adbIdentityDir(): File`（`filesDir/at-cli-identity`，语义不变）；`hasIdentityForDir(File): Boolean`（纯 fs 检查）；`bootstrapClassificationForDir(File): ATAdbBootstrapState`（经 facade 读 readiness + 纯 C4 规则，无第二 bootstrap 状态机）；`isAdbRuntimeInitialized(): Boolean`（委托 facade）。
- 内联 `ATCliExecution`：`pairingState(): ATCliPairingState` / `pair/pairDiscovered → ATCliPairingResult`（host mapping `toCliResult`）。

## 10. ATCliExecution Structured Result（Phase F）

`ATCliExecution`（`:at-cli`）pairing surface 从 String 改为结构化：

```kotlin
fun pairingState(): ATCliPairingState = NOT_READY
fun pair(host, port, code): ATCliPairingResult = ATCliPairingResult.FAILED_RESULT
fun pairDiscovered(code): ATCliPairingResult = ATCliPairingResult.FAILED_RESULT
```

`AdbCliBridgeServer.onPair` 接收 `ATCliPairingResult`，**single presentation formatter** 在 CLI boundary 渲染 wire text；exit code 来自 `result.success`（不再靠 `resultText.contains("pairing succeeded")` 字符串匹配）。

## 11. CLI Wire Parity

WIRE_PROTOCOL_DELTA = **NONE**：

- T_ONESHOT / T_INTERACTIVE / T_PAIR / T_STATUS / T_OUT / T_ERR / T_EXIT 不变；
- stdout / stderr / exit code 语义不变；
- bad args / bad pairing code 语义不变（usage + exit 2）；
- same-UID UDS + SO_PEERCRED + app_process `AtCliMain` 不变；
- pairing 结果文本逐字相同（`pairing succeeded (guid=...)` / `pairing rejected...` / `pairing service not found` / `multiple pairing endpoints...` / `pairing failed...`）。

## 12. Final Gradle DAG

```
:app
  → :at-engine-api
  → :at-composition-android
  → :at-cli
  → :at-backend-local
  → :at-backend-adb

:at-cli-consumer-host
  → :at-engine-api
  → :at-composition-android
  → :at-cli

:at-cli
  → :at-engine-api   （ONLY）

:at-composition-android   （assembly root — internal provider dep ALLOWED）
  → :at-engine-api (api)
  → :at-engine-spi (api)
  → :at-engine
  → :at-backend-local
  → :at-backend-adb
  → :adb-engine
```

删除的 transitional 依赖：

- `:app/build.gradle.kts`：删 `implementation(project(":adb-engine"))`（2 处，含重复）。
- `:at-cli-consumer-host/build.gradle.kts`：删 `implementation(project(":adb-engine"))`。
- `:at-cli/build.gradle.kts`：删 `:at-engine-spi` / `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine`，只保留 `:at-engine-api`。

无 `api(project(":at-composition-android"))` 之类 transitive 泄漏方式。NO CYCLES。

## 13. Import Audit（Consumer Source Gate）

| Scope | `com.adb.engine` import | `com.at.backend.*` / `com.at.engine.impl.*` |
|---|---|---|
| `:app/src/main` | 0 | 0 |
| `:at-cli-consumer-host/src/main` | 0 | 0 |
| `:at-cli/src/main` | 0 | 0 |
| `:app/src/androidTest` | 0 | 0 |
| `:at-cli-consumer-host/src/androidTest` | 0 | 0 |

## 14. Public API Leak Audit（Composition Gate）

`ATAndroid` / `ATAndroidComposition` / `ATAndroidConfig` / `ATAndroidAdbControl` / `ATAdbSemantic` / `ATAdbLifecycleListenerHandle` 的 **public 签名**中：

- `AdbRuntime` / `AdbEngine` / `AdbConsumerBootstrap` / `AdbRuntimeState` / `AdbBootstrapState` / `AdbPairingResultV2` / `AdbPairingDiscovery` / `AdbDiscoveredService` / `AdbConnectionRequest` / `AdbEndpoint` / `File identityDir` / backend concrete types = **0**。
- `AdbConnectionRequest` / `AdbRuntime.initialize` 等仅出现在 `ATAndroid.create()` 的 **private 实现体**内（composition internal，§15）。
- `from()` mapper 参数类型为 provider 类型但方法标记 `internal`（composition 内部使用）。
- `ATAdbLifecycleListenerHandle` 的 provider handle 字段为 `private val: Any`（反射 `remove()`），public surface 无 provider 类型。

**COMPOSITION_PUBLIC_ADB_INTERNAL_TYPE_LEAK = 0**（public signature 口径）。

## 15. Ownership Proof

| 不变量 | 证据 |
|---|---|
| AdbEngine = ADB protocol owner | `:adb-engine` 协议实现未改（仅新增 test-only static 委托 helper） |
| AdbRuntime = UNIQUE lifecycle owner | 未新增 runtime；`ATAndroidAdbControl` 全部委托单例 `AdbRuntime` |
| AdbRuntime + AdbRecoveryCoordinator = UNIQUE recovery owner | 未触碰 recovery |
| AdbEngine.pair = pairing primitive | 唯一调用点仍在 `AdbConsumerBootstrap.pair` |
| AdbConsumerBootstrap = first-use bootstrap | 唯一实例由 `ATAndroidAdbControl`（composition 内部）持有 |
| ATAndroidAdbControl = PROJECTION_ONLY | 全部方法为 1:1 投影 / 委托；无新状态机 / 重试 / readiness / pairing / 错误分类逻辑 |
| SECOND_ADB_RUNTIME | NO |
| SECOND_RECOVERY_OWNER | NO |
| SECOND_PAIRING_OWNER | NO |
| SECOND_BOOTSTRAP_STATE_MACHINE | NO |

## 16. Identity Preservation

| Consumer | 路径 | 变更 |
|---|---|---|
| `:app` | `filesDir/adb_identity` | NO（`ATAndroidConfig()` 默认；`AppRuntime.adbIdentityDir()` 同路径） |
| `:at-cli-consumer-host` | `filesDir/at-cli-identity` | NO（`ATAndroidConfig(identityProfile = "at-cli-identity")`） |

KEY_COPY = NO；无 key migration / reset / force pair / 删除 existing identity。

## 17. Build / Test

| Gate | 结果 |
|---|---|
| `:adb-engine:test` / `:at-engine-api:test` / `:at-engine-spi:test` / `:at-engine:test` / `:at-backend-local:test` / `:at-backend-adb:test` / `:at-composition-android:test` / `:at-cli:test` / `:app:test` / `:at-cli-consumer-host:test` | PASS |
| `gradlew test`（全量） | PASS |
| `gradlew assembleDebug`（全量，含 :adb-only-test-host 等） | PASS |
| `:app:assembleDebugAndroidTest` / `:at-cli-consumer-host:assembleDebugAndroidTest` | PASS |

DEVICE_TEST = NOT_EVALUATED（本轮禁止 device work）。

## 18. Production Diff Classification

| 类别 | 文件 |
|---|---|
| TYPE_PROJECTION_ONLY | `ATAdbSemantic.kt`（新增）/ `ATCliPairing.kt`（新增）/ `ATAndroidAdbControl.kt`（surface 投影）/ `ATAdbLifecycleListenerHandle` |
| IMPORT_CHANGE | `MainActivity.kt` / `PairActivity.kt` / `AdbCliStatusHub.kt` / `AppCliExecution.kt` / `AtCliConsumerHost.kt` / 11 个 app androidTest / `AtCliConsumerTest.kt` |
| DEPENDENCY_CHANGE | `app/build.gradle.kts` / `at-cli-consumer-host/build.gradle.kts` / `at-cli/build.gradle.kts` |
| CONSUMER_ADAPTER_CHANGE | `AppRuntime.kt` / `AtCliConsumerHost.kt` / `AppCliExecution.kt` / `AdbCliStatusHub.kt` / `PairActivity.kt` / `MainActivity.kt` / `AdbCliBridgeServer.kt`（formatter）/ `ATCliExecution.kt`（SPI 签名） |
| TEST_ADAPTATION | `AtCliConsumerTest.kt` / `AppAdbProvisionTest.kt` / 其余 10 个 app androidTest / `CommonConsumerPathTest.kt` |
| TEST-ONLY PROVIDER HELPER | `AdbConsumerBootstrap.kt`（companion `hasIdentityForDir` / `bootstrapStateForDir` / `discoverPairingForTest` / `canWriteSecureSettingsForTest` / `isRuntimeInitializedForTest` — 纯委托，无逻辑变更） |
| DOC_CHANGE | SSOT / ROADMAP / MIGRATION-AUDIT count 修正 / 本文档 |

**禁止类别全部 = 0**：无 ADB_PROTOCOL_LOGIC_CHANGE / PTY_LOGIC_CHANGE / CLI_WIRE_PROTOCOL_CHANGE / RECOVERY_LOGIC_CHANGE / PAIRING_LOGIC_CHANGE / IDENTITY_LOGIC_CHANGE / EXECUTION_SEMANTIC_CHANGE。

## 19. Device Intentionally Not Evaluated

DEVICE_TEST = NO；DEVICE_PARITY = NOT_EVALUATED。本轮不执行 `adb devices` / install / shell / connectedAndroidTest / 真机 smoke / pair。所有 device gate（L1-L7 / A1-A10 / C1-C6 / H1-H7 / restart identity / runtime ownership device evidence）留给 **AT.V4-4.2**。

## 20. Final Verdict

| Gate | 结果 |
|---|---|
| APP_DIRECT_ADB_ENGINE_DEP | NO |
| CONSUMER_HOST_DIRECT_ADB_ENGINE_DEP | NO |
| CLI_DIRECT_ADB_ENGINE_DEP | NO |
| APP / CONSUMER_HOST / CLI ADB INTERNAL IMPORTS | 0 / 0 / 0 |
| COMPOSITION_PUBLIC_ADB_INTERNAL_TYPE_LEAK | 0 |
| SEMANTIC_DELTA | NONE |
| ATEngine_API_CHANGED | NO |
| ADB_ENGINE_LOGIC_CHANGED | NO（仅 test-only 静态委托 helper） |
| CLI_WIRE_PROTOCOL_CHANGED | NO |
| BUILD / UNIT_TEST / ANDROID_TEST_COMPILE | PASS / PASS / PASS |

## 21. Handoff to V4-4.2

AT.V4-4.2（Device Closure）scope 预留：Product Local L1-L7 / Product ADB A1-A10 / CLI C1-C6 / ConsumerHost H1-H7 / App restart identity / Host restart identity / connection reuse / runtime ownership device evidence。

V4-4.1 PASS 后：

- **CONSUMER_BOUNDARY_IMPLEMENTATION = CLOSED**
- **DEVICE_CLOSURE = PENDING**
- AT.V4 overall 尚未进入 V4-5。
