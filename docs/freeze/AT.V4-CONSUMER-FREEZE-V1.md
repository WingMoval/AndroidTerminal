AT.V4-CONSUMER-FREEZE-V1 — Consumer Freeze Manifest
Date: 2026-10-22

## Freeze Identity
```
FREEZE_COMMIT = 532f816 (AT.V4-5: close consumer conformance and architecture enforcement)
FREEZE_TAG = AT.V4-CONSUMER-FREEZE-V1
DATE = 2026-10-22
```

## Allowed Consumer Dependencies
```
:at-engine-api           (REQUIRED)
:at-composition-android  (REQUIRED)
:at-cli                  (OPTIONAL)
```

## Forbidden Consumer Dependencies
```
:at-engine
:at-engine-spi
:at-backend-local
:at-backend-adb
:adb-engine
```

## Public API Surface Status
```
AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0
FORBIDDEN_DIRECT_DEPENDENCY_COUNT = 0
FORBIDDEN_CONSUMER_IMPORT_COUNT = 0
MODULE_DAG_CYCLE_COUNT = 0
AT_ENGINE_PUBLIC_API_CHANGE = NO
```

## Composition Entrypoint
```
ATAndroid.create(context, ATAndroidConfig(identityProfile))
```

## Ownership
```
AdbRuntime              = UNIQUE lifecycle owner
AdbRecoveryCoordinator  = UNIQUE recovery owner
AdbEngine.pair          = pairing primitive
AdbConsumerBootstrap    = first-use bootstrap implementation
AT Engine               = execution semantics
```

## Identity Semantics
```
ONE canonical identity per Consumer
self-generated / app-private / persistent
KEY_COPY = NO / PC_KEY_REUSE = NO / SHARED_PRIVATE_KEY = NO
```

## Bootstrap Semantics
```
First-use: manual WD → pair once → self-grant WRITE_SECURE_SETTINGS → READY
Subsequent: WD OFF → auto-WD → discovery → connect → READY
POST_BOOTSTRAP_AUTO_WD = PASS
```

## CLI Surface
```
adb shell <cmd>       (one-shot)
adb shell             (interactive)
adb pair <host>:<port> <code>  (pairing)
```

## Architecture Gate Result
```
ARCHITECTURE_GATE_FINAL = PASS
CONSUMER_CONTRACT_GAP = NO
ARCHITECTURE_REGRESSION = NO
```

## Device Parity Result
```
V3_V4_DEVICE_PARITY = PASS
V3_V4_SEMANTIC_DELTA = NONE
All P1-P18 gates = PASS
```

## Artifact Inventory
```
8 artifacts (6 AAR + 2 JAR)
3 native libraries (libat_local_backend.so / libadbd_engine.so / libatcli_env.so)
ARTIFACT_DEPENDENCY_CLOSURE = PASS
NATIVE_ARTIFACT_CLOSURE = PASS
SHA256SUMS.txt = PRESENT
```

## Known Deferred Work
```
AT.EMULATOR-0       = DEFERRED (Terminal Emulator)
AT.ARTIFACT-BUNDLE-0 = DEFERRED (Maven publication / artifact redesign)
AG.INTEGRATION-0    = DEFERRED (AndroidAgent integration)
AT.CONTRACT-1       = DEFERRED (final API freeze)
```

## Freeze Meaning
This tag freezes the V4 CONSUMER BOUNDARY, not the AT Contract.
Future V5 may evolve the public API. This tag marks the point at which
the V4 architecture is physically complete, device-verified, and
consumer-ready.
