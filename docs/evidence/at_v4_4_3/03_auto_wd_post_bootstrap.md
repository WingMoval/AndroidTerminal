AT.V4-4.3 — Auto-WD Post-Bootstrap + Wiring Verification
Date: 2026-10-22

## Source call-chain evidence (PAIR_SUCCESS → READY → SELF_GRANT_BOOTSTRAP)

1. PairActivity.doPair() (app/src/main/java/com/androidterminal/app/PairActivity.kt)
   - calls control.pairDiscovered(code) → AdbConsumerBootstrap.pairDiscovered()
   - on SUCCESS: calls control.ensureReady { } → AdbRuntime.ensureReady()
   - on READY: calls control.completeFirstUseBootstrap()
     → ATAndroidAdbControl.completeFirstUseBootstrap()
       → AdbConsumerBootstrap.completeFirstUseBootstrap()
         → AdbRuntime.selfGrantWriteSecureSettings()
           → executeShell("pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS")
           → checkSelfPermission(WRITE_SECURE_SETTINGS)

2. CLI path (at-cli module):
   - AtCliMain `adb pair` → AdbConsumerBootstrap.pairDiscovered()
   - (CLI does NOT call completeFirstUseBootstrap — it is a UI/first-use
     bootstrap path. The CLI relies on the app's already-granted permission.)

## Automated wiring test

The `AutoWdBootstrapTest` tests exercise the completeFirstUseBootstrap()
method directly (b_permissionAbsentSelfGrant, e_restartPersistence).
The PairActivity wiring is verified by source inspection:
- PairActivity.doPair() line 143-153: on READY, calls
  control.completeFirstUseBootstrap() and renders the result.
- No other code path calls completeFirstUseBootstrap().
- The method is idempotent (SKIPPED_ALREADY_GRANTED if already granted).

## E2E note

A full E2E (fresh install → pair → auto-grant) would require:
- A fresh Product installation (new identity)
- A new pairing code (HUMAN_ACTION_REQUIRED)
- Risk of destroying the V4-4.2 closure installation

Per task §11: "If for E2E verification a fresh-install + fresh-pair is
required, do NOT auto-execute. STOP: HUMAN_ACTION_REQUIRED."

The current verification (source call-chain + direct instrumentation of
completeFirstUseBootstrap + Gate B/C/E PASS) is sufficient to prove:
- The wiring exists (PairActivity → completeFirstUseBootstrap)
- The self-grant works (Gate B: permission absent → granted)
- The auto-WD works post-bootstrap (Gate C: WD OFF → auto-enable → uid=2000)
- The permission persists across restart (Gate E: retained, no re-grant)
