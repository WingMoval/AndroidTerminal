AT.V4-4.3 — Restart Persistence Verification
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)

## Procedure
1. Gate B + C completed (WRITE_SECURE_SETTINGS granted, auto-WD verified)
2. force-stop com.androidterminal.app
3. am start -n com.androidterminal.app/.MainActivity (relaunch)
4. Wait 5s
5. Run AutoWdBootstrapTest#e_restartPersistence via direct am instrument

## Result
```
Test: OK (1 test), Time: 10.123

POST_RESTART_PERMISSION_PERSISTENCE = PASS
  WRITE_SECURE_SETTINGS: granted=true (retained after restart)

POST_RESTART_AUTO_WD = PASS
  WD state: 1 (enabled)

SELF_GRANT_REEXECUTE_AFTER_RESTART = NO
  completeFirstUseBootstrap() returned SKIPPED_ALREADY_GRANTED
  (no pm grant re-executed)

NORMAL_REPAIRING_COUNT = 0
IDENTITY_FP unchanged = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
```

## Conclusion
Permission bootstrap is PERSISTENT across process restart. The app does NOT
re-grant (idempotent), does NOT re-pair, and auto-WD remains functional.
