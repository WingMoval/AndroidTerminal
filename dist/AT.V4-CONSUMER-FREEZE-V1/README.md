AT.V4-CONSUMER-FREEZE-V1 — AAR Bundle Manifest
Date: 2026-10-22
Freeze Commit: 532f816 (AT.V4-5)
Freeze Tag: AT.V4-CONSUMER-FREEZE-V1

## Purpose
This bundle contains the Gradle module artifacts needed for an external
Consumer to build against the V4 AndroidTerminal Consumer Boundary.

## Contents

### artifacts/
| File | Type | Module | Role |
|------|------|--------|------|
| at-engine-api.jar | JAR | :at-engine-api | REQUIRED — public consumer contract |
| at-composition-android-debug.aar | AAR | :at-composition-android | REQUIRED — Android assembly root |
| at-cli-debug.aar | AAR | :at-cli | OPTIONAL — CLI frontend |
| at-engine-debug.aar | AAR | :at-engine | internal (DefaultATEngine) |
| at-engine-spi.jar | JAR | :at-engine-spi | internal (backend SPI) |
| at-backend-local-debug.aar | AAR | :at-backend-local | internal (Local backend) |
| at-backend-adb-debug.aar | AAR | :at-backend-adb | internal (ADB backend) |
| adb-engine-debug.aar | AAR | :adb-engine | internal (ADB lifecycle) |

### docs/
- AT_V4_CONSUMER_HANDOFF.md (consumer integration guide)
- AT.V4-CONSUMER-FREEZE-V1.md (freeze manifest)

### SHA256SUMS.txt
SHA-256 hashes for all artifacts in artifacts/.

## Consumer Usage
A Consumer app depends on:
```
:at-engine-api          (REQUIRED)
:at-composition-android (REQUIRED)
:at-cli                 (OPTIONAL)
```

The internal modules (:at-engine, :at-engine-spi, :at-backend-local,
:at-backend-adb, :adb-engine) are transitive dependencies of
:at-composition-android. A Consumer should NOT directly depend on them
(CONSUMER_BOUNDARY_VIOLATION).

## Native Libraries
| Library | Owning AAR |
|---------|-----------|
| libat_local_backend.so | at-backend-local-debug.aar |
| libadbd_engine.so | adb-engine-debug.aar |
| libatcli_env.so | at-cli-debug.aar |

All native libraries are packaged within their owning AARs. No separate
native artifact delivery needed.

## Dependency Closure
ARTIFACT_DEPENDENCY_CLOSURE = PASS
NATIVE_ARTIFACT_CLOSURE = PASS
