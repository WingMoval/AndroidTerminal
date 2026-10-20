# AT.CONSUMER-PHYSICAL-0 — AT Engine 消费者边界物理化审计

> 性质：ARCHITECTURE AUDIT / MODULE BOUNDARY DESIGN / DEPENDENCY GRAPH ANALYSIS /
> PUBLIC SURFACE ANALYSIS / PHYSICAL SEPARATION PLANNING。
> DOCS ONLY。PRODUCTION_CODE_CHANGE = NO / TEST_CODE_CHANGE = NO / GRADLE_CHANGE = NO /
> MODULE_CREATE = NO / MODULE_RENAME = NO / PACKAGE_MOVE = NO / API_IMPLEMENTATION = NO /
> DEVICE_TEST = NO / NEW_TAG = NO / PUSH = NO。
> 审计基线：`f55973d`（AT.CONSUMER-CAPABILITY-AUDIT-0.1 + AT.CLI-PATH-FACTCHECK-0 未提交）。

---

## 1. Executive Verdict

**VERDICT = PASS**

核心结论：

1. **CURRENT_PHYSICAL_LAYER2 = PARTIAL**
   - 存在 `ATEngine` facade（Layer-2 conceptual），但 lower-level implementation（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / backend classes）仍是正常消费路径的一部分。
   - Consumer 当前可以直接 import 并调用所有 provider/internal 类型。

2. **PHYSICAL_SPLIT_NEEDED = PARTIAL**
   - 不需要新增 5 个 module（五层 = 五 module 是过度模块化）。
   - 需要新增 **1 个独立 public/API module**（`:at-engine-api` 或 `:at-api`），作为 Consumer 的正式物理边界。
   - 现有 `:at-engine` 保留为 implementation module（Layer-3）。
   - `:adb-engine` / `:at-backend-local` / `:at-backend-adb` 保持为 provider/adapter module（Layer-4/5），对普通 Consumer **不可见**。

3. **RECOMMENDED_PHYSICAL_MODEL = Candidate A（独立 public/API module）**
   - `:at-engine-api`：pure contract / facade / public types（no Android Context in method signatures, no ADB internals, no backend classes）。
   - `:at-engine`：implementation / orchestration / session / backend-facing abstractions。
   - Consumer → `:at-engine-api`（`api` 依赖）。
   - `:at-engine` → `:at-engine-api` + `:at-backend-*` + `:adb-engine`（`implementation` 依赖）。
   - **AdbRuntime ownership 不变**（UNIQUE ADB lifecycle owner）。
   - **AdbConsumerBootstrap behavior 不变**（first-use/bootstrap facade）。
   - **Local / ADB separation 不变**。
   - **CLI / Local boundary 不变**。

4. **NEW_LAYER2_MODULE_NEEDED = YES**
   - 新增 1 个 module（`:at-engine-api`），解决 **compile-time boundary enforcement** 问题。
   - 现有 `:at-engine` 角色从 "API + implementation mixed" 变为 "implementation only"。

5. **COMPILE_TIME_ENFORCEMENT = GRADLE MODULE BOUNDARY + DEPENDENCY RULES**
   - 普通 Consumer 只依赖 `:at-engine-api`，**无法**直接 import `:adb-engine` / `:at-backend-*`。
   - Advanced provider integrator 可以额外依赖 `:at-backend-*`（用于实现自定义 backend）。
   - 不需要 Kotlin `internal` / package visibility（Gradle module boundary 已足够）。

6. **AG TO-BE PHYSICAL DEPENDENCY = `:at-engine-api` ONLY**
   - AG integration adapter 最终只依赖 `:at-engine-api`，不依赖 `:adb-engine` / `:at-backend-adb` / identity internals。
   - AG Core 仍禁止 concrete AT dependency。

7. **AT APP TO-BE PHYSICAL DEPENDENCY = `:at-engine-api` + `:at-cli`（optional frontend）**
   - AT 自己的 Product App 必须像真正 Consumer 一样使用 Layer-2 physical boundary。
   - 不允许 "AT App 是自己人，所以可以继续穿透"。

8. **REFERENCE_CONFORMANCE_HOST = RECOMMENDED（future task）**
   - 建议未来建立 `:at-consumer-conformance-host`，只依赖 `:at-engine-api`，证明普通第三方 Consumer 不需要 internal modules。
   - 本轮不实现。

9. **EXECUTION_SEMANTICS_CHANGED = NO**
   - 物理重构只移动 ownership / visibility，不改变 execution semantics。
   - **AdbRuntime owner 不变** / **Recovery owner 不变** / **Pairing owner 不变** / **Local/ADB separation 不变** / **CLI/Local boundary 不变**。

10. **PRE_EMULATOR_FREEZE_IMPACT = NONE**
    - Known-Good freeze 不是 final ABI freeze，物理重构不需要重新打开 execution semantic freeze。
    - **AG_CONSUMER_FREEZE_IMPACT = NONE** / **CONTRACT_DRAFT_IMPACT = NONE**。

11. **RECOMMENDED_MIGRATION_PHASES = P0-P6（最小 phase，非一次性大搬家）**
    - P0：module/API skeleton（新增 `:at-engine-api`，移动 public types）。
    - P1：composition/orchestration move（`ATEngine` facade 移到 `:at-engine-api`，implementation 留在 `:at-engine`）。
    - P2：AT App migrate（`:app` 改依赖 `:at-engine-api`）。
    - P3：reference consumer migrate（`:at-cli-consumer-host` 改依赖 `:at-engine-api`）。
    - P4：compile-time dependency enforcement（Gradle dependency rules + architecture test）。
    - P5：device regression（验证 Local / ADB / CLI 全路径）。
    - P6：new consumer handoff（发布 consumer-facing docs + AAR）。

12. **RECOMMENDED_NEXT = AT.CONSUMER-PHYSICAL-1**
    - 下一轮实施 P0（module skeleton + public type migration）。
    - **NEXT_OPEN_AUTHORIZED = NO**（等待 Controller Review）。

---

## 2. Historical Logical Boundary（TERM.A0.5 五层模型）

**Layer 1**：Consumer / Product Shell（`:app` / `:at-cli-consumer-host` / future AG adapter）

**Layer 2**：AT Engine Public Interface（`ATEngine` facade + public contract types）

**Layer 3**：AT Engine（execution orchestration / session / backend-facing abstractions）

**Layer 4**：Backend Adapter（`:at-backend-local` / `:at-backend-adb`）

**Layer 5**：Execution Mechanism / Provider（Local PTY / ADB Engine → adbd）

**关键原则**：
- AG / Product 只依赖 Layer 2，不穿透到 Layer 3/4/5。
- AG Core 禁止 concrete AT dependency。
- Local / ADB 是正交 capability，consumer 按需组合。

---

## 3. Current Module Graph（§7）

```
:app
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  ├── implementation(:adb-engine)
  └── implementation(:at-cli)

:at-engine
  └── (no project dependencies)

:at-backend-local
  └── implementation(:at-engine)

:at-backend-adb
  ├── implementation(:at-engine)
  └── implementation(:adb-engine)

:adb-engine
  └── (no project dependencies)

:at-cli
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-cli-consumer-host
  ├── implementation(:at-cli)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)
```

**关键发现**：
- **所有依赖都是 `implementation`**（无 `api`），意味着 consumer 的上层模块不能看到 consumer 的依赖（Gradle `implementation` 语义）。
- **但 consumer 本身可以直接 import 所有依赖 module 的 public 类型**（`implementation` 不隐藏类型，只是不暴露给 consumer 的 consumer）。
- **`:at-engine` 无 project dependencies**（pure contract / facade module，但当前混合了 implementation）。

---

## 4. Current Package / Public Surface（§8）

### `:at-engine`（5 个 .kt 文件）

| 文件 | 主要类型 | Visibility | Architectural Layer |
|---|---|---|---|
| `ATBackend.kt` | `ATOneShotBackend` / `ATPersistentBackend`（interfaces） | public | Layer-4（backend-facing abstractions） |
| `ATEngine.kt` | `ATEngine`（class） | public | Layer-2（facade） |
| `AtEngineMarker.kt` | `AtEngineMarker`（object） | public | Layer-2（marker） |
| `ATExecution.kt` | `ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome`（data classes / enums） | public | Layer-2（public contract types） |
| `ATSession.kt` | `ATTerminalSession`（interface） / `ATSessionEvent`（sealed class） / `ATSessionState`（enum） | public | Layer-2/3（session contract + implementation detail） |

**Public type leak**：`ATOneShotBackend` / `ATPersistentBackend` 是 backend-facing abstractions，**不应该**暴露给普通 Consumer（Consumer 不应该自己构造 backend）。

### `:at-backend-local`（5 个 .kt 文件）

| 文件 | 主要类型 | Visibility | Architectural Layer |
|---|---|---|---|
| `LocalBackendMarker.kt` | `LocalBackendMarker`（object） | public | Layer-4（marker） |
| `LocalOneShotBackend.kt` | `LocalOneShotBackend`（class） | public | Layer-4（backend implementation） |
| `LocalPersistentBackend.kt` | `LocalPersistentBackend`（class） / `LocalTerminalSession`（class） | public | Layer-4（backend implementation） |
| `LocalPtyNative.kt` | `LocalPtyNative`（object，JNI） | internal | Layer-5（native primitive） |
| `LocalPtySession.kt` | `LocalPtySession`（class） | public | Layer-5（provider primitive） |

**Public type leak**：`LocalPersistentBackend` / `LocalPtySession` 是 provider implementation，**不应该**暴露给普通 Consumer。

### `:at-backend-adb`（2 个 .kt 文件）

| 文件 | 主要类型 | Visibility | Architectural Layer |
|---|---|---|---|
| `AdbBackend.kt` | `AdbOneShotBackend` / `AdbPersistentBackend`（classes） | public | Layer-4（backend implementation） |
| `AdbBackendMarker.kt` | `AdbBackendMarker`（object） | public | Layer-4（marker） |

**Public type leak**：`AdbOneShotBackend` / `AdbPersistentBackend` 是 backend implementation，**不应该**暴露给普通 Consumer。

### `:adb-engine`（14 个 .kt 文件）

| 文件 | 主要类型 | Visibility | Architectural Layer |
|---|---|---|---|
| `AdbConnectionRequest.kt` | `AdbConnectionRequest`（data class） | public | Layer-5（provider type） |
| `AdbConnectionState.kt` | `AdbConnectionState`（enum） | public | Layer-5（provider type） |
| `AdbConsumerBootstrap.kt` | `AdbConsumerBootstrap`（class） / `AdbBootstrapState`（enum） / `AdbPairingDiscovery`（data class） / `AdbPairingResultV2`（data class） | public | Layer-3/4（bootstrap facade） |
| `AdbEndpoint.kt` | `AdbEndpoint`（data class） / `AdbEndpointSource`（enum） | public | Layer-5（provider type） |
| `AdbEngine.kt` | `AdbEngine`（object） | public | Layer-5（provider engine） |
| `AdbEngineMarker.kt` | `AdbEngineMarker`（object） | public | Layer-5（marker） |
| `AdbInteractiveStream.kt` | `AdbInteractiveStream`（interface） | public | Layer-5（provider primitive） |
| `AdbRecovery.kt` | `AdbRecoveryPolicy`（data class） | public | Layer-5（provider type） |
| `AdbRecoveryCoordinator.kt` | `AdbRecoveryCoordinator`（class） | public | Layer-5（recovery owner） |
| `AdbRuntime.kt` | `AdbRuntime`（object） / `AdbRuntimeState`（enum） / `AdbRuntimeDiagnostic`（data class） / `AdbRuntimeListener`（fun interface） | public | Layer-3/4（lifecycle owner） |
| `AdbRuntimeBackends.kt` | `AdbWirelessDebuggingState`（enum） / `AdbDiscoveryServiceType`（enum） / `AdbDiscoveredService`（data class） / `AdbDiscoveryBackend`（interface） / `AdbWirelessDebuggingReadinessBackend`（interface） / `AdbAndroidNsdDiscoveryBackend`（class） / `AdbAndroidWirelessDebuggingReadinessBackend`（class） | public | Layer-5（provider internals） |
| `AdbRuntimeState.kt` | （merged into `AdbRuntime.kt`） | - | - |
| `AdbShellResult.kt` | `AdbShellResult`（data class） | public | Layer-5（provider type） |
| `AdbNative.kt` | `AdbNative`（object，JNI） | internal | Layer-5（native primitive） |

**Public type leak**：`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / `AdbRecoveryCoordinator` / `AdbEndpointStore` / `AdbConnectionRequest` / `AdbInteractiveStream` / `AdbEndpoint` / `AdbDiscoveredService` / `AdbWirelessDebuggingState` / `AdbDiscoveryBackend` / `AdbAndroidNsdDiscoveryBackend` 等都是 provider/internal 类型，**不应该**暴露给普通 Consumer。

### `:at-cli`（6 个 .kt 文件）

| 文件 | 主要类型 | Visibility | Architectural Layer |
|---|---|---|---|
| `AdbCliArgs.kt` | `AdbCliParseResult`（data class） | public | Layer-2（CLI frontend contract） |
| `AdbCliBridgeServer.kt` | `AdbCliBridgeServer`（class） | public | Layer-2/4（bridge server） |
| `AdbCliMain.kt` | `AtCliMain`（object） | public | Layer-2（CLI entrypoint） |
| `AdbCliProtocol.kt` | `AdbCliProtocol`（object） | public | Layer-2（protocol constants） |
| `ATCliExecution.kt` | `ATCliExecution`（interface） | public | Layer-2（host-provided SPI） |
| `LocalSessionBootstrap.kt` | `LocalCliBootstrap`（class） | public | Layer-2（shell function bootstrap） |

**Public type leak**：`ATCliExecution` 要求 host 实现 `adbEngine()` / `ensureReady()` / `adbIdentityDir()` / `consumerBootstrap()`，这些是 boundary leakage（host 不应该自己编排 ADB lifecycle）。

---

## 5. Consumer Compile-time Reachability（§9）

**一个第三方 Consumer 如果依赖当前推荐 module set（`:at-engine` + `:at-backend-local` + `:at-backend-adb` + `:adb-engine` + `:at-cli`），可以直接 import：**

### Layer-2 public types（应该可见）

- `ATEngine`（facade）
- `ATExecutionRequest` / `ATExecutionResult` / `ATExecutionOutcome`（execution contract）
- `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`（session contract）

### Layer-3/4/5 provider/internal types（不应该可见，但当前可以）

- `AdbRuntime` / `AdbRuntimeState` / `AdbRuntimeDiagnostic` / `AdbRuntimeListener`
- `AdbConsumerBootstrap` / `AdbBootstrapState` / `AdbPairingDiscovery` / `AdbPairingResultV2`
- `AdbEngine`
- `AdbOneShotBackend` / `AdbPersistentBackend`
- `LocalOneShotBackend` / `LocalPersistentBackend` / `LocalPtySession`
- `AdbConnectionRequest` / `AdbConnectionState` / `AdbInteractiveStream` / `AdbEndpoint` / `AdbEndpointSource` / `AdbDiscoveredService` / `AdbWirelessDebuggingState` / `AdbDiscoveryBackend` / `AdbAndroidNsdDiscoveryBackend` / `AdbShellResult` / `AdbRecoveryPolicy` / `AdbRecoveryCoordinator`
- `ATCliExecution` / `AdbCliBridgeServer` / `AtCliMain` / `AdbCliProtocol` / `LocalCliBootstrap` / `AdbCliParseResult`

**结论**：Consumer 当前可以直接 import 并调用所有 provider/internal 类型，**没有 compile-time boundary**。

---

## 6. `api` vs `implementation` Leakage（§10）

**GRADLE_TRANSITIVE_LEAK_MATRIX**：

| From module | Dependency | Configuration | Transitive to consumer? | Contains provider-internal types? | Architectural problem? |
|---|---|---|---|---|---|
| `:app` | `:at-engine` | `implementation` | NO | NO | NO（`:at-engine` 无 project dependencies） |
| `:app` | `:at-backend-local` | `implementation` | NO | YES（`LocalPersistentBackend` / `LocalPtySession`） | YES（Consumer 不应该看到 Local backend implementation） |
| `:app` | `:at-backend-adb` | `implementation` | NO | YES（`AdbOneShotBackend` / `AdbPersistentBackend`） | YES（Consumer 不应该看到 ADB backend implementation） |
| `:app` | `:adb-engine` | `implementation` | NO | YES（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / ...） | YES（Consumer 不应该看到 ADB provider internals） |
| `:app` | `:at-cli` | `implementation` | NO | YES（`ATCliExecution` / `AdbCliBridgeServer` / ...） | PARTIAL（CLI 是 frontend，Consumer 可能需要看到部分 CLI types） |
| `:at-backend-adb` | `:at-engine` | `implementation` | NO（`:at-engine` 无 project dependencies） | NO | NO |
| `:at-backend-adb` | `:adb-engine` | `implementation` | NO（`:adb-engine` 无 project dependencies） | YES | YES（`:at-backend-adb` 不应该暴露 `:adb-engine` types 给 `:at-backend-adb` 的 consumer） |
| `:at-cli` | `:at-engine` | `implementation` | NO | NO | NO |
| `:at-cli` | `:at-backend-local` | `implementation` | NO | YES | YES（`:at-cli` 不应该暴露 `:at-backend-local` types 给 `:at-cli` 的 consumer） |
| `:at-cli` | `:at-backend-adb` | `implementation` | NO | YES | YES（`:at-cli` 不应该暴露 `:at-backend-adb` types 给 `:at-cli` 的 consumer） |
| `:at-cli` | `:adb-engine` | `implementation` | NO | YES | YES（`:at-cli` 不应该暴露 `:adb-engine` types 给 `:at-cli` 的 consumer） |

**关键发现**：
- **所有依赖都是 `implementation`**，意味着 **transitive exposure = NO**（consumer 的上层模块不能看到 consumer 的依赖）。
- **但 consumer 本身可以直接 import 所有依赖 module 的 public 类型**（`implementation` 不隐藏类型）。
- **`:at-engine` 无 project dependencies**，所以 `:at-engine` 不会通过 `api` 泄漏 backend types。
- **问题不在 `api` vs `implementation`，而在 consumer 直接依赖了太多 module**（`:app` 直接依赖 `:adb-engine` / `:at-backend-local` / `:at-backend-adb`，而不是只依赖 `:at-engine`）。

**结论**：当前的 Gradle dependency 配置**没有**通过 `api` 泄漏 provider types，但**允许 consumer 直接依赖 provider modules**，导致 consumer 可以 import provider/internal 类型。解决方案是**新增一个独立 public/API module**，consumer 只依赖这个 module，**不直接依赖** `:adb-engine` / `:at-backend-*`。

---

## 7. Current Layer-2 Physical Verdict（§11）

**CURRENT_PHYSICAL_LAYER2 = PARTIAL**

**理由**：
- 存在 `ATEngine` facade（Layer-2 conceptual），Consumer 可以通过 `ATEngine.execute()` / `ATEngine.openSession()` 使用 execution capability。
- **但** lower-level implementation（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / backend classes）仍是正常消费路径的一部分：
  - Consumer 必须自己调用 `AdbRuntime.initialize(context)` / `AdbRuntime.connectAuto(keyDir)` / `AdbRuntime.ensureReady(keyDir)`。
  - Consumer 必须自己构造 `AdbConsumerBootstrap(identityDir)` 并进行 pairing。
  - Consumer 必须自己构造 `ATEngine(oneShot = AdbOneShotBackend(...), persistent = AdbPersistentBackend(...))`。
  - Consumer 必须自己传播 `identityDir` 到多个 subsystem（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbConnectionRequest` / CLI execution）。
- **没有 physical compile-time boundary** 阻止 Consumer 直接 import provider/internal 类型。

**定义匹配**：
- **PARTIAL** = 存在 facade，但 lower-level implementation 仍是正常消费路径的一部分。✓

---

## 8. `:at-engine` Role Audit（§12）

**CURRENT_AT_ENGINE_MODULE_ROLE = C. API + implementation mixed module**

**理由**：
- `:at-engine` 同时包含：
  - **Layer-2 public contract types**（`ATEngine` / `ATExecutionRequest` / `ATExecutionResult` / `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`）。
  - **Layer-4 backend-facing abstractions**（`ATOneShotBackend` / `ATPersistentBackend` interfaces）。
  - **Layer-3 implementation detail**（`ATTerminalSession` 的具体实现 `LocalTerminalSession` 在 `:at-backend-local`，但 `ATSessionEvent` / `ATSessionState` 的语义是 Layer-3）。
- **混合项**：
  - `ATOneShotBackend` / `ATPersistentBackend` 是 backend-facing abstractions，**不应该**暴露给普通 Consumer（Consumer 不应该自己构造 backend）。
  - `ATTerminalSession` / `ATSessionEvent` / `ATSessionState` 是 session contract，**应该**暴露给 Consumer，但具体实现（`LocalTerminalSession` / `AdbTerminalSession`）在 backend modules，**不应该**暴露。

**结论**：`:at-engine` 当前是 mixed module，需要拆分：
- **Public contract types**（`ATEngine` / `ATExecutionRequest` / `ATExecutionResult` / `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`）→ 移到新的 `:at-engine-api` module。
- **Backend-facing abstractions**（`ATOneShotBackend` / `ATPersistentBackend`）→ 保留在 `:at-engine`（implementation module），**不暴露**给普通 Consumer。

---

## 9. Candidate A/B/C/D 比较（§13）

### Candidate A：独立 public/API module

**示意**：
```
:at-engine-api  (new)
  ├── ATEngine (facade)
  ├── ATExecutionRequest / ATExecutionResult / ATExecutionOutcome
  ├── ATTerminalSession / ATSessionEvent / ATSessionState
  └── (no Android Context in method signatures)

:at-engine  (existing, becomes implementation)
  ├── ATOneShotBackend / ATPersistentBackend (backend-facing abstractions)
  ├── session implementation (LocalTerminalSession / AdbTerminalSession)
  └── orchestration / backend wiring

Consumer
  └── implementation(:at-engine-api)

:at-engine
  ├── implementation(:at-engine-api)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)
```

**优点**：
- **COMPILE_TIME_ENFORCEMENT = YES**（Consumer 只依赖 `:at-engine-api`，无法直接 import `:adb-engine` / `:at-backend-*`）。
- **CONSUMER_MISUSE_RESISTANCE = HIGH**（Consumer 无法调用 `AdbRuntime.connectAuto()` / `AdbConsumerBootstrap.pair()` / 构造 backend）。
- **BACKEND_NEUTRALITY = PRESERVED**（`:at-engine-api` 不包含 backend-specific types）。
- **ADB_INTERNAL_HIDING = YES**（`:adb-engine` 对 Consumer 不可见）。
- **LOCAL_ONLY_SUPPORT = YES**（Local-only consumer 只依赖 `:at-engine-api`）。
- **ADB_DIRECT_SUPPORT = YES**（ADB-direct consumer 只依赖 `:at-engine-api`，ADB lifecycle 由 `:at-engine` implementation 内部处理）。
- **FULL_TERMINAL_SUPPORT = YES**（Full terminal consumer 只依赖 `:at-engine-api`）。
- **CLI_COMPATIBILITY = YES**（`:at-cli` 依赖 `:at-engine-api` + `:at-engine`，Consumer 可选依赖 `:at-cli`）。
- **FUTURE_EMULATOR = YES**（Emulator / ScreenState 是 AT Engine capability，放在 `:at-engine` implementation，不暴露给 Consumer）。
- **FUTURE_SCREENSTATE = YES**（ScreenState 是 Layer-3/4，不暴露给 Consumer）。
- **FUTURE_SSH = YES**（SSH backend 是 Layer-4 adapter，不暴露给 Consumer）。
- **TESTABILITY = HIGH**（`:at-engine-api` 是 pure contract，易于 mock / test）。
- **BINARY_PACKAGING = NEUTRAL**（新增 1 个 AAR，total AAR count 从 5 → 6）。
- **AAR_CONSUMPTION = SLIGHTLY_COMPLICATE**（raw AAR vendoring 需要多 vendor 1 个 AAR）。
- **GRADLE_COMPLEXITY = LOW**（只新增 1 个 module，dependency graph 简单）。
- **MIGRATION_COST = MEDIUM**（需要移动 public types + 更新 consumer dependencies）。
- **API_STABILITY = HIGH**（`:at-engine-api` 是 stable public surface，implementation 可以频繁变化）。
- **RISK = LOW**（不改变 execution semantics，只移动 visibility）。
- **LONG_TERM_MAINTAINABILITY = HIGH**（clear separation between public contract and implementation）。

**缺点**：
- **MIGRATION_COST = MEDIUM**（需要移动 ~10 个 public types 到 `:at-engine-api`）。
- **AAR_CONSUMPTION = SLIGHTLY_COMPLICATE**（raw AAR vendoring 需要多 vendor 1 个 AAR）。

### Candidate B：现有 `:at-engine` 成为 Public Interface，新增 implementation module

**示意**：
```
:at-engine  (existing, becomes contract-only)
  ├── ATEngine (facade)
  ├── ATExecutionRequest / ATExecutionResult / ATExecutionOutcome
  ├── ATTerminalSession / ATSessionEvent / ATSessionState
  └── (no Android Context in method signatures)

:at-engine-impl  (new)
  ├── ATOneShotBackend / ATPersistentBackend (backend-facing abstractions)
  ├── session implementation
  └── orchestration / backend wiring

Consumer
  └── implementation(:at-engine)

:at-engine-impl
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)
```

**优点**：
- **COMPILE_TIME_ENFORCEMENT = YES**（Consumer 只依赖 `:at-engine`，无法直接 import `:adb-engine` / `:at-backend-*`）。
- **MIGRATION_COST = LOW**（不需要移动 types，只需要新增 `:at-engine-impl` module 并移动 implementation）。
- **API_STABILITY = HIGH**（`:at-engine` 保持现有 package / types，Consumer 不需要更新 imports）。

**缺点**：
- **NAMING_CONFUSION = HIGH**（`:at-engine` 既是 public contract 又是 implementation，语义不清）。
- **GRADLE_COMPLEXITY = MEDIUM**（`:at-engine` 无 dependencies，`:at-engine-impl` 依赖 `:at-engine` + backends，dependency graph 略复杂）。
- **LONG_TERM_MAINTAINABILITY = MEDIUM**（`:at-engine` 名字暗示 "engine implementation"，但实际是 contract，容易混淆）。

### Candidate C：保持 `:at-engine` 单 module，通过 Kotlin `internal` / package discipline 实现逻辑隔离

**示意**：
```
:at-engine  (existing, unchanged)
  ├── ATEngine (public facade)
  ├── ATExecutionRequest / ATExecutionResult (public contract types)
  ├── ATOneShotBackend / ATPersistentBackend (public backend-facing abstractions)
  ├── AdbRuntime / AdbConsumerBootstrap / AdbEngine (public provider/internal types)
  └── (no physical boundary)

Consumer
  └── implementation(:at-engine)
  └── (can import AdbRuntime / AdbConsumerBootstrap / AdbEngine / ...)
```

**优点**：
- **MIGRATION_COST = NONE**（不需要移动 types 或新增 module）。
- **GRADLE_COMPLEXITY = NONE**（dependency graph 不变）。

**缺点**：
- **COMPILE_TIME_ENFORCEMENT = NO**（Consumer 仍然可以直接 import provider/internal 类型）。
- **CONSUMER_MISUSE_RESISTANCE = LOW**（Consumer 可以调用 `AdbRuntime.connectAuto()` / `AdbConsumerBootstrap.pair()` / 构造 backend）。
- **ADB_INTERNAL_HIDING = NO**（`:adb-engine` types 仍然对 Consumer 可见）。
- **LONG_TERM_MAINTAINABILITY = LOW**（依赖文档纪律，没有物理强制）。

### Candidate D：Facade / consumer module

**示意**：
```
:at-consumer  (new, facade / consumer module)
  ├── ATEngine (facade)
  ├── ATExecutionRequest / ATExecutionResult
  ├── ATTerminalSession
  └── (composition / factory entrypoint)

:at-engine  (existing, unchanged)
  ├── ATOneShotBackend / ATPersistentBackend
  ├── AdbRuntime / AdbConsumerBootstrap / AdbEngine
  └── (implementation)

Consumer
  └── implementation(:at-consumer)

:at-consumer
  └── implementation(:at-engine)
```

**优点**：
- **COMPILE_TIME_ENFORCEMENT = YES**（Consumer 只依赖 `:at-consumer`，无法直接 import `:at-engine` internals）。
- **CONSUMER_MISUSE_RESISTANCE = HIGH**（Consumer 只能通过 `:at-consumer` facade 使用 AT）。

**缺点**：
- **GRADLE_COMPLEXITY = MEDIUM**（`:at-consumer` 依赖 `:at-engine`，`:at-engine` 依赖 backends，dependency graph 略复杂）。
- **NAMING_CONFUSION = MEDIUM**（`:at-consumer` 名字暗示 "consumer implementation"，但实际是 facade，容易混淆）。
- **API_STABILITY = MEDIUM**（`:at-consumer` 是新 module，没有历史 stability）。

---

## 10. Candidate Comparison Matrix（§14）

| 维度 | Candidate A | Candidate B | Candidate C | Candidate D |
|---|---|---|---|---|
| COMPILE_TIME_ENFORCEMENT | YES | YES | NO | YES |
| CONSUMER_MISUSE_RESISTANCE | HIGH | HIGH | LOW | HIGH |
| BACKEND_NEUTRALITY | PRESERVED | PRESERVED | PRESERVED | PRESERVED |
| ADB_INTERNAL_HIDING | YES | YES | NO | YES |
| LOCAL_ONLY_SUPPORT | YES | YES | YES | YES |
| ADB_DIRECT_SUPPORT | YES | YES | YES | YES |
| FULL_TERMINAL_SUPPORT | YES | YES | YES | YES |
| CLI_COMPATIBILITY | YES | YES | YES | YES |
| FUTURE_EMULATOR | YES | YES | YES | YES |
| FUTURE_SCREENSTATE | YES | YES | YES | YES |
| FUTURE_SSH | YES | YES | YES | YES |
| TESTABILITY | HIGH | HIGH | MEDIUM | HIGH |
| BINARY_PACKAGING | NEUTRAL | NEUTRAL | NONE | NEUTRAL |
| AAR_CONSUMPTION | SLIGHTLY_COMPLICATE | SLIGHTLY_COMPLICATE | NONE | SLIGHTLY_COMPLICATE |
| GRADLE_COMPLEXITY | LOW | MEDIUM | NONE | MEDIUM |
| MIGRATION_COST | MEDIUM | LOW | NONE | MEDIUM |
| API_STABILITY | HIGH | HIGH | LOW | MEDIUM |
| RISK | LOW | LOW | MEDIUM | LOW |
| LONG_TERM_MAINTAINABILITY | HIGH | MEDIUM | LOW | HIGH |

**RECOMMENDED_PHYSICAL_MODEL = Candidate A（独立 public/API module）**

**理由**：
- **COMPILE_TIME_ENFORCEMENT = YES** + **CONSUMER_MISUSE_RESISTANCE = HIGH** + **ADB_INTERNAL_HIDING = YES**（满足核心架构 invariant）。
- **GRADLE_COMPLEXITY = LOW** + **MIGRATION_COST = MEDIUM**（可接受的 trade-off）。
- **API_STABILITY = HIGH** + **LONG_TERM_MAINTAINABILITY = HIGH**（长期可维护性最佳）。
- **NAMING_CLARITY = HIGH**（`:at-engine-api` 明确表示 "public API"，`:at-engine` 明确表示 "implementation"）。

---

## 11. Recommended Physical Model（§11 详细设计）

**RECOMMENDED_NEW_MODULES = `:at-engine-api`（1 个新 module）**

**RECOMMENDED_AT_ENGINE_MODULE_ROLE = implementation module（Layer-3）**

**RECOMMENDED_PUBLIC_MODULE = `:at-engine-api`**

**RECOMMENDED_IMPLEMENTATION_MODULE = `:at-engine`（existing）**

**RECOMMENDED_BACKEND_MODULES = `:at-backend-local` / `:at-backend-adb`（existing，unchanged）**

**RECOMMENDED_ADB_ENGINE_VISIBILITY = `:adb-engine` 对普通 Consumer 不可见（implementation 依赖）**

**RECOMMENDED_BOOTSTRAP_VISIBILITY = `AdbConsumerBootstrap` 对普通 Consumer 不可见（implementation 依赖）**

**RECOMMENDED_COMPOSITION_ROOT = `:at-engine` implementation module 提供 composition entrypoint（`ATEngineFactory` 或 `ATComposition`）**

### 11.1 `:at-engine-api` 内容

**Pure contract / facade module**（no Android Context in method signatures, no ADB internals, no backend classes）：

```kotlin
// :at-engine-api

// Execution contract
data class ATExecutionRequest(...)
data class ATExecutionResult(...)
enum class ATExecutionOutcome { COMPLETED, TRANSPORT_ERROR, UNSUPPORTED }

// Session contract
interface ATTerminalSession {
    fun write(bytes: ByteArray): Int
    fun read(timeoutMs: Int): ByteArray?
    fun resize(rows: Int, cols: Int): Boolean
    fun close()
    val state: ATSessionState
}
sealed class ATSessionEvent {
    data class Data(val bytes: ByteArray) : ATSessionEvent()
    data class Exited(val exitCode: Int) : ATSessionEvent()
    object Eof : ATSessionEvent()
    data class Failed(val error: String) : ATSessionEvent()
}
enum class ATSessionState { OPENING, ACTIVE, EXITED, FAILED, CLOSED, EOF }

// Facade
class ATEngine {
    fun execute(request: ATExecutionRequest): ATExecutionResult
    fun openSession(launch: Launch): ATOpenSessionResult
}
data class Launch(val command: String, val initialRows: Int = 24, val initialCols: Int = 80)
sealed class ATOpenSessionResult {
    data class Success(val session: ATTerminalSession) : ATOpenSessionResult()
    data class Failure(val reason: String) : ATOpenSessionResult()
}
```

**关键约束**：
- **NO Android Context** in method signatures（Consumer 不需要理解 Android implementation detail）。
- **NO ADB internals**（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / `AdbConnectionRequest` / ... 不在 `:at-engine-api`）。
- **NO backend classes**（`LocalOneShotBackend` / `LocalPersistentBackend` / `AdbOneShotBackend` / `AdbPersistentBackend` 不在 `:at-engine-api`）。
- **NO backend-facing abstractions**（`ATOneShotBackend` / `ATPersistentBackend` 不在 `:at-engine-api`，保留在 `:at-engine` implementation）。

### 11.2 `:at-engine` 内容（implementation module）

**Layer-3 implementation**（backend-facing abstractions + session implementation + orchestration）：

```kotlin
// :at-engine

// Backend-facing abstractions (NOT exposed to Consumer)
interface ATOneShotBackend {
    fun execute(request: ATExecutionRequest): ATExecutionResult
}
interface ATPersistentBackend {
    data class Launch(val command: String, val initialRows: Int = 24, val initialCols: Int = 80)
    fun openSession(launch: Launch): ATOpenSessionResult
}

// Composition / factory entrypoint
object ATEngineFactory {
    fun createLocal(context: Context): ATEngine  // Local-only
    fun createAdb(context: Context, identityDir: String): ATEngine  // ADB-direct
    fun createFull(context: Context, identityDir: String): ATEngine  // Full terminal (Local + ADB)
}

// Orchestration / backend wiring
// - AdbRuntime.initialize(context)
// - AdbRuntime.connectAuto(identityDir)
// - AdbConsumerBootstrap(identityDir)
// - LocalPersistentBackend() / AdbOneShotBackend(...) / AdbPersistentBackend(...)
```

**关键约束**：
- **`:at-engine` 依赖 `:at-engine-api`**（implementation 实现 contract）。
- **`:at-engine` 依赖 `:at-backend-local` / `:at-backend-adb` / `:adb-engine`**（implementation 使用 backends）。
- **`:at-engine` 不包含 public contract types**（所有 public types 在 `:at-engine-api`）。

### 11.3 Dependency Graph（TO-BE）

```
Consumer (e.g. :app / :at-cli-consumer-host / future AG adapter)
  └── implementation(:at-engine-api)

:at-engine (implementation)
  ├── implementation(:at-engine-api)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-backend-local
  └── implementation(:at-engine-api)

:at-backend-adb
  ├── implementation(:at-engine-api)
  └── implementation(:adb-engine)

:adb-engine
  └── (no project dependencies)

:at-cli (optional frontend)
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)
```

**NO CYCLES** ✓

---

## 12. Public Data Plane（§18）

**DATA PLANE**（Consumer 直接调用）：

```kotlin
// Execution
ATEngine.execute(request: ATExecutionRequest): ATExecutionResult

// Session
ATEngine.openSession(launch: Launch): ATOpenSessionResult
ATTerminalSession.write(bytes: ByteArray): Int
ATTerminalSession.read(timeoutMs: Int): ByteArray?
ATTerminalSession.resize(rows: Int, cols: Int): Boolean
ATTerminalSession.close()
ATTerminalSession.state: ATSessionState
ATSessionEvent (push mode)
```

**关键约束**：
- **NO Android Context** in data plane method signatures。
- **NO ADB internals** in data plane method signatures。
- **NO backend classes** in data plane method signatures。

---

## 13. Public Control Plane（§18）

**CONTROL PLANE**（Consumer 通过 `:at-engine-api` 或 `:at-engine` implementation 调用）：

```kotlin
// Readiness / status
ATEngine.isReady(): Boolean  // or ATEngine.status(): ATStatus
enum class ATStatus { READY, NOT_READY, USER_ACTION_REQUIRED, PAIRING_REQUIRED, CONNECT_FAILED, ENDPOINT_NOT_FOUND }

// Bootstrap / pairing
ATEngine.bootstrap(): ATBootstrap  // returns a bootstrap handle
ATBootstrap.pair(host: String, port: Int, code: String): ATPairingResult
ATBootstrap.discoverPairing(timeoutMs: Long): ATPairingDiscovery
ATBootstrap.hasIdentity(): Boolean

// Diagnostics
ATEngine.diagnostics(): ATDiagnostic
data class ATDiagnostic(val code: String, val message: String, val details: Map<String, String>)
```

**关键约束**：
- **Control plane 在 `:at-engine-api` 中暴露**（Consumer 需要知道 "am I READY?" / "do I need to pair?"）。
- **但 control plane 不暴露 ADB internals**（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / `AdbConnectionRequest` / ... 不在 `:at-engine-api`）。
- **Provider-specific detail 通过 sealed / structured types 暴露**（`ATStatus` / `ATPairingResult` / `ATDiagnostic` 是 generic，不包含 ADB-specific types）。

---

## 14. Provider-specific Capability Boundary（§17）

**问题**：ADB pairing / readiness 是 provider-specific capability，如何物理放置？

**解决方案**：

### Option 1：Generic control contract in `:at-engine-api`

```kotlin
// :at-engine-api

// Generic readiness / bootstrap contract (provider-neutral)
interface ATProviderControl {
    fun isReady(): Boolean
    fun status(): ATStatus
    fun pair(host: String, port: Int, code: String): ATPairingResult
    fun discoverPairing(timeoutMs: Long): ATPairingDiscovery
    fun hasIdentity(): Boolean
    fun diagnostics(): ATDiagnostic
}

// ATEngine exposes the provider control (if available)
class ATEngine {
    val providerControl: ATProviderControl?  // null for Local-only
}
```

**优点**：
- **Backend-neutral**（`ATProviderControl` 是 generic contract，不包含 ADB-specific types）。
- **Consumer 不需要知道 ADB internals**（Consumer 只调用 `providerControl.pair()` / `providerControl.status()`，不直接调用 `AdbConsumerBootstrap.pair()` / `AdbRuntime.status()`）。
- **Future SSH / other providers 可以实现 `ATProviderControl`**（extensible）。

**缺点**：
- **`:at-engine` implementation 需要实现 `ATProviderControl`**（`AdbProviderControl` wraps `AdbConsumerBootstrap` / `AdbRuntime`）。

### Option 2：Optional provider capability interface

```kotlin
// :at-engine-api

// Optional capability (Consumer can query)
interface ATProviderCapability {
    fun supportsPairing(): Boolean
    fun supportsWirelessDebugging(): Boolean
    fun supportsDiscovery(): Boolean
}

// ATEngine exposes the capability (if available)
class ATEngine {
    val providerCapability: ATProviderCapability?  // null for Local-only
}
```

**优点**：
- **Consumer 可以 query capability**（`engine.providerCapability?.supportsPairing()`）。
- **Backend-neutral**（`ATProviderCapability` 是 generic contract）。

**缺点**：
- **Consumer 仍然需要调用 `engine.providerControl.pair()`**（capability query 不解决 pairing 调用问题）。

### Option 3：Sealed provider detail

```kotlin
// :at-engine-api

// Sealed provider detail (Consumer can pattern-match)
sealed class ATProviderDetail {
    data class AdbDetail(
        val wirelessDebuggingEnabled: Boolean,
        val pairingRequired: Boolean,
        val endpointDiscovered: Boolean,
        val connectionState: String,  // "ONLINE" / "OFFLINE" / "CONNECTING"
    ) : ATProviderDetail()
}

// ATEngine exposes the provider detail (if available)
class ATEngine {
    val providerDetail: ATProviderDetail?  // null for Local-only
}
```

**优点**：
- **Consumer 可以 pattern-match provider detail**（`when (engine.providerDetail) { is AdbDetail -> ... }`）。
- **Provider-specific 但 structured**（`AdbDetail` 是 data class，不包含 `AdbRuntime` / `AdbConsumerBootstrap` types）。

**缺点**：
- **`:at-engine-api` 包含 provider-specific types**（`AdbDetail`），违反 "backend-neutral" 原则。
- **Future SSH / other providers 需要新增 `SshDetail` / ...**（sealed class 会不断增长）。

**RECOMMENDED_PROVIDER_CAPABILITY_BOUNDARY = Option 1（Generic control contract in `:at-engine-api`）**

**理由**：
- **Backend-neutral**（`ATProviderControl` 是 generic contract，不包含 ADB-specific types）。
- **Consumer 不需要知道 ADB internals**（Consumer 只调用 `providerControl.pair()` / `providerControl.status()`）。
- **Extensible**（Future SSH / other providers 可以实现 `ATProviderControl`）。
- **`:at-engine` implementation 实现 `ATProviderControl`**（`AdbProviderControl` wraps `AdbConsumerBootstrap` / `AdbRuntime`，但 `:at-engine` 是 implementation module，可以依赖 `:adb-engine`）。

---

## 15. Android Context Physical Boundary（§16）

**当前 Context 进入点**：
- `AdbRuntime.initialize(context)`（`:adb-engine`）
- `AppRuntime.initialize(context)`（`:app`）
- `AtCliConsumerHost.initialize(context)`（`:at-cli-consumer-host`）

**TO-BE 设计**：

### CONSTRUCTION DEPENDENCY（Context allowed）

```kotlin
// :at-engine (implementation module)

object ATEngineFactory {
    // Context is a CONSTRUCTION DEPENDENCY (allowed at composition time)
    fun createLocal(context: Context): ATEngine
    fun createAdb(context: Context, identityDir: String): ATEngine
    fun createFull(context: Context, identityDir: String): ATEngine
}
```

**理由**：
- **Context 是 platform assembly dependency**（wiring Android readiness + NSD discovery backends），不是 execution-semantic input。
- **Composition / factory creation 时允许 Context**（Consumer 在 app startup 时调用 `ATEngineFactory.createFull(context, identityDir)`）。

### RUNTIME PUBLIC API DEPENDENCY（Context hidden）

```kotlin
// :at-engine-api (public contract)

class ATEngine {
    // NO Context in runtime method signatures
    fun execute(request: ATExecutionRequest): ATExecutionResult
    fun openSession(launch: Launch): ATOpenSessionResult
    fun isReady(): Boolean
    fun pair(host: String, port: Int, code: String): ATPairingResult
    // ...
}
```

**理由**：
- **Runtime public method surface 不暴露 Context**（Consumer 不需要在每次调用时传递 Context）。
- **Context 在 composition time 注入一次**（`ATEngineFactory.createFull(context, identityDir)` 内部调用 `AdbRuntime.initialize(context)`，Consumer 不需要知道）。

**ANDROID_CONTEXT_BOUNDARY = Context 在 composition / factory creation 时允许，在 runtime public method surface 隐藏**

---

## 16. Identity Physical Boundary（§22）

**当前 identityDir 传播点**：
- `AppRuntime.initialize(context)` → `adbKeyDir = File(app.filesDir, "adb_identity").absolutePath`
- `AtCliConsumerHost.initialize(context)` → `keyDir = File(app.filesDir, "at-cli-identity").absolutePath`
- `AdbRuntime.connectAuto(keyDir)`
- `AdbRuntime.ensureReady(keyDir, statusListener)`
- `AdbConsumerBootstrap(identityDir)`
- `AdbConnectionRequest(host, port, keyDir)`
- `ATCliExecution.adbIdentityDir()`

**IDENTITY_DIR_PROPAGATION_GRAPH**：
```
AppRuntime.initialize(context)
  └── adbKeyDir = File(app.filesDir, "adb_identity").absolutePath
      ├── AdbRuntime.connectAuto(adbKeyDir)
      ├── AdbRuntime.ensureReady(adbKeyDir, statusListener)
      ├── AdbConsumerBootstrap(adbKeyDir)
      ├── AdbConnectionRequest("127.0.0.1", 0, adbKeyDir)
      └── ATCliExecution.adbIdentityDir() → adbKeyDir

AtCliConsumerHost.initialize(context)
  └── keyDir = File(app.filesDir, "at-cli-identity").absolutePath
      ├── AdbRuntime.connectAuto(keyDir)
      ├── AdbRuntime.ensureReady(keyDir, statusListener)
      ├── AdbConsumerBootstrap(keyDir)
      ├── AdbConnectionRequest("127.0.0.1", 0, keyDir)
      └── ATCliExecution.adbIdentityDir() → keyDir
```

**问题**：Consumer 需要在多个地方传播 `identityDir`，容易出错（例如 `AdbRuntime.connectAuto()` 用了错误的 `identityDir`）。

**TO-BE 设计**：

### Option A：每 operation 继续传

```kotlin
// :at-engine-api
class ATEngine {
    fun connectAuto(identityDir: String): ATDiagnostic
    fun ensureReady(identityDir: String, statusListener: (String) -> Unit): ATStatus
    fun pair(identityDir: String, host: String, port: Int, code: String): ATPairingResult
}
```

**缺点**：Consumer 仍然需要在每个 operation 传递 `identityDir`，容易出错。

### Option B：Composition time 绑定一次

```kotlin
// :at-engine
object ATEngineFactory {
    fun createAdb(context: Context, identityDir: String): ATEngine
}

// :at-engine-api
class ATEngine {
    // identityDir is bound at composition time (not exposed in runtime methods)
    fun connectAuto(): ATDiagnostic
    fun ensureReady(statusListener: (String) -> Unit): ATStatus
    fun pair(host: String, port: Int, code: String): ATPairingResult
}
```

**优点**：
- **Consumer 只在 composition time 传递一次 `identityDir`**（`ATEngineFactory.createAdb(context, identityDir)`）。
- **Runtime methods 不暴露 `identityDir`**（`engine.connectAuto()` / `engine.pair(host, port, code)`）。
- **`:at-engine` implementation 内部绑定 `identityDir`**（`AdbRuntime.connectAuto(identityDir)` / `AdbConsumerBootstrap(identityDir)`）。

**缺点**：
- **`:at-engine` implementation 需要存储 `identityDir`**（`ATEngine` 实例持有 `identityDir`）。

### Option C：Provider config object

```kotlin
// :at-engine-api
data class ATProviderConfig(
    val identityDir: String,
    val wirelessDebuggingEnabled: Boolean = true,
    val discoveryTimeoutMs: Long = 10_000L,
)

// :at-engine
object ATEngineFactory {
    fun createAdb(context: Context, config: ATProviderConfig): ATEngine
}
```

**优点**：
- **Structured config**（`ATProviderConfig` 包含所有 provider-specific settings）。
- **Extensible**（未来可以新增 config fields，例如 `sshConfig` / `usbConfig`）。

**缺点**：
- **`:at-engine-api` 包含 provider-specific types**（`ATProviderConfig` 包含 `identityDir` / `wirelessDebuggingEnabled`，这些是 ADB-specific）。

**RECOMMENDED_IDENTITY_BOUNDARY = Option B（Composition time 绑定一次）**

**理由**：
- **Consumer 只在 composition time 传递一次 `identityDir`**（`ATEngineFactory.createAdb(context, identityDir)`）。
- **Runtime methods 不暴露 `identityDir`**（`engine.connectAuto()` / `engine.pair(host, port, code)`）。
- **Backend-neutral**（`ATEngineFactory` 是 generic factory，不包含 ADB-specific types）。
- **`:at-engine` implementation 内部绑定 `identityDir`**（`AdbRuntime.connectAuto(identityDir)` / `AdbConsumerBootstrap(identityDir)`，但 `:at-engine` 是 implementation module，可以依赖 `:adb-engine`）。

**IDENTITY_CONFIGURATION_BOUNDARY = Composition time 绑定一次，Runtime methods 不暴露 `identityDir`**

---

## 17. CLI Physical Relationship（§23）

**当前 `:at-cli` 角色**：
- `:at-cli` 是 **frontend module**（shell-visible `adb` CLI + same-UID UDS bridge + Local shell `adb` function bootstrap）。
- `:at-cli` 依赖 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine`（implementation 依赖）。
- `:at-cli` 暴露 `ATCliExecution` SPI（host-provided execution capability）。

**问题**：`ATCliExecution` 要求 host 实现 `adbEngine()` / `ensureReady()` / `adbIdentityDir()` / `consumerBootstrap()`，这些是 boundary leakage。

**TO-BE 设计**：`:at-cli` 只依赖 `:at-engine-api`，`ATCliExecution` 简化为 `engine(): ATEngine`（Host 不需要自己编排 ADB lifecycle，ADB lifecycle 由 `:at-engine` implementation 内部处理）。

**RECOMMENDED_ATCLI_TO_BE_DEPENDENCY = `:at-cli` 只依赖 `:at-engine-api` + `:at-engine`，`ATCliExecution` 简化**

---

## 18. AppRuntime Duplication（§24）

**当前 `AppRuntime` 职责分类**：
- **PRODUCT_SPECIFIC**：`CliFrontendProvisioner.provision(app)` / `AdbCliStatusHub`（Human UI status projection）。
- **COMMON_AT_COMPOSITION**：`AdbRuntime.initialize(app)` / `adbKeyDir = File(app.filesDir, "adb_identity").absolutePath`。
- **COMMON_AT_ORCHESTRATION**：`localEngine()` / `adbEngine()` / `connectAuto()` / `consumerBootstrap()`。
- **CLI_SPECIFIC**：`AdbCliBridgeServer` start / `cliBridgeSocketPath()` / `relayInteractiveResize()`。
- **ADB_PROVIDER_SPECIFIC**：`AdbRuntime.connectAuto(adbKeyDir)` / `AdbRuntime.ensureReady(adbKeyDir)`。

**TO-BE**：`COMMON_AT_COMPOSITION` + `COMMON_AT_ORCHESTRATION` 移到 `:at-engine` implementation（`ATEngineFactory`），`:app` 只保留 `PRODUCT_SPECIFIC` + `CLI_SPECIFIC`。

**APP_RUNTIME_COMMON_ORCHESTRATION_TO_MOVE = `AdbRuntime.initialize` / `adbKeyDir` / `localEngine()` / `adbEngine()` / `connectAuto()` / `consumerBootstrap()` → `:at-engine` `ATEngineFactory`**

---

## 19. ConsumerHost Duplication（§25）

**`AtCliConsumerHost` 与 `AppRuntime` 重复**：
- `AdbRuntime.initialize(context)`（重复）
- `keyDir = File(app.filesDir, "at-cli-identity").absolutePath`（重复，identity dir 路径不同）
- `localEngine()` / `adbEngine()`（重复）
- `connectAuto()`（重复）
- `ATCliExecution` 实现（重复，`AppCliExecution` vs `AtCliConsumerHost.cliExecution()`）

**APP_RUNTIME_VS_CONSUMER_HOST_DUPLICATION_MATRIX = HIGH**（~70% 逻辑重复）

**CONSUMER_HOST_COMMON_ORCHESTRATION_TO_MOVE = 同 AppRuntime，移到 `:at-engine` `ATEngineFactory`**

---

## 20. Composition Root Design（§26）

**TO-BE Composition Root = `:at-engine` implementation module 提供 `ATEngineFactory`**

```kotlin
// :at-engine
object ATEngineFactory {
    fun createLocal(context: Context): ATEngine
    fun createAdb(context: Context, identityDir: String): ATEngine
    fun createFull(context: Context, identityDir: String): ATEngine
}
```

**谁负责 wiring**：`:at-engine` implementation（`ATEngineFactory` 内部调用 `AdbRuntime.initialize` / `AdbConsumerBootstrap` / backend construction）。

**谁拥有 one logical composition root / process**：Consumer（`:app` / `:at-cli-consumer-host`）在 app startup 时调用 `ATEngineFactory.createFull(context, identityDir)` 一次，获得 `ATEngine` 实例，进程内共享。

**保持 AdbRuntime singleton owner**：`ATEngineFactory.createAdb()` 内部调用 `AdbRuntime.initialize(context)`（idempotent），`AdbRuntime` 仍是 UNIQUE ADB lifecycle owner。

---

## 21. Compile-time Forbidden Dependency Rules（§27）

**NORMAL_PRODUCT_CONSUMER_ALLOWED_DEPENDENCIES**：
- `:at-engine-api`（public contract / facade）
- `:at-cli`（optional frontend，如果 Consumer 需要 shell-visible adb）

**NORMAL_PRODUCT_CONSUMER_FORBIDDEN_DEPENDENCIES**：
- `:at-engine`（implementation module）
- `:at-backend-local`（Local backend implementation）
- `:at-backend-adb`（ADB backend implementation）
- `:adb-engine`（ADB provider internals）

**ADVANCED_PROVIDER_INTEGRATOR_RULE**：
- 可以额外依赖 `:at-engine` / `:at-backend-local` / `:at-backend-adb`（用于实现自定义 backend）。
- 仍然禁止依赖 `:adb-engine`（ADB provider internals 对 provider integrator 也不可见，除非实现 ADB-specific backend）。

**INTERNAL_AT_MODULE_RULE**：
- `:at-engine` / `:at-backend-local` / `:at-backend-adb` 可以互相依赖（implementation 依赖）。
- `:at-cli` 可以依赖 `:at-engine-api` + `:at-engine` + backends（frontend 需要 wiring）。

---

## 22. Enforcement Strategy（§28）

**RECOMMENDED_ENFORCEMENT_STACK**：

1. **Gradle module boundary**（primary）：
   - Consumer 只依赖 `:at-engine-api`，**无法**直接 import `:adb-engine` / `:at-backend-*`（Gradle `implementation` 语义 + module boundary）。
   - 不需要 Kotlin `internal` / package visibility（Gradle module boundary 已足够）。

2. **Gradle dependency rules**（secondary）：
   - 使用 `dependencyVerification` 或自定义 Gradle task 验证 Consumer 不依赖 forbidden modules。
   - 例如：`:app` / `:at-cli-consumer-host` 的 `build.gradle.kts` 只能有 `implementation(project(":at-engine-api"))` + `implementation(project(":at-cli"))`，不能有 `implementation(project(":adb-engine"))` / `implementation(project(":at-backend-local"))` / `implementation(project(":at-backend-adb"))`。

3. **Architecture test**（tertiary）：
   - 使用 `archunit` 或自定义 test 验证 `:at-engine-api` 不包含 `:adb-engine` / `:at-backend-*` types。
   - 验证 `:at-engine-api` method signatures 不包含 Android Context / ADB internals / backend classes。

4. **Lint / static check**（optional）：
   - 使用 `detekt` 或 `ktlint` 规则验证 `:at-engine-api` 不包含 forbidden imports。

**ARCHITECTURE_TEST_RECOMMENDED = YES**（至少应有一层 compile-time or CI enforcement）

---

## 23. Public Surface Budget（§32）

**普通 Consumer 真正应该看到的概念**（`:at-engine-api` public surface）：

| 概念 | 类型 | 说明 |
|---|---|---|
| Execution request | `ATExecutionRequest` | 命令 + 可选 timeout |
| Execution result | `ATExecutionResult` | outcome + stdout + stderr + exitCode + error |
| Execution outcome | `ATExecutionOutcome` | COMPLETED / TRANSPORT_ERROR / UNSUPPORTED |
| Session launch | `Launch` | command + initialRows + initialCols |
| Session open result | `ATOpenSessionResult` | Success(session) / Failure(reason) |
| Session | `ATTerminalSession` | write / read / resize / close / state |
| Session state | `ATSessionState` | OPENING / ACTIVE / EXITED / FAILED / CLOSED / EOF |
| Session event | `ATSessionEvent` | Data / Exited / Eof / Failed |
| Engine facade | `ATEngine` | execute / openSession / isReady / pair / ... |
| Provider control | `ATProviderControl` | isReady / status / pair / discoverPairing / hasIdentity / diagnostics |
| Provider status | `ATStatus` | READY / NOT_READY / USER_ACTION_REQUIRED / PAIRING_REQUIRED / CONNECT_FAILED / ENDPOINT_NOT_FOUND |
| Pairing result | `ATPairingResult` | success / outcome / deviceGuid / error / endpoint |
| Pairing discovery | `ATPairingDiscovery` | endpoints / unique / serviceNotFound / multiple |
| Diagnostic | `ATDiagnostic` | code / message / details |

**目标**：Consumer public concepts（~15 个）远少于内部 implementation concepts（`AdbRuntime` / `AdbConsumerBootstrap` / `AdbEngine` / `AdbRecoveryCoordinator` / `AdbEndpointStore` / `AdbConnectionRequest` / `AdbInteractiveStream` / `LocalPtySession` / ... 50+ 个）。

---

## 24. Public Type Leak Test（§33）

**TO-BE Layer-2 public method signature 不得要求普通 Consumer import：**

- `AdbRuntime` / `AdbEngine` / `AdbEndpointStore` / `AdbConnectionRequest` / `AdbInteractiveStream` / `LocalPtySession` / ...（Layer-5 types）

**例外**：无（任何 Layer-5 type 出现在 Layer-2 signature = PUBLIC_TYPE_LEAK = YES）

**验证**：
- `ATEngine.execute(request: ATExecutionRequest): ATExecutionResult` ✓（no Layer-5 types）
- `ATEngine.openSession(launch: Launch): ATOpenSessionResult` ✓（no Layer-5 types）
- `ATTerminalSession.write(bytes: ByteArray): Int` ✓（no Layer-5 types）
- `ATEngine.isReady(): Boolean` ✓（no Layer-5 types）
- `ATEngine.pair(host: String, port: Int, code: String): ATPairingResult` ✓（no Layer-5 types）

**PUBLIC_TYPE_LEAK = NO**（TO-BE 满足要求）

---

## 25. Binary / AAR Impact（§34）

**当前 raw AAR gap**：
- `RAW_AAR_SET_CONTENT_KNOWN = YES`（5 AAR: at-engine / at-backend-local / at-backend-adb / adb-engine / at-cli）
- `RAW_AAR_CONSUMPTION_VERIFIED = NO`
- `FORMAL_SELF_CONTAINED_AAR_BUNDLE = NOT_ESTABLISHED`
- `AAR_TRANSITIVITY_METADATA = ABSENT`
- `BASE_APK_MUST_CONTAIN_ATCLIMAIN = YES`

**独立 public API module 对 AAR consumption 的影响**：
- **AAR count**：5 → 6（新增 `:at-engine-api` AAR）。
- **Raw AAR vendoring**：需要多 vendor 1 个 AAR（`at-engine-api-debug.aar`）。
- **Transitive metadata**：仍然 ABSENT（Gradle `implementation` 不生成 POM）。
- **Base.apk requirement**：仍然需要 `com.at.cli.AtCliMain` class（`:at-cli` AAR 提供）。

**AAR_IMPACT = SLIGHTLY_COMPLICATE**（多 1 个 AAR，但 transitive metadata 仍然 ABSENT，需要手动 vendor 完整 6-AAR 集合）

**注意**：本轮不解决 artifact publishing（`FORMAL_SELF_CONTAINED_AAR_BUNDLE` 仍然 NOT_ESTABLISHED，留待未来 `AT.ARTIFACT-BUNDLE-0`）。

---

## 26. AG Impact（§35）

**CURRENT_AG_PHYSICAL_COUPLING**（`D:\codebank\AndCode\AndroidAgent-V2\integration-at`）：
```kotlin
api("com.at.engine:at-engine:1.0.0")
api("com.at.backend.local:at-backend-local:1.0.0")
api("com.at.backend.adb:at-backend-adb:1.0.0")
api("com.adb.engine:adb-engine:1.0.0")
api("com.at.cli:at-cli:1.0.0")
```

**问题**：AG integration adapter 直接依赖所有 AT module（包括 `:adb-engine` / `:at-backend-*`），违反 "AG 只依赖 Layer-2 physical contract" 原则。

**TO_BE_AG_DEPENDENCY**：
```kotlin
implementation("com.at.engine:at-engine-api:1.0.0")  // Layer-2 public contract ONLY
// NO :at-engine / :at-backend-local / :at-backend-adb / :adb-engine / :at-cli
```

**理想目标**：AG integration adapter 只依赖 `:at-engine-api`，不依赖 `:adb-engine` / `:at-backend-adb` / identity internals。AG Core 本身仍禁止 concrete AT dependency。

**AG_TO_BE_PHYSICAL_DEPENDENCY = `:at-engine-api` ONLY**

---

## 27. Product App Impact（§36）

**CURRENT_AT_APP_DEPENDENCIES**（`:app`）：
```kotlin
implementation(project(":at-engine"))
implementation(project(":at-backend-local"))
implementation(project(":at-backend-adb"))
implementation(project(":adb-engine"))
implementation(project(":at-cli"))
```

**问题**：AT 自己的 Product App 直接依赖所有 AT module（包括 `:adb-engine` / `:at-backend-*`），违反 "AT App 必须像真正 Consumer 一样使用 Layer-2 physical boundary" 原则。

**TO_BE_AT_APP_DEPENDENCIES**：
```kotlin
implementation(project(":at-engine-api"))  // Layer-2 public contract
implementation(project(":at-cli"))          // optional frontend (shell-visible adb)
// NO :at-engine / :at-backend-local / :at-backend-adb / :adb-engine
```

**AT_APP_TO_BE_PHYSICAL_DEPENDENCY = `:at-engine-api` + `:at-cli`（optional）**

**关键**：不允许 "AT App 是自己人，所以可以继续穿透"。AT App 必须像真正 Consumer 一样只走 Public boundary，否则 Layer-2 无法获得真实验证。

---

## 28. Conformance Host Strategy（§37）

**CONFORMANCE_HOST_ROLE = RECOMMENDED（future task）**

**建议**：未来建立 `:at-consumer-conformance-host`，只依赖 `:at-engine-api`（+ `:at-cli` optional），证明普通第三方 Consumer 不需要 internal modules。

**Conformance host 覆盖**：
- Local one-shot / Local persistent
- ADB one-shot / ADB persistent
- Readiness / Bootstrap / Identity
- CLI（shell-visible adb）

**与当前 `AtCliConsumerHost` 区分**：
- `AtCliConsumerHost` = REFERENCE_EMBEDDING_HOST + REFERENCE_TERMINAL_ENVIRONMENT（当前最完整的 AT terminal-environment reference，但直接依赖 internal modules）。
- `:at-consumer-conformance-host`（future）= NORMAL_THIRD_PARTY_CONSUMER_CONFORMANCE（只依赖 `:at-engine-api`，证明普通 Consumer 不需要 internal modules）。

**本轮不实现**（留待未来独立任务）。

---

## 29. Compatibility Matrix（§38）

**PHYSICAL_SPLIT_COMPATIBILITY_MATRIX**：

| Capability | Compatible? | Notes |
|---|---|---|
| Local persistent | YES | `:at-engine-api` 提供 `ATEngine.openSession()`，`:at-engine` implementation 内部使用 `LocalPersistentBackend` |
| Local one-shot capability | YES | `LocalOneShotBackend` 类存在（`:at-backend-local`），但未被 Product / reference host wired（`localEngine().oneShot = null`） |
| ADB one-shot | YES | `:at-engine-api` 提供 `ATEngine.execute()`，`:at-engine` implementation 内部使用 `AdbOneShotBackend` |
| ADB persistent | YES | `:at-engine-api` 提供 `ATEngine.openSession()`，`:at-engine` implementation 内部使用 `AdbPersistentBackend` |
| Direct ADB | YES | `:at-engine-api` 提供 `ATEngine.execute()` / `ATEngine.openSession()`，Consumer 不需要直接依赖 `:adb-engine` |
| Shell-visible adb | YES | `:at-cli` 依赖 `:at-engine-api` + `:at-engine`，Consumer 可选依赖 `:at-cli` |
| `adb pair` | YES | `:at-engine-api` 提供 `ATEngine.pair()` / `ATProviderControl.pair()`，`:at-engine` implementation 内部使用 `AdbConsumerBootstrap` |
| First-use bootstrap | YES | `:at-engine-api` 提供 `ATProviderControl.hasIdentity()` / `ATProviderControl.pair()`，`:at-engine` implementation 内部使用 `AdbConsumerBootstrap` |
| Per-consumer identity | YES | `ATEngineFactory.createAdb(context, identityDir)` composition time 绑定一次 |
| Connection reuse | YES | `AdbRuntime` 仍是 UNIQUE ADB lifecycle owner，`:at-engine` implementation 内部复用连接 |
| Recovery | YES | `AdbRecoveryCoordinator` 仍是 UNIQUE recovery owner，`:at-engine` implementation 内部处理 |
| Multi-session | YES | `:at-engine-api` 提供 `ATTerminalSession`，支持 multi-session |
| Future Emulator | YES | Emulator / ScreenState 是 AT Engine capability（Layer-3/4），放在 `:at-engine` implementation，不暴露给 Consumer |
| Future ScreenState | YES | 同 Emulator |
| Future SSH backend | YES | SSH backend 是 Layer-4 adapter（`:at-backend-ssh`），不暴露给 Consumer |

**所有 capability 均 COMPATIBLE** ✓

---

## 30. Freeze Impact（§39）

**EXECUTION_SEMANTICS_CHANGED = NO**（物理重构只移动 ownership / visibility，不改变 execution semantics）

**PRE_EMULATOR_FREEZE_IMPACT = NONE**（Known-Good freeze 不是 final ABI freeze，物理重构不需要重新打开 execution semantic freeze）

**AG_CONSUMER_FREEZE_IMPACT = NONE**（同上）

**CONTRACT_DRAFT_IMPACT = NONE**（AT Contract 仍然 DRAFT，物理重构不影响 contract status）

**ADBRUNTIME_OWNER_CHANGED = NO** / **RECOVERY_OWNER_CHANGED = NO** / **PAIRING_OWNER_CHANGED = NO** / **LOCAL_ADB_SEPARATION_CHANGED = NO** / **CLI_LOCAL_BOUNDARY_CHANGED = NO**

---

## 31. Migration Phases（§40）

**RECOMMENDED_MIGRATION_PHASES**（最小 phase，非一次性大搬家）：

**P0：Module/API skeleton**
- 新增 `:at-engine-api` module（pure contract / facade）。
- 移动 public types（`ATEngine` / `ATExecutionRequest` / `ATExecutionResult` / `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`）到 `:at-engine-api`。
- `:at-engine` 依赖 `:at-engine-api`（implementation）。
- 验证：`:at-engine-api` 不包含 `:adb-engine` / `:at-backend-*` types。

**P1：Composition/orchestration move**
- 在 `:at-engine` 实现 `ATEngineFactory`（`createLocal` / `createAdb` / `createFull`）。
- `ATEngineFactory` 内部调用 `AdbRuntime.initialize` / `AdbConsumerBootstrap` / backend construction。
- 验证：`:at-engine` implementation 可以正确 wiring Local / ADB backends。

**P2：AT App migrate**
- `:app` 改依赖 `:at-engine-api` + `:at-cli`（删除 `:at-engine` / `:at-backend-local` / `:at-backend-adb` / `:adb-engine` 直接依赖）。
- `AppRuntime` 使用 `ATEngineFactory.createFull(context, identityDir)` 代替手动 wiring。
- 验证：`:app` 可以正常构建 + 运行 Local / ADB / CLI 全路径。

**P3：Reference consumer migrate**
- `:at-cli-consumer-host` 改依赖 `:at-engine-api` + `:at-cli`（删除 internal module 直接依赖）。
- `AtCliConsumerHost` 使用 `ATEngineFactory.createFull(context, identityDir)` 代替手动 wiring。
- 验证：`:at-cli-consumer-host` 可以正常构建 + 运行 Local / ADB / CLI 全路径。

**P4：Compile-time dependency enforcement**
- 添加 Gradle dependency rules（验证 Consumer 不依赖 forbidden modules）。
- 添加 architecture test（验证 `:at-engine-api` 不包含 forbidden types）。
- 验证：`:app` / `:at-cli-consumer-host` 无法直接 import `:adb-engine` / `:at-backend-*` types。

**P5：Device regression**
- 真机验证 Local / ADB / CLI 全路径（one-shot / persistent / pairing / bootstrap / recovery）。
- 验证：物理重构后所有 capability 仍然正常工作。

**P6：New consumer handoff**
- 发布 consumer-facing docs（`AT_TO_AG_CONSUMER_HANDOFF_V1` 更新）。
- 发布 AAR bundle（`at-engine-api-debug.aar` + 其他 5 AAR）。
- 验证：第三方 Consumer 可以只依赖 `:at-engine-api` 使用 AT。

---

## 32. Naming Proposal（§42）

**RECOMMENDED_MODULE_NAMES**：
- `:at-engine-api`（public contract / facade module）
- `:at-engine`（implementation module，existing）
- `:at-backend-local`（Local backend adapter，existing）
- `:at-backend-adb`（ADB backend adapter，existing）
- `:adb-engine`（ADB provider engine，existing）
- `:at-cli`（CLI frontend，existing）

**NAMING_STATUS = PROVISIONAL**（不实施，等待 Controller Review）

**理由**：
- `:at-engine-api` 明确表示 "public API"（Consumer 依赖这个 module）。
- `:at-engine` 明确表示 "implementation"（`:at-engine` 依赖 `:at-engine-api` + backends）。
- 其他 module 名字保持不变（`:at-backend-local` / `:at-backend-adb` / `:adb-engine` / `:at-cli`）。

---

## 33. Architecture Diagrams（§43）

### A. CURRENT PHYSICAL DAG

```
:app
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  ├── implementation(:adb-engine)
  └── implementation(:at-cli)

:at-cli-consumer-host
  ├── implementation(:at-cli)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-cli
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-backend-adb
  ├── implementation(:at-engine)
  └── implementation(:adb-engine)

:at-backend-local
  └── implementation(:at-engine)

:at-engine
  └── (no project dependencies)

:adb-engine
  └── (no project dependencies)
```

### B. CURRENT LOGICAL FIVE-LAYER MODEL

```
Layer 1: Consumer / Product Shell
  ├── :app (AppRuntime)
  ├── :at-cli-consumer-host (AtCliConsumerHost)
  └── future AG adapter

Layer 2: AT Engine Public Interface
  └── ATEngine (facade) + public contract types

Layer 3: AT Engine
  ├── execution orchestration
  ├── session implementation
  └── backend-facing abstractions

Layer 4: Backend Adapter
  ├── :at-backend-local (LocalOneShotBackend / LocalPersistentBackend)
  └── :at-backend-adb (AdbOneShotBackend / AdbPersistentBackend)

Layer 5: Execution Mechanism / Provider
  ├── Local PTY (/system/bin/sh -i)
  └── ADB Engine → adbd (AdbEngine / AdbRuntime / AdbConsumerBootstrap)
```

### C. LEAK MAP

```
Layer 2 (ATEngine) → Layer 3/4/5 types LEAKED to Consumer:
  ├── AdbRuntime (Layer-3/4) ← Consumer can import + call
  ├── AdbConsumerBootstrap (Layer-3/4) ← Consumer can import + call
  ├── AdbEngine (Layer-5) ← Consumer can import + call
  ├── AdbOneShotBackend / AdbPersistentBackend (Layer-4) ← Consumer can import + construct
  ├── LocalOneShotBackend / LocalPersistentBackend (Layer-4) ← Consumer can import + construct
  ├── AdbConnectionRequest / AdbInteractiveStream / AdbEndpoint (Layer-5) ← Consumer can import
  └── ATCliExecution.adbEngine() / ensureReady() / adbIdentityDir() / consumerBootstrap() (Layer-2/4) ← Host must implement
```

### D. RECOMMENDED PHYSICAL DAG

```
Consumer (:app / :at-cli-consumer-host / future AG adapter)
  ├── implementation(:at-engine-api)  [Layer-2 public contract]
  └── implementation(:at-cli)         [optional frontend]

:at-cli
  ├── implementation(:at-engine-api)
  ├── implementation(:at-engine)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-engine [Layer-3 implementation]
  ├── implementation(:at-engine-api)
  ├── implementation(:at-backend-local)
  ├── implementation(:at-backend-adb)
  └── implementation(:adb-engine)

:at-backend-local [Layer-4]
  └── implementation(:at-engine-api)

:at-backend-adb [Layer-4]
  ├── implementation(:at-engine-api)
  └── implementation(:adb-engine)

:at-engine-api [Layer-2 public contract]
  └── (no project dependencies)

:adb-engine [Layer-5 provider]
  └── (no project dependencies)
```

### E. CONSUMER COMPILE-TIME VIEW

```
Consumer can import:
  ├── ATEngine (facade)
  ├── ATExecutionRequest / ATExecutionResult / ATExecutionOutcome
  ├── ATTerminalSession / ATSessionEvent / ATSessionState
  ├── ATProviderControl / ATStatus / ATPairingResult / ATPairingDiscovery / ATDiagnostic
  └── (if :at-cli) AtCliMain / AdbCliBridgeServer / LocalCliBootstrap

Consumer CANNOT import:
  ├── AdbRuntime / AdbRuntimeState / AdbRuntimeDiagnostic / AdbRuntimeListener
  ├── AdbConsumerBootstrap / AdbBootstrapState / AdbPairingDiscovery / AdbPairingResultV2
  ├── AdbEngine / AdbConnectionRequest / AdbConnectionState / AdbInteractiveStream
  ├── AdbEndpoint / AdbEndpointSource / AdbDiscoveredService / AdbWirelessDebuggingState
  ├── AdbDiscoveryBackend / AdbAndroidNsdDiscoveryBackend / AdbShellResult / AdbRecoveryPolicy / AdbRecoveryCoordinator
  ├── LocalOneShotBackend / LocalPersistentBackend / LocalPtySession
  ├── AdbOneShotBackend / AdbPersistentBackend
  └── ATOneShotBackend / ATPersistentBackend (backend-facing abstractions)
```

### F. INTERNAL AT IMPLEMENTATION VIEW

```
:at-engine (implementation)
  ├── ATEngineFactory (composition / factory entrypoint)
  ├── AdbProviderControl (implements ATProviderControl, wraps AdbConsumerBootstrap / AdbRuntime)
  ├── ATOneShotBackend / ATPersistentBackend (backend-facing abstractions)
  └── session implementation (LocalTerminalSession / AdbTerminalSession)

:at-backend-local
  ├── LocalOneShotBackend (implements ATOneShotBackend)
  ├── LocalPersistentBackend (implements ATPersistentBackend)
  └── LocalPtySession (provider primitive)

:at-backend-adb
  ├── AdbOneShotBackend (implements ATOneShotBackend)
  └── AdbPersistentBackend (implements ATPersistentBackend)

:adb-engine
  ├── AdbEngine (provider engine)
  ├── AdbRuntime (UNIQUE ADB lifecycle owner)
  ├── AdbConsumerBootstrap (first-use/bootstrap facade)
  ├── AdbRecoveryCoordinator (UNIQUE recovery owner)
  ├── AdbEndpointStore (endpoint discovery / caching)
  └── AdbNative (JNI)
```

---

## 34. Final Report（§49）

```
AT.CONSUMER-PHYSICAL-0 FINAL REPORT

VERDICT = PASS
BASE_HEAD = f55973d
WORKTREE_START = CLEAN（AT.CLI-PATH-FACTCHECK-0 未提交）
WORKTREE_END = CLEAN（新增 AT.CONSUMER-PHYSICAL-0 doc + 本轮交付刷新）

PRODUCTION_CODE_CHANGED = NO
TEST_CODE_CHANGED = NO
GRADLE_CHANGED = NO
MODULE_CREATED = NO
DEVICE_TEST = NO

TERM_A0_5_MODEL = PRESERVED
CURRENT_PHYSICAL_LAYER2 = PARTIAL
CURRENT_AT_ENGINE_MODULE_ROLE = C. API + implementation mixed module

CONSUMER_CAN_IMPORT_ADBRUNTIME = YES（当前）
CONSUMER_CAN_IMPORT_ADBCONSUMERBOOTSTRAP = YES（当前）
CONSUMER_CAN_IMPORT_ADBENGINE = YES（当前）
CONSUMER_CURRENTLY_CONSTRUCTS_BACKENDS = YES
CONSUMER_CURRENTLY_PROPAGATES_IDENTITYDIR = YES

PHYSICAL_SPLIT_NEEDED = PARTIAL
NEW_LAYER2_MODULE_NEEDED = YES
RECOMMENDED_PHYSICAL_MODEL = Candidate A（独立 public/API module）

RECOMMENDED_PUBLIC_MODULE = :at-engine-api
RECOMMENDED_IMPLEMENTATION_MODULE = :at-engine（existing）
RECOMMENDED_BACKEND_MODULES = :at-backend-local / :at-backend-adb（existing，unchanged）
RECOMMENDED_ADB_ENGINE_VISIBILITY = :adb-engine 对普通 Consumer 不可见（implementation 依赖）
RECOMMENDED_BOOTSTRAP_VISIBILITY = AdbConsumerBootstrap 对普通 Consumer 不可见（implementation 依赖）
RECOMMENDED_COMPOSITION_ROOT = :at-engine implementation module 提供 ATEngineFactory

PUBLIC_DATA_PLANE = execute / openSession / write / read / resize / close / events
PUBLIC_CONTROL_PLANE = isReady / status / pair / discoverPairing / hasIdentity / diagnostics

NORMAL_PRODUCT_CONSUMER_ALLOWED_DEPENDENCIES = :at-engine-api + :at-cli（optional）
NORMAL_PRODUCT_CONSUMER_FORBIDDEN_DEPENDENCIES = :at-engine / :at-backend-local / :at-backend-adb / :adb-engine
ADVANCED_PROVIDER_INTEGRATOR_RULE = 可以额外依赖 :at-engine / :at-backend-local / :at-backend-adb（用于实现自定义 backend），仍然禁止 :adb-engine

ANDROID_CONTEXT_BOUNDARY = Context 在 composition / factory creation 时允许，在 runtime public method surface 隐藏
IDENTITY_CONFIGURATION_BOUNDARY = Composition time 绑定一次，Runtime methods 不暴露 identityDir
ATCLI_TO_BE_DEPENDENCY = :at-cli 只依赖 :at-engine-api + :at-engine，ATCliExecution 简化

APP_RUNTIME_COMMON_ORCHESTRATION_TO_MOVE = AdbRuntime.initialize / adbKeyDir / localEngine() / adbEngine() / connectAuto() / consumerBootstrap() → :at-engine ATEngineFactory
CONSUMER_HOST_COMMON_ORCHESTRATION_TO_MOVE = 同 AppRuntime，移到 :at-engine ATEngineFactory

AG_TO_BE_PHYSICAL_DEPENDENCY = :at-engine-api ONLY
AT_APP_TO_BE_PHYSICAL_DEPENDENCY = :at-engine-api + :at-cli（optional）

COMPILE_TIME_ENFORCEMENT = Gradle module boundary + dependency rules + architecture test
ARCHITECTURE_TEST_RECOMMENDED = YES

NEW_MODULE_COUNT = 1（:at-engine-api）
OVER_MODULARIZATION_RISK = LOW

AAR_IMPACT = SLIGHTLY_COMPLICATE（多 1 个 AAR，transitive metadata 仍然 ABSENT）

EXECUTION_SEMANTICS_CHANGED = NO
ADBRUNTIME_OWNER_CHANGED = NO
RECOVERY_OWNER_CHANGED = NO
PAIRING_OWNER_CHANGED = NO
LOCAL_ADB_SEPARATION_CHANGED = NO
CLI_LOCAL_BOUNDARY_CHANGED = NO

PRE_EMULATOR_FREEZE_IMPACT = NONE
AG_CONSUMER_FREEZE_IMPACT = NONE
CONTRACT_DRAFT_IMPACT = NONE

REFERENCE_CONFORMANCE_HOST = RECOMMENDED（future task）
RECOMMENDED_MIGRATION_PHASES = P0-P6（module skeleton → composition move → app migrate → consumer migrate → enforcement → device regression → handoff）
RECOMMENDED_NEXT = AT.CONSUMER-PHYSICAL-1
NEXT_OPEN_AUTHORIZED = NO

DOC = docs/architecture/AT.CONSUMER-PHYSICAL-0_AT_Engine_Physical_Boundary_Design.md
EVIDENCE_INDEX_UPDATED = DEFERRED（AT.CLI-PATH-FACTCHECK-0 未 formal commit，避免 task contamination）
本轮交付 = AT.CONSUMER-PHYSICAL-0_AT_Engine_Physical_Boundary_Design.md（1 file）
COMMIT = NO（AT.CLI-PATH-FACTCHECK-0 未 formal commit，避免 task contamination）
COMMIT_SHA = N/A
TAG = NO
PUSH = NO
STOP = YES
```

---

## 35. Controller Summary（§50，≤15 条）

1. **AT 的逻辑 Layer 2 是否需要物理化**：**YES**（PARTIAL，需要新增 1 个独立 public/API module）。
2. **当前 `:at-engine` 到底是 Layer 2、Layer 3 还是混合**：**C. API + implementation mixed module**（需要拆分）。
3. **当前 Consumer 能否直接看到 AdbRuntime**：**YES**（当前可以，TO-BE 不可以）。
4. **当前 Consumer 是否在自己构造 backend**：**YES**（当前是，TO-BE 不是，由 `ATEngineFactory` 内部构造）。
5. **当前 Consumer 是否自己传播 identityDir**：**YES**（当前是，TO-BE 不是，composition time 绑定一次）。
6. **这些是否构成真实 architecture leakage**：**YES**（Consumer 不应该理解 ADB lifecycle / backend construction / identity propagation）。
7. **package/internal 是否足够，还是需要 Gradle module boundary**：**需要 Gradle module boundary**（package/internal 不够，Consumer 仍然可以 import provider/internal 类型）。
8. **是否应该新增独立 Public/API module**：**YES**（`:at-engine-api`，pure contract / facade）。
9. **如果新增，现有 `:at-engine` 应该承担什么角色**：**implementation module**（Layer-3，backend-facing abstractions + session implementation + orchestration）。
10. **ADB Engine 是否仍独立**：**YES**（`:adb-engine` 保持独立，对普通 Consumer 不可见）。
11. **AdbRuntime ownership 是否保持**：**YES**（UNIQUE ADB lifecycle owner，不变）。
12. **AT App 自己是否也必须只走 Public boundary**：**YES**（`:app` 只依赖 `:at-engine-api` + `:at-cli`，不允许穿透）。
13. **AG 最终应该依赖哪些 physical modules**：**:at-engine-api ONLY**（AG integration adapter 不依赖 `:adb-engine` / `:at-backend-*` / identity internals）。
14. **推荐最小物理重构方案是什么**：**Candidate A**（新增 `:at-engine-api`，移动 public types，`ATEngineFactory` composition entrypoint，Gradle dependency rules + architecture test）。
15. **下一轮是否应该实施**：**NO**（等待 Controller Review，`NEXT_OPEN_AUTHORIZED = NO`）。

**STOP**（按任务要求，等待 Controller Review；不自动开始 AT.CONSUMER-PHYSICAL-1 或其他任务）。
