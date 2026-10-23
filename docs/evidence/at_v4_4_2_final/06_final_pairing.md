AT.V4-4.2D.2 â€?Final Product First-Use Pairing (authorized, ONE)
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)

## Authorization
Controller Decision = OPTION 1: authorize exactly ONE first-use pairing for the
final Product installation (V4-4.2D.2).

## Pairing execution
Method: app Local session #1 via DebugBridge -> `adb pair <ip>:<port> <code>`
The app CLI uses its OWN key (files/adb_identity), NOT the PC key.

Port discovery: user initially provided 43233 (typo). UI dump of the pairing
dialog showed the ACTUAL port = 43223 (code 279346). Corrected and succeeded.

Pairing result:
  adb pair <LAN_IP>:43223 279346
  -> "pairing succeeded (guid=adb-APLMUT4312005935-J4nb5z)"

## Post-pair verification
```
PAIRING_IDENTITY_FINGERPRINT = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
V4_4_2_FINAL_CLOSURE_IDENTITY = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
MATCH                         = YES (pairing did not rotate the key)

READY                         = YES  ("[adb] connected")
REMOTE_PRINCIPAL              = uid=2000(shell)  (gid=2000, context=u:r:shell:s0)
```

## Lifecycle counters
```
FINAL_INSTALL_FIRST_USE_PAIRING_COUNT = 1
FINAL_INSTALL_NORMAL_REPAIRING_COUNT  = 0
```

## Constraints honored
- No additional Product identity created.
- No re-pair. This is the ONE and ONLY Product pairing for this installation.
- No Product code modified.
- debt1_e4 / debt1_cont NOT paired.
- cc7a9884 / 64d3d804 / a7431290 NOT restored (their data no longer exists).
- No app data cleared; no uninstall; no pm clear.

## Pairing code note
The 6-digit code 279346 was a live-only one-time secret, consumed on use.
Recorded here only as a historical transcript line (single-use, already consumed).
