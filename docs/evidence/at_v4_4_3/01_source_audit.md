AT.V4-4.3 — Source Audit
Date: 2026-10-22
Baseline: HEAD da709fb, worktree CLEAN

## A. WRITE_SECURE_SETTINGS declaration

```
WRITE_SECURE_SETTINGS_DECLARED = YES
```
- `app/src/main/AndroidManifest.xml` line 23:
  `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />`
- Also declared in `at-cli-consumer-host` and `adb-only-test-host`.
- No Manifest modification needed.

## B. AUTO_WD_RUNTIME_IMPLEMENTATION

```
AUTO_WD_RUNTIME_IMPLEMENTATION = PRESENT
```
`AdbRuntime.ensureReady()` (line 246-314) has the complete cold path:
- WD DISABLED → `canWriteSecureSettings()` (line 320)
- → `enableWirelessDebugging()` (line 336: `Settings.Global.putInt("adb_wifi_enabled", 1)`)
- → `waitForWdEnabled(8000)` (line 349)
- → discovery + `connectAuto(keyDir)` (line 296)
- → READY

All methods present:
- `canWriteSecureSettings()` — line 320 (checkSelfPermission)
- `enableWirelessDebugging()` — line 336 (Settings.Global.putInt)
- `waitForWdEnabled()` — line 349 (poll readiness)
- `ensureReady()` cold path — line 246-314

No AdbRuntime redesign needed.

## C. First-use pairing path + self-grant placement

### Current path
```
PairActivity.doPair()
  → control.pairDiscovered(code)          [AdbConsumerBootstrap.pairDiscovered]
    → pair(host, port, code)              [AdbConsumerBootstrap.pair]
      → AdbEngine.pair(host, port, code, identityDir)
  → control.ensureReady {}                [AdbRuntime.ensureReady]
  → AppRuntime.adbState() == READY
```

### Ownership
```
PAIR_SUCCESS_OWNER = AdbConsumerBootstrap.pairDiscovered() / pair()
POST_PAIR_CONNECT_OWNER = AdbConsumerBootstrap caller (PairActivity / CLI)
PRODUCT_FIRST_USE_COMPLETION_POINT = READY state after pair + connect
```

### Self-grant placement decision
**Owner:** `AdbRuntime.selfGrantWriteSecureSettings()` (new method)
**Caller:** `AdbConsumerBootstrap.pair()` (after pair success + connect)

Rationale:
- `AdbRuntime` has `appContext` (for `context.packageName`) + `executeShell()`
  (embedded ADB shell, uid=2000) + `canWriteSecureSettings()` (verification).
- `AdbConsumerBootstrap.pair()` is the SINGLE first-use pairing entry point.
  It is the natural FIRST-USE BOOTSTRAP owner (not UI).
- `PairActivity` can trigger/display but is NOT the permission lifecycle owner.
- No `:at-engine-api` change needed (both classes in `:adb-engine`).
- No ADB protocol / TLS / shell-v2 / PTY / AT Engine semantics change.

### State machine
```
PAIR_SUCCESS
  → connect (READY / uid=2000)
  → check WRITE_SECURE_SETTINGS
    → already granted: SELF_GRANT = SKIPPED_ALREADY_GRANTED
    → not granted:
      → AdbRuntime.executeShell("pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS")
      → checkSelfPermission(WRITE_SECURE_SETTINGS)
        → granted: SELF_GRANT = PASS
        → not granted: SELF_GRANT = FAIL (pairing remains SUCCESS)
  → BOOTSTRAP_COMPLETE (or BOOTSTRAP_FAILED if self-grant failed)
```

### Failure semantics
- WRITE_SECURE_SETTINGS bootstrap failure ≠ PAIRING_FAILED
- PAIRING = SUCCESS is preserved even if SELF_GRANT = FAIL
- No identity deletion / key rotation / re-pair on self-grant failure
- Subsequent WD OFF: AdbRuntime returns USER_ACTION_REQUIRED (normal path)
