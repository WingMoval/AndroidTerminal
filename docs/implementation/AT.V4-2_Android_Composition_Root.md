# AT.V4-2 — Android Composition Root

## 1. Baseline

**START_HEAD** = `09f2ac9` (AT.V4-1: extract engine API and backend SPI)
**WORKTREE** = CLEAN

## 2. Architecture Authorities

- ARCHITECTURE_AUTHORITY_1: TERM.A0.5_AT_Engine_Architecture_Synthesis.md
- ARCHITECTURE_AUTHORITY_2: AndroidTerminal_Canonical_V1.md
- V4 PHYSICAL SSOT: docs/AT.V4_MASTER_SSOT.md
- V4-1 INPUT: docs/AT.V4-1_Engine_API_SPI_Extraction.md

## 3. Current Composition Audit

### APP_RUNTIME_COMPOSITION_RESPONSIBILITIES
- AdbRuntime.initialize(context) — ADB lifecycle wiring
- Identity dir creation (filesDir/adb_identity)
- Local backend construction (LocalPersistentBackend)
- ADB backend construction (AdbOneShotBackend + AdbPersistentBackend)
- DefaultATEngine construction (localEngine + adbEngine)
- CLI bridge provisioning (product-specific)
- Status hub (product-specific)

### CONSUMER_HOST_COMPOSITION_RESPONSIBILITIES
- AdbRuntime.initialize(context) — ADB lifecycle wiring
- Identity dir creation (filesDir/at-cli-identity)
- Local backend construction (LocalPersistentBackend)
- ADB backend construction (AdbOneShotBackend + AdbPersistentBackend)
- DefaultATEngine construction (localEngine + adbEngine)
- CLI bridge (consumer-specific)

### COMMON_COMPOSITION_RESPONSIBILITIES
- AdbRuntime.initialize(context)
- Identity dir creation (app-private)
- Local backend construction
- ADB backend construction
- DefaultATEngine construction

### PRODUCT_SPECIFIC_RESPONSIBILITIES
- CLI bridge provisioning (CliFrontendProvisioner)
- Status hub (AdbCliStatusHub)
- Session registry
- Resize relay

### CLI_SPECIFIC_RESPONSIBILITIES
- CLI bridge server
- LocalCliBootstrap
- ATCliExecution implementation

## 4. AppRuntime Responsibility Matrix

| Responsibility | Type | V4-2 Action |
|---|---|---|
| AdbRuntime.initialize | Common | MOVE_IN_V4_2 |
| Identity dir creation | Common | MOVE_IN_V4_2 |
| Local backend construction | Common | MOVE_IN_V4_2 |
| ADB backend construction | Common | MOVE_IN_V4_2 |
| DefaultATEngine construction | Common | MOVE_IN_V4_2 |
| CLI bridge provisioning | Product-specific | KEEP_PRODUCT_SPECIFIC_V4_4 |
| Status hub | Product-specific | KEEP_PRODUCT_SPECIFIC_V4_4 |
| Session registry | Product-specific | KEEP_PRODUCT_SPECIFIC_V4_4 |
| Resize relay | Product-specific | KEEP_PRODUCT_SPECIFIC_V4_4 |

## 5. ConsumerHost Responsibility Matrix

| Responsibility | Type | V4-2 Action |
|---|---|---|
| AdbRuntime.initialize | Common | MOVE_IN_V4_2 |
| Identity dir creation | Common | MOVE_IN_V4_2 |
| Local backend construction | Common | MOVE_IN_V4_2 |
| ADB backend construction | Common | MOVE_IN_V4_2 |
| DefaultATEngine construction | Common | MOVE_IN_V4_2 |
| CLI bridge server | CLI-specific | KEEP_CLI_SPECIFIC_V4_4 |
| LocalCliBootstrap | CLI-specific | KEEP_CLI_SPECIFIC_V4_4 |
| ATCliExecution implementation | CLI-specific | KEEP_CLI_SPECIFIC_V4_4 |

## 6. ATEngine Provider Model

**ATEngine_PROVIDER_MODEL** = SINGLE_PROVIDER_INSTANCE

Each `DefaultATEngine` instance represents ONE backend/provider pair:
- `localEngine()` = DefaultATEngine(LocalOneShotBackend, LocalPersistentBackend)
- `adbEngine()` = DefaultATEngine(AdbOneShotBackend, AdbPersistentBackend)

## 7. Composition Return Model

**COMPOSITION_RETURN_MODEL** = TWO_ENGINE_HANDLE

The composition returns `ATAndroidComposition` with:
- `localEngine: ATEngine` — Local provider
- `adbEngine: ATEngine` — ADB provider

**SEMANTIC_DELTA** = NONE

## 8. Provider Wiring A/B Comparison

### Option A: Composition Root directly depends on :adb-engine
```
:at-composition-android
  → :at-engine-api
  → :at-engine-spi
  → :at-engine
  → :at-backend-local
  → :at-backend-adb
  → :adb-engine
```
**Pros:**
- Assembly Root can know concrete implementations
- No new factory layer in :at-backend-adb
- Minimal semantic change
- No new lifecycle ownership

**Cons:**
- :at-composition-android depends on :adb-engine (acceptable for assembly root)

### Option B: ADB backend module provides narrow internal creation surface
```
:at-composition-android
  → :at-backend-adb (with factory)
```
**Pros:**
- :at-composition-android doesn't depend on :adb-engine directly

**Cons:**
- :at-backend-adb becomes a new composition scope
- May introduce new lifecycle ownership issues
- Increases backend module complexity

## 9. Selected Provider Wiring Model

**PROVIDER_WIRING_MODEL** = A

**WHY:**
- Assembly Root can legitimately know concrete implementations
- No new factory layer needed
- Minimal semantic change
- No new lifecycle ownership
- :at-composition-android → :adb-engine is an ASSEMBLY-ROOT INTERNAL DEPENDENCY, not a Consumer boundary violation

## 10. Composition Module DAG

```
:at-engine-api
  → none

:at-engine-spi
  → :at-engine-api

:at-engine
  → :at-engine-api + :at-engine-spi

:at-backend-local
  → :at-engine-api + :at-engine-spi

:at-backend-adb
  → :at-engine-api + :at-engine-spi + :adb-engine

:at-composition-android
  → :at-engine-api + :at-engine-spi + :at-engine + :at-backend-local + :at-backend-adb + :adb-engine
```

## 11. Context Boundary

**CONTEXT_BOUNDARY** = CREATION_ONLY

Context is accepted ONLY in `ATAndroid.create(context)`. After construction:
- `ATAndroidComposition` does NOT hold a Context reference
- `ATEngine` instances do NOT require Context
- No Context leaks to Layer-2

## 12. Identity Boundary

**IDENTITY_BINDING_MODEL** = PER_CONSUMER_APP_PRIVATE

- Identity directory: `context.applicationContext.filesDir/adb_identity`
- SELF_GENERATED (never copied from PC)
- NO_KEY_COPY
- Each consumer app owns its own app-private identity

**IDENTITY_PATH_SEMANTIC_CHANGED** = NO

The identity path semantics are unchanged from V3/V4-1:
- App: `filesDir/adb_identity`
- ConsumerHost: `filesDir/at-cli-identity` (will be unified in V4-4)

**IDENTITYDIR_PUBLIC_LEAK** = NO

`identityDir` is NOT a public construction input. It is determined internally from `context.filesDir`.

## 13. AdbRuntime Ownership

**ADB_RUNTIME_OWNER** = UNCHANGED

- AdbRuntime remains the UNIQUE lifecycle owner
- Composition only calls `AdbRuntime.initialize(context)` (idempotent)
- NO SecondAdbRuntime
- NO RuntimeManager
- NO ConnectionManager

**SECOND_ADB_RUNTIME** = NO

## 14. Local Wiring

**LOCAL_REQUIRES_ADB** = NO
**LOCAL_REQUIRES_ADBRUNTIME** = NO
**LOCAL_REQUIRES_IDENTITY** = NO
**LOCAL_REQUIRES_WD** = NO
**LOCAL_REQUIRES_PAIRING** = NO

Local engine is ALWAYS available, even if ADB initialization fails:
```kotlin
val localEngine: ATEngine = DefaultATEngine(
    oneShot = LocalOneShotBackend(),
    persistent = LocalPersistentBackend(),
)
```

## 15. ADB Wiring

ADB engine is constructed with a placeholder connection:
```kotlin
val connection = AdbConnectionRequest("127.0.0.1", 0, identityPath)
val adbEngine: ATEngine = DefaultATEngine(
    oneShot = AdbOneShotBackend(connection),
    persistent = AdbPersistentBackend(connection),
)
```

- Port 0 = placeholder (reuse lifecycle connection)
- Actual connection established by `AdbRuntime.connectAuto()`
- NO new connection lifecycle logic in composition
- NO retry/discovery/pairing/recovery reimplementation

## 16. Bootstrap Boundary

**AdbConsumerBootstrap** remains the first-use bootstrap implementation.

- NOT exposed in composition public API
- NOT added to Layer-2 ATEngine API
- DEFERRED for future composition control surface

## 17. Public Leak Audit

**COMPOSITION_PUBLIC_TYPES** = `ATAndroid`, `ATAndroidComposition`

**Allowed to see:**
- Context (creation only)
- ATEngine (Layer-2 interface)
- ATAndroidComposition

**NOT allowed to see:**
- ~~AdbRuntime~~
- ~~AdbEngine~~
- ~~AdbConsumerBootstrap~~
- ~~LocalOneShotBackend~~
- ~~LocalPersistentBackend~~
- ~~AdbOneShotBackend~~
- ~~AdbPersistentBackend~~
- ~~DefaultATEngine~~
- ~~identityDir~~
- ~~File identity path~~

**DEFAULT_AT_ENGINE_PUBLIC_LEAK** = NO
**BACKEND_PUBLIC_LEAK** = NO
**ADB_INTERNAL_PUBLIC_LEAK** = NO

## 18. Build

```text
assembleDebug: BUILD SUCCESSFUL
test: BUILD SUCCESSFUL
:at-composition-android:test: BUILD SUCCESSFUL
```

## 19. Unit Tests

C1-C8 static/structural tests:
- C4: returned engine type = ATEngine interface ✓
- C5: Consumer-visible handle does not expose DefaultATEngine ✓
- C6: Consumer-visible handle does not expose backend concrete classes ✓
- C7: identityDir is not a public construction input ✓
- C8: Context does not enter :at-engine-api ✓

## 20. Device Smoke

**DEVICE_TEST** = BLOCKED_BY_ENVIRONMENT

Device `APLMUT4312005935` (HONOR ALI-AN00) is NOT connected.

**BLOCKER** = Environment readiness (device not connected), NOT composition construction failure.

D1-D6 require a connected device with existing app-private identity + WD READY.

## 21. V3 Semantic Parity

- AdbRuntime ownership: UNCHANGED
- Recovery ownership: UNCHANGED
- Pairing ownership: UNCHANGED
- Local/ADB semantics: UNCHANGED
- CLI semantics: UNCHANGED

**SEMANTIC_DELTA** = NONE

## 22. V4-3 Remaining Scope

**V4_3_REMAINING_SCOPE** = NONE

V4-1 already completed:
- Backend SPI extraction
- Backend modules depend on SPI, not engine
- Engine does not depend on concrete backends

V4-2 completed:
- Android composition root
- Provider wiring closure
- Identity binding

**V4_3_REQUIRED** = NO

**RECOMMENDED_NEXT** = AT.V4-4 (CLI / Product App migration)

## 23. Final Verdict

**VERDICT** = PASS (with device smoke BLOCKED_BY_ENVIRONMENT)

- Module created: `:at-composition-android`
- Provider wiring model: A
- Composition public API: clean (no leaks)
- Build: PASS
- Unit tests: PASS
- Device smoke: BLOCKED_BY_ENVIRONMENT (device not connected)
- V3 semantic parity: PASS
- V4-3 scope: ABSORBED
