AT.V4-4.3 — Final Verdict
Date: 2026-10-22

## GATES

| Gate | Result |
|------|--------|
| A — Source/build | PASS (BUILD SUCCESSFUL, PRODUCTION_CHANGE_SCOPE = FIRST_USE_BOOTSTRAP_ONLY) |
| B — Permission absent → self-grant | PASS (WRITE_SECURE_SETTINGS: false→true, FP unchanged, no re-pair) |
| C — Pair-success wiring | PASS (source call-chain + automated test) |
| D — Auto-WD real-device closure | PASS (WD OFF → auto-enable → discovery → connect → uid=2000) |
| E — Restart persistence | PASS (permission retained, no re-grant, no re-pair) |

## FROZEN SEMANTICS

```
FIRST_USE_MANUAL_WD_REQUIRED = YES
FIRST_USE_PAIRING_REQUIRED = YES
POST_PAIR_SELF_GRANT_WRITE_SECURE_SETTINGS = PASS
POST_BOOTSTRAP_MANUAL_WD_REQUIRED = NO
POST_BOOTSTRAP_AUTO_WD = PASS
PRODUCT_FIRST_USE_PAIRING_COUNT = 1
PRODUCT_NORMAL_REPAIRING_COUNT = 0
SELF_GRANT_SOURCE = EMBEDDED_ADB_SHELL
PC_PM_GRANT_USED_FOR_PRODUCT_BOOTSTRAP = NO
FRESH_INSTALL_ZERO_TOUCH = NO
FIRST_USE_ONE_MANUAL_PAIRING_THEN_AUTONOMOUS_WD = YES
```

## PRODUCTION CHANGES

```
PRODUCTION_FILES_CHANGED:
  - adb-engine/src/main/java/com/adb/engine/AdbRuntime.kt
    (added SelfGrantResult enum + selfGrantWriteSecureSettings() method)
  - at-composition-android/src/main/java/com/at/composition/android/ATAndroidAdbControl.kt
    (added FirstUseBootstrapResult enum + completeFirstUseBootstrap() method)
  - app/src/main/java/com/androidterminal/app/PairActivity.kt
    (added completeFirstUseBootstrap() call in doPair() on READY)

TEST_FILES_CHANGED:
  - app/src/androidTest/java/com/androidterminal/app/AutoWdBootstrapTest.kt (new)

DOC_FILES_CHANGED:
  - docs/evidence/at_v4_4_3/01_source_audit.md (new)
  - docs/evidence/at_v4_4_3/02_self_grant_device.md (new)
  - docs/evidence/at_v4_4_3/03_auto_wd_post_bootstrap.md (new)
  - docs/evidence/at_v4_4_3/04_restart_persistence.md (new)
  - docs/evidence/at_v4_4_3/05_final_verdict.md (this file)
  - docs/AT.V4_MASTER_SSOT.md (updated)
  - docs/roadmap/AT.V4_ROADMAP.md (updated)
```

## NO CHANGES TO
- ADB protocol
- TLS
- shell-v2
- PTY
- AT Engine public API
- Identity architecture
- AdbRuntime ownership / recovery
- Consumer Boundary

## VERDICT

```
AT.V4-4.3 = COMPLETE
AT.V4-4.2 = CLOSED (unchanged)
RECOMMENDED_NEXT = AT.V4-5
NEXT_OPEN_AUTHORIZED = NO
```
