AT.V4-5 — Baseline + Architecture Inventory
Date: 2026-10-22
Baseline: HEAD ae9f0ad, worktree CLEAN

## Frozen state (NOT reopened)
```
AT.V4-4.2 = COMPLETE / DEVICE_CLOSURE = CLOSED
AT.V4-4.3 = COMPLETE / POST_BOOTSTRAP_AUTO_WD = PASS
```

## Module inventory (from settings.gradle.kts)
| Module | Role |
|--------|------|
| :adb-engine | ADB Engine (lifecycle owner: AdbRuntime / AdbEngine / AdbConsumerBootstrap) |
| :at-engine-api | Public consumer contract (Layer-2): ATEngine / ATExecution / ATSession |
| :at-engine-spi | Backend SPI (Layer-3/4 implementation contract) |
| :at-engine | Engine implementation (DefaultATEngine) |
| :at-backend-local | Local backend (LocalOneShotBackend / LocalPersistentBackend) |
| :at-backend-adb | ADB backend (AdbOneShotBackend / AdbPersistentBackend) |
| :at-composition-android | Android assembly root (ATAndroid.create + ATAndroidAdbControl + semantic types) |
| :at-cli | Reusable CLI (AdbCliBridgeServer / LocalCliBootstrap / ATCliExecution SPI) |
| :app | Product Human UI (consumer) |
| :at-cli-consumer-host | Independent CLI consumer test host (consumer) |
| :at-consumer-conformance-host | (NEW) Reference consumer + conformance fixture |

## Canonical Consumer Boundary (frozen from SSOT)
```
Consumer
├── :at-engine-api          (REQUIRED)
├── :at-composition-android (REQUIRED)
└── :at-cli                 (OPTIONAL)
```

## Forbidden direct consumer dependencies
```
:at-engine / :at-engine-spi / :at-backend-local / :at-backend-adb / :adb-engine
```

## Consumer-facing public surface (verified by source audit)

### :at-engine-api (PURE CONSUMER CONTRACT)
- `ATEngine` interface: `execute(ATExecutionRequest): ATExecutionResult`, `openSession(ATSessionLaunch): ATOpenSessionResult`
- `ATExecutionRequest(command, timeoutMs)` / `ATExecutionResult(outcome, stdout, stderr, exitCode, ...)`
- `ATSessionLaunch(command, rows, cols, env)` / `ATTerminalSession` / `ATSessionEvent` / `ATSessionState`
- NO Context / AdbRuntime / AdbEngine / backend / SPI / identityDir in public surface ✓

### :at-composition-android (Android assembly root)
- `ATAndroid.create(context, config): ATAndroidComposition`
- `ATAndroidComposition`: `localEngine: ATEngine` / `adbEngine: ATEngine` / `adbControl: ATAndroidAdbControl`
- `ATAndroidConfig(identityProfile)` — identity is app-private, self-generated
- `ATAndroidAdbControl` (consumer semantic facade, PROJECTION_ONLY):
  - `readinessState(): ATAdbReadinessState`
  - `bootstrapState(): ATAdbBootstrapState`
  - `hasIdentity(): Boolean`
  - `discoverPairing(): ATAdbPairingDiscovery`
  - `pair(host, port, code): ATAdbPairingResult`
  - `pairDiscovered(code): ATAdbPairingResult`
  - `ensureReady(onStatusLine): Boolean`
  - `completeFirstUseBootstrap(): FirstUseBootstrapResult`
- Semantic types (PURE MAPPING, no provider type in consumer signature):
  - `ATAdbReadinessState` / `ATAdbBootstrapState` / `ATAdbPairingDiscovery` / `ATAdbPairingResult`

### :at-cli (optional frontend)
- `AdbCliBridgeServer(filesDir, execution)` — same-UID UDS, native `atcli_env`
- `LocalCliBootstrap(mainClass)` — shell-visible `adb` function bootstrap
- `ATCliExecution` (host-provided SPI): `adbEngine()` / `ensureReady()` / `pairingState()` / `pair()` / `pairDiscovered()`

## Contract gap assessment
NO CONSUMER_CONTRACT_GAP. A legal consumer using only
`:at-engine-api` + `:at-composition-android` + `:at-cli` can complete:
- Local one-shot / persistent (localEngine)
- ADB one-shot / persistent (adbEngine)
- Readiness (adbControl.readinessState)
- Bootstrap (adbControl.bootstrapState / completeFirstUseBootstrap)
- Identity semantic (adbControl.hasIdentity — self-generated, NOT exposed as identityDir)
- CLI shell-visible adb (AdbCliBridgeServer + LocalCliBootstrap + ATCliExecution)

AT_ENGINE_PUBLIC_API_CHANGE = NO (no expansion needed).
