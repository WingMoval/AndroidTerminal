AT.V4-4.2D — Fresh-Install Product First-Use Pairing (Controller Decision A)
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
Device IP: 192.168.137.44

## Authorization
Controller Decision = A: authorize ONE first-use pairing for the freshly-installed
V4 Product App canonical identity to restore the Device Closure test precondition.

## Environment divergence (recorded exactly)
```
ENVIRONMENT_DIVERGENCE        = FRESH_INSTALL
OLD_CANONICAL_IDENTITY        = cc7a9884...
OLD_IDENTITY_AVAILABLE        = NO
NEW_CANONICAL_IDENTITY        = 64d3d80475d4ae07c4080050e73d695301a1aeaec7289884c3d22dd04aff891d
NEW_INSTALL_FIRST_USE_PAIRING_COUNT = 1
NEW_INSTALL_NORMAL_REPAIRING_COUNT  = 0
V4_PRODUCT_IDENTITY_REGRESSION  = NO
V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS
```

## Why PC-side `adb pair` was NOT sufficient
- PC `adb pair` trusts the PC key (fingerprint 385abf2b...).
- The Product App connects with its OWN key (files/adb_identity, fingerprint 64d3d804...).
- These are two different keys. Device trusted the PC key, not the app key.
- Therefore the app's connectAuto remained CONNECT_FAILED until the APP's own key
  was paired.

## Pairing (app's own key, via app CLI in a Local session)
Method: DebugBridge attach to Local session #1 -> write `adb pair <ip>:<port> <code>`
The app CLI drives AdbEngine.pair() with keyDir = files/adb_identity (the app's
OWN canonical identity), NOT the PC key.

Pairing attempts:
- 42257 / 711174  -> rejected (dialog closed, port refused). PC-side pair had used
                     this code while the dialog was open; app-side pair ran after
                     the dialog closed.
- 37193 / 574319  -> SUCCESS: "pairing succeeded (guid=adb-APLMUT4312005935-J4nb5z)"

Pairing code is a live-only secret; NOT persisted beyond this note's reference to
the port/code that were used (per task: not written to git-tracked evidence in a
reusable form). The code 574319 is recorded here only as a one-time historical
transcript line; it is single-use and already consumed.

## Post-pair verification
1. Identity fingerprint UNCHANGED by pairing (pair does not rotate the key):
   files/adb_identity/adbkey.pub sha256 =
   64d3d80475d4ae07c4080050e73d695301a1aeaec7289884c3d22dd04aff891d  (== NEW_CANONICAL_IDENTITY)

2. App CLI connection (app's own key):
   `adb shell id` ->
   "checking connection... discovering... connecting... [adb] connected"
   "uid=2000(shell) gid=2000(shell) groups=... context=u:r:shell:s0"
   => READY + uid=2000(shell)  PASS

## Constraints honored
- No additional Product identity created.
- debt1_e4 / debt1_cont NOT paired.
- No old adbkey copied; cc7a9884 NOT restored by synthetic copying.
- Identity architecture NOT reopened.
- No Production code modified.
- This is the ONE and ONLY Product pairing for this installation lifecycle.
  Any later normal-operation re-pair request => UNEXPECTED_PRODUCT_REPAIR_REQUIRED
  (regression candidate).

## ConsumerHost note (separate environment divergence, awaiting Controller)
com.androidterminal.atclihost is NOT present on the device (reset with the rest).
Its persistent identity (files/at-cli-identity) is lost. Per Controller instruction,
this is reported separately; NO second pairing code was requested for it yet.
