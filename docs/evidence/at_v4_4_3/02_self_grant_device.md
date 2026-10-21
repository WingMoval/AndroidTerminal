AT.V4-4.3 — Self-Grant Device Verification (Gate B + C + E)
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
Product: com.androidterminal.app (UID 10411)
Identity: a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd

## Gate B — Permission absent → self-grant

Before:
```
PRODUCT_IDENTITY_FINGERPRINT_BEFORE = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
WRITE_SECURE_SETTINGS_BEFORE = false (not in granted=true list)
PAIRING_STATE = already paired (V4-4.2 final)
```

Test: AutoWdBootstrapTest#b_permissionAbsentSelfGrant
Command: am instrument -w -e class ...b_permissionAbsentSelfGrant ...AndroidJUnitRunner
Result: OK (1 test), Time: 10.17

After:
```
WRITE_SECURE_SETTINGS_AFTER = true (granted=true in dumpsys)
SELF_GRANT_RESULT = PASS
IDENTITY_FP_AFTER = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
IDENTITY_FP_BEFORE == IDENTITY_FP_AFTER = YES
PAIRING_COUNT_DELTA = 0
SELF_GRANT_SOURCE = EMBEDDED_ADB_SHELL (pm grant via AdbRuntime.executeShell)
PC_PM_GRANT_USED_FOR_PRODUCT_BOOTSTRAP = NO
EMBEDDED_SHELL_PRINCIPAL = uid=2000(shell)
```

## Gate C — Auto-WD post-bootstrap

Test: AutoWdBootstrapTest#c_autoWdPostBootstrap
Command: am instrument -w -e class ...c_autoWdPostBootstrap ...AndroidJUnitRunner
Result: OK (1 test), Time: 14.251

Flow:
```
1. Settings.Global.putInt("adb_wifi_enabled", 0)  → WD OFF
2. AppRuntime.connectAuto() → ensureReady()
3. ensureReady: WD DISABLED → canWriteSecureSettings()=true
   → enableWirelessDebugging() → Settings.Global.putInt("adb_wifi_enabled", 1)
   → waitForWdEnabled(8000) → WD ENABLED
   → discovery → connectAuto → READY
4. Open ADB session → `id` → uid=2000(shell)
```

```
POST_BOOTSTRAP_AUTO_WD = PASS
POST_BOOTSTRAP_DISCOVERY = PASS
POST_BOOTSTRAP_CONNECT_AUTO = PASS
POST_BOOTSTRAP_REMOTE_PRINCIPAL = uid=2000(shell)
PAIRING_REQUIRED = NO
REPAIRING_COUNT = 0
WD after = 1 (auto-enabled)
```

## Gate E — Restart persistence

1. force-stop com.androidterminal.app
2. relaunch (am start -n)
3. Run e_restartPersistence test

Test: AutoWdBootstrapTest#e_restartPersistence
Result: OK (1 test), Time: 10.123

```
POST_RESTART_PERMISSION_PERSISTENCE = PASS (WRITE_SECURE_SETTINGS still granted=true)
POST_RESTART_AUTO_WD = PASS (WD=1)
NORMAL_REPAIRING_COUNT = 0
SELF_GRANT_REEXECUTE_AFTER_RESTART = NO (SKIPPED_ALREADY_GRANTED)
IDENTITY_FP unchanged = a026370d...
```

## Final frozen semantics

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
