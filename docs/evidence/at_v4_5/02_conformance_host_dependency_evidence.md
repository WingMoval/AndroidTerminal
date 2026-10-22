AT.V4-5 — Conformance Host Dependency Evidence
Date: 2026-10-22

## Module created
`:at-consumer-conformance-host` (applicationId com.androidterminal.conformance)
Role: REFERENCE CONSUMER + CONFORMANCE FIXTURE (NOT a Product).

## Direct project dependencies (from build.gradle.kts)
```
implementation(project(":at-engine-api"))
implementation(project(":at-composition-android"))
implementation(project(":at-cli"))
```

## ALLOWED (frozen V4 Consumer Boundary)
```
:at-engine-api           (REQUIRED)
:at-composition-android  (REQUIRED)
:at-cli                  (OPTIONAL)
```

## FORBIDDEN (verified ABSENT)
```
:at-engine         ABSENT
:at-engine-spi     ABSENT
:at-backend-local  ABSENT
:at-backend-adb    ABSENT
:adb-engine        ABSENT
```

## CONFORMANCE_HOST_ALLOWED_DIRECT_DEPS = PASS
Verified by :at-architecture-gate (ProductionArchitectureGateTest.conformanceHostBoundary).

## COMPOSE + CONSUME (NOT reimplement)
- Composition entrypoint: `ATAndroid.create(context, ATAndroidConfig(identityProfile))`
- Local execution: `composition.localEngine` (ATEngine public contract)
- ADB execution: `composition.adbEngine` (ATEngine public contract)
- ADB semantic control: `composition.adbControl` (ATAndroidAdbControl facade)
- CLI: `AdbCliBridgeServer` + `LocalCliBootstrap` + `ATCliExecution` (host SPI)
- NO `new DefaultATEngine` / `new AdbRuntime` / `new AdbEngine` / backend construction.

## CONFORMANCE_HOST_MODULE = PRESENT
