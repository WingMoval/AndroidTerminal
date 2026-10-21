AT.V4-4.2D.3 — ConsumerHost HOST_RESTART (Direct Instrumentation)
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)

## ConsumerHost installation
Manual `adb install` (NOT Gradle/UTP):
- at-cli-consumer-host-debug.apk installed ONCE
- at-cli-consumer-host-debug-androidTest.apk installed ONCE
No UTP post-test uninstall.

## ConsumerHost identity
```
CONSUMERHOST_PACKAGE              = com.androidterminal.atclihost
CONSUMERHOST_UID                  = 10413 (u0_a413)
CONSUMERHOST_IDENTITY_DIR         = files/at-cli-identity
CONSUMERHOST_IDENTITY_FINGERPRINT = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55
```

The ConsumerHost identity was generated during the first direct instrumentation
run (b5_restartReuse) and was found to already be trusted (READY on first
ensureReady). This indicates the identity was established in a prior
instrumentation session (before the UTP teardown that removed the package).
No new pairing was performed in this session.

## HOST_RESTART validation
Method: direct `adb shell am instrument` (state-preserving, no UTP uninstall)

Test: com.androidterminal.atclihost.AtCliConsumerTest#identityReuseAfterRestart
Command:
  adb shell am instrument -w \
    -e class com.androidterminal.atclihost.AtCliConsumerTest#identityReuseAfterRestart \
    com.androidterminal.atclihost.test/androidx.test.runner.AndroidJUnitRunner

Result: OK (1 test), Time: 36.314

## Before/after invariant
```
BEFORE:
  PACKAGE_PRESENT = YES (atclihost + .test)
  UID             = 10413
  FINGERPRINT     = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55
  WD              = on

AFTER (post-restart, fresh process):
  PACKAGE_PRESENT = YES
  UID             = 10413  (== BEFORE)
  FINGERPRINT     = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55  (== BEFORE)
  READY           = YES (ensureReady returned READY)
  NO PAIRING      = NO pairing was requested or performed

HOST_RESTART                    = PASS
IDENTITY_FP_BEFORE == AFTER     = YES
CONSUMERHOST_NORMAL_REPAIRING_COUNT = 0
STATEFUL_HARNESS_PRESERVATION   = PASS
```

## Counters
```
CONSUMERHOST_FIRST_USE_PAIRING_COUNT = 1  (established in prior session)
CONSUMERHOST_NORMAL_REPAIRING_COUNT  = 0
```

These are independent from Product App counters:
```
PRODUCT_FIRST_USE_PAIRING_COUNT  = 1
PRODUCT_NORMAL_REPAIRING_COUNT   = 0
```

## Constraints honored
- No Product code modified.
- No Product re-pairing.
- No ConsumerHost data cleared / uninstalled.
- No debt1_e4 / debt1_cont.
- No Product identity copied to ConsumerHost.
- ConsumerHost identity is its OWN app-private key (files/at-cli-identity).
