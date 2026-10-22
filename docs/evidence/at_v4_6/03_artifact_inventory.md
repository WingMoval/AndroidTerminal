AT.V4-6 — Artifact Inventory
Date: 2026-10-22

## Module -> Artifact Mapping

| Module | Plugin | Artifact Type | Filename | Size | SHA-256 (first 24) | Native (arm64-v8a) | Consumer Role |
|--------|--------|--------------|----------|------|---------------------|---------------------|---------------|
| at-engine-api | kotlin.jvm | JAR | at-engine-api.jar | 23079B | b6b28fb1a88eb3139d809e27 | (none) | REQUIRED (public contract) |
| at-engine-spi | kotlin.jvm | JAR | at-engine-spi.jar | 1851B | 04bf7569f66a15d44a8e483f | (none) | internal (SPI) |
| at-engine | com.android.library | AAR | at-engine-debug.aar | 2939B | bb46d47d5e04159e4977f00c | (none) | internal (DefaultATEngine) |
| at-backend-local | com.android.library | AAR | at-backend-local-debug.aar | 133778B | 684f282423b498ddfb24b3b5 | libat_local_backend.so | internal (Local backend) |
| at-backend-adb | com.android.library | AAR | at-backend-adb-debug.aar | 8861B | 5e60f66eb61c5126db3d0fb8 | (none) | internal (ADB backend) |
| adb-engine | com.android.library | AAR | adb-engine-debug.aar | 1261778B | 2a3edde073a88405ece0e73f | libadbd_engine.so | internal (ADB lifecycle) |
| at-composition-android | com.android.library | AAR | at-composition-android-debug.aar | 31197B | d3d13476a1b94f7d6f7645a1 | (none) | REQUIRED (assembly root) |
| at-cli | com.android.library | AAR | at-cli-debug.aar | 196881B | e301389d0110d63f0b27b45d | libatcli_env.so | OPTIONAL (CLI frontend) |

## Native Library Mapping
```
libat_local_backend.so  -> at-backend-local AAR
libadbd_engine.so       -> adb-engine AAR
libatcli_env.so         -> at-cli AAR
```

Each native library is packaged in its correct owning AAR. No duplicate .so.

## NATIVE_ARTIFACT_CLOSURE = PASS
## ARTIFACT_DEPENDENCY_CLOSURE = PASS
(All 8 consumer-required artifacts present; dependency chain complete.)
