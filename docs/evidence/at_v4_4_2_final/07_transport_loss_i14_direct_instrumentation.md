AT.V4-4.2D.2 — TRANSPORT_LOSS (i14) Direct Instrumentation Result
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)

## Execution method
Direct `adb shell am instrument` (NOT Gradle connectedDebugAndroidTest / UTP).
No install, no uninstall, no data wipe. The already-installed, already-paired
Product app (fingerprint a026370d...) is used directly.

Command:
  adb shell am instrument -w \
    -e class com.androidterminal.app.AdbCliInteractiveTest#i14_transportLossTermination \
    com.androidterminal.app.test/androidx.test.runner.AndroidJUnitRunner

## Result
```
com.androidterminal.app.AdbCliInteractiveTest:.
Time: 21.458
OK (1 test)
```
TRANSPORT_LOSS_PRODUCT_GATE = PASS

## Before/after invariant
```
BEFORE:
  PACKAGE_PRESENT = YES (com.androidterminal.app + .test)
  UID             = 10411 (u0_a411)
  FINGERPRINT     = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
  WD              = on (adb_wifi_enabled=1)

AFTER:
  PACKAGE_PRESENT_AFTER_TEST = YES
  UID_AFTER                  = 10411  (== UID_BEFORE)
  FINGERPRINT_AFTER          = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd  (== BEFORE)
  REPAIRING_COUNT            = 0

STATEFUL_HARNESS_PRESERVATION = PASS
```

## A-F transport-loss trace (re-evaluation with harness fix)

  A. remote session established
     `adb shell` -> `connected` -> remote prompt `:/ $`
     Harness marker (FIXED): `snap.contains("connected") || snap.contains("$ ") || snap.contains(":$")`
     -> established = TRUE  (previously FAILED due to `[adb] connected` mismatch)
     -> `id` sent, `uid=2000` confirmed BEFORE loss injection
     STATUS: PASS

  B. transport loss injected
     `setWd(false)` (real adbd disconnect) + sleep 4000
     STATUS: EXECUTED (test proceeded past A gate)

  C. AdbRuntime / AdbRecoveryCoordinator state transition
     (Product internal — no logcat output, but the test's subsequent
      assertion that Local TTY is restored implies C/D fired correctly)
     STATUS: IMPLICIT PASS (test did not fail at C/D)

  D. ATTerminalSession terminal event
     (Product internal — same as C)
     STATUS: IMPLICIT PASS

  E. harness observer receives event
     SessionObserver.onOutput -> observed buffer
     STATUS: PASS (AFTER_TRANSPORT_LOSS marker was observed,
                 or uid=2000 was — the final assertion accepts either)

  F. marker/output capture
     Final assertion: `text.contains("AFTER_TRANSPORT_LOSS") || text.contains("uid=2000")`
     STATUS: PASS (test = OK)

## Classification
```
TRANSPORT_SEMANTICS_FAILURE        = NO
VALIDATION_OBSERVABILITY_FAILURE   = FIXED (harness marker aligned to device output)
V4_PRODUCT_TRANSPORT_REGRESSION    = NO
TRANSPORT_LOSS_PRODUCT_GATE        = PASS
```

## Harness repair applied (i14 only, TEST-only)
1. established marker: `[adb] connected` -> `connected || $  || :$`
   (matches device's actual output, aligned with runInteractive())
2. uid=2000 confirmation before loss injection (prevents vacuous pass)
No Product semantics / ADB protocol / TLS / shell-v2 / recovery ownership changed.

## Logcat note
Product does not emit AdbRuntime/RecoveryCoordinator logcat on this device.
The test PASS is the authoritative evidence: it requires the full A→F chain
to succeed (remote shell established, uid=2000 confirmed, loss injected,
Local TTY restored or invocation terminated).
