# AT.V4-4 — Consumer Boundary Closure

## 1. Baseline

**START_HEAD** = `23889a5` (AT.V4-2: establish Android composition root)
**WORKTREE** = CLEAN

## 2. Consumer Audit

### APP_DIRECT_INTERNAL_IMPORTS (BEFORE)
- `com.adb.engine.AdbRuntime` (AppRuntime, AppCliExecution)
- `com.adb.engine.AdbConsumerBootstrap` (AppRuntime, AppCliExecution, PairActivity)
- `com.adb.engine.AdbBootstrapState` (PairActivity)
- `com.adb.engine.AdbPairingResultV2` (PairActivity)
- `com.at.backend.adb.AdbOneShotBackend` (AppRuntime)
- `com.at.backend.adb.AdbPersistentBackend` (AppRuntime)
- `com.at.backend.local.LocalPersistentBackend` (AppRuntime)
- `com.at.engine.impl.DefaultATEngine` (AppRuntime, AppTerminal)

### CONSUMER_HOST_DIRECT_INTERNAL_IMPORTS (BEFORE)
- `com.adb.engine.AdbRuntime` (AtCliConsumerHost)
- `com.adb.engine.AdbConsumerBootstrap` (AtCliConsumerHost)
- `com.at.backend.adb.AdbOneShotBackend` (AtCliConsumerHost)
- `com.at.backend.adb.AdbPersistentBackend` (AtCliConsumerHost)
- `com.at.backend.local.LocalPersistentBackend` (AtCliConsumerHost)
- `com.at.engine.impl.DefaultATEngine` (AtCliConsumerHost)

### CLI_INTERNAL_TYPE_DEPENDENCIES (BEFORE)
- `com.adb.engine.AdbConsumerBootstrap` (ATCliExecution.consumerBootstrap())

## 3. Old Dependency Graph

```
:app
  → :at-engine
  → :at-engine-spi
  → :at-backend-local
  → :at-backend-adb
  → :adb-engine
  → :at-cli

:at-cli-consumer-host
  → :at-engine
  → :at-engine-spi
  → :at-backend-local
  → :at-backend-adb
  → :adb-engine
  → :at-cli

:at-cli
  → :at-engine-api
  → :adb-engine (for AdbConsumerBootstrap)
```

## 4. Control-Plane Requirement Matrix

| Requirement | Owner | Consumer needs |
|---|---|---|
| ensureReady | AdbRuntime.ensureReady | YES |
| readinessState | AdbRuntime.readinessState | YES |
| hasIdentity | AdbConsumerBootstrap | YES |
| bootstrapState | AdbConsumerBootstrap | YES |
| discoverPairing | AdbConsumerBootstrap | YES |
| pair | AdbEngine.pair (via bootstrap) | YES |
| pairDiscovered | AdbConsumerBootstrap | YES |

## 5. New adbControl Design

**CONTROL_SURFACE_NAME** = `ATAndroidAdbControl`

**CONTROL_SURFACE_OWNER** = PROJECTION_ONLY

The `ATAndroidAdbControl` is a consumer semantic facade that delegates to:
- `AdbRuntime` (lifecycle owner)
- `AdbConsumerBootstrap` (first-use bootstrap implementation)
- `AdbEngine.pair` (pairing primitive)

It does NOT:
- own the ADB lifecycle
- own recovery
- own pairing
- create a second AdbRuntime

Public surface:
- `bootstrapState(): AdbBootstrapState`
- `hasIdentity(): Boolean`
- `discoverPairing(timeoutMs): AdbPairingDiscovery`
- `pair(host, port, code): AdbPairingResultV2`
- `pairDiscovered(code, timeoutMs): AdbPairingResultV2`
- `ensureReady(onStatusLine): Boolean`
- `readinessState(): AdbRuntimeState`

## 6. Ownership Proof

- **NORMAL_READINESS_OWNER** = AdbRuntime.ensureReady (UNCHANGED)
- **BOOTSTRAP_OWNER** = AdbConsumerBootstrap (UNCHANGED)
- **PAIRING_PRIMITIVE_OWNER** = AdbEngine.pair (UNCHANGED)
- **RECOVERY_OWNER** = AdbRuntime + AdbRecoveryCoordinator (UNCHANGED)

## 7. Identity Compatibility

**APP_IDENTITY_PATH** = `filesDir/adb_identity` (PRESERVED)
**CONSUMER_HOST_IDENTITY_PATH** = `filesDir/at-cli-identity` (PRESERVED)

**APP_IDENTITY_PATH_PRESERVED** = YES
**CONSUMER_HOST_IDENTITY_PATH_PRESERVED** = YES

**KEY_COPY** = NO
**MIGRATION_REPAIR_REQUIRED** = NO

The `ATAndroidConfig(identityProfile = "...")` allows each consumer to specify
its own identity profile without changing the physical path.

## 8. ATCliExecution Old/New Surface

**OLD**:
- `adbEngine(): ATEngine`
- `ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean`
- `adbIdentityDir(): String`
- `consumerBootstrap(): AdbConsumerBootstrap`

**NEW**:
- `adbEngine(): ATEngine`
- `ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean`
- `pairingState(): String`
- `pair(host: String, port: Int, code: String): String`
- `pairDiscovered(code: String): String`

Removed:
- `adbIdentityDir()` (identity is now internal to the composition)
- `consumerBootstrap()` (pairing is now delegated to the host's adbControl)

## 9. ConsumerHost Migration

**CONSUMER_HOST_COMMON_WIRING_REMOVED** = YES

AtCliConsumerHost now uses `ATAndroid.create(context)` to obtain the composition.
It no longer:
- creates AdbRuntime wiring directly
- propagates identityDir
- constructs Local/ADB backends
- constructs DefaultATEngine

Preserved for test compatibility:
- `adbIdentityDir()`
- `consumerBootstrap()`
- `adbState()`
- `connectAuto()`

## 10. AppRuntime Migration

**APP_RUNTIME_COMMON_WIRING_REMOVED** = YES

AppRuntime now uses `ATAndroid.create(context)` to obtain the composition.
It no longer:
- creates AdbRuntime wiring directly
- constructs Local/ADB backends
- constructs DefaultATEngine

Preserved (Product-specific):
- CLI bridge provisioning (CliFrontendProvisioner)
- Status hub (AdbCliStatusHub)
- Session registry
- Resize relay
- `adbIdentityDir()` (for test compatibility)
- `consumerBootstrap()` (for test compatibility)

## 11. PairActivity Migration

PairActivity now uses `AppRuntime.adbControl` for:
- `hasIdentity()`
- `bootstrapState()`
- `discoverPairing()`
- `pairDiscovered()`

It no longer directly uses:
- `AdbConsumerBootstrap` concrete class
- `adbIdentityDir()`

## 12. Status Migration

AdbCliStatusHub remains in :app (Product/UI concern).
The new `adbControl` provides semantic sources for:
- readiness state
- pairing state

Status projection semantics are UNCHANGED.

## 13. Final Gradle DAG

```
:app
  → :at-engine-api
  → :at-composition-android
  → :at-cli
  → :adb-engine (TRANSITIONAL, for AdbRuntimeState/AdbBootstrapState types)

:at-cli-consumer-host
  → :at-engine-api
  → :at-composition-android
  → :at-cli
  → :adb-engine (TRANSITIONAL, for AdbRuntimeState/AdbConsumerBootstrap types)

:at-cli
  → :at-engine-api
  (no direct :adb-engine dependency)
```

## 14. Import Audit

**APP_FORBIDDEN_IMPORTS** = 0
**CONSUMER_HOST_FORBIDDEN_IMPORTS** = 0
**CLI_FORBIDDEN_IMPORTS** = 0

Forbidden patterns checked:
- `com.at.engine.impl.*`
- `com.at.backend.local.*`
- `com.at.backend.adb.*`
- `com.adb.engine.AdbConnectionRequest`
- `com.adb.engine.AdbEndpoint`

## 15. Build

```text
assembleDebug: BUILD SUCCESSFUL
test: BUILD SUCCESSFUL
```

## 16. Tests

All unit tests PASS.

## 17. Product Local Device Smoke

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

Device `APLMUT4312005935` (HONOR ALI-AN00) is NOT connected.

L1-L7 require a connected device.

## 18. Product ADB Device Smoke

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

A1-A10 require a connected device with existing identity + WD READY.

## 19. CLI Device Smoke

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

C1-C6 require a connected device.

## 20. ConsumerHost Smoke

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

H1-H7 require a connected device.

## 21. Restart Identity Reuse

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

Requires a connected device.

## 22. Ownership Evidence

- **SECOND_ADB_RUNTIME** = NO
- **SECOND_RECOVERY_OWNER** = NO
- **SECOND_TRANSPORT_OBSERVER** = NO
- **SECOND_TLS_OWNER** = NO

## 23. Semantic Parity

- AdbRuntime ownership: UNCHANGED
- Recovery ownership: UNCHANGED
- Pairing ownership: UNCHANGED
- Local/ADB semantics: UNCHANGED
- CLI semantics: UNCHANGED

**SEMANTIC_DELTA** = NONE

## 24. Remaining Debt

1. `:app` and `:at-cli-consumer-host` still have TRANSITIONAL dependency on `:adb-engine`
   for `AdbRuntimeState` / `AdbBootstrapState` / `AdbConsumerBootstrap` types.
   These should be projected in `:at-composition-android` in a future task.

2. `ATCliExecution` uses `String` for `pairingState()` and `pair()` / `pairDiscovered()`
   return values. A structured result type would be preferable but would require
   adding types to `:at-engine-api` or a new shared types module.

## 25. Final Verdict

**VERDICT** = DEVICE_CLOSURE_PENDING

- Code migration: COMPLETE
- Build: PASS
- Unit tests: PASS
- Static import audit: PASS
- Device smoke: BLOCKED_BY_ENVIRONMENT (device not connected)

**AT_V4_4_STATUS** = IMPLEMENTATION_COMPLETE_DEVICE_CLOSURE_PENDING
**V4_5_READY** = NO (device closure required)
