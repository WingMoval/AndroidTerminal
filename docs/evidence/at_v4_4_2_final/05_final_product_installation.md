AT.V4-4.2D.2 â€?Final Product Installation (Stateful)
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
Device IP: <LAN_IP>

## Installation method
Manual `adb install` (NOT Gradle connectedDebugAndroidTest / UTP).
- app-debug.apk installed ONCE
- app-debug-androidTest.apk installed ONCE
No UTP post-test uninstall will occur because we will NOT invoke
connectedDebugAndroidTest. Instrumentation will be run via direct
`adb shell am instrument`.

## FINAL_PRODUCT state (recorded after first identity creation)
```
FINAL_PRODUCT_PACKAGE              = com.androidterminal.app
FINAL_PRODUCT_UID                  = 10411 (u0_a411)
FINAL_CANONICAL_IDENTITY_DIR       = files/adb_identity
FINAL_CANONICAL_IDENTITY_FINGERPRINT = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
adbkey.pub INODE                   = 1963465
adbkey MTIME                       = 2026-10-22 01:22
adb_identity DIR INODE             = 2743036
```

## V4_4_2_FINAL_CLOSURE_IDENTITY
a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd

This fingerprint is the single canonical identity for this final
persistent installation. It must remain stable through:
  PAIR_ONCE -> RUN i14 -> (any future direct instrumentation)

## Identities NOT restored (belong to destroyed data instances)
- cc7a9884...  (original pre-reset, OLD_CANONICAL_IDENTITY)
- 64d3d804...  (V4-4.2D first pairing, destroyed by UTP uninstall)
- a7431290...  (V4-4.2D.1 audit pre-test, destroyed by UTP uninstall)

None of these are restorable; their app data no longer exists.

## Lifecycle rule for remainder of V4-4.2 Device Closure
- NO connectedDebugAndroidTest
- NO uninstall com.androidterminal.app
- NO pm clear com.androidterminal.app
- NO delete files/adb_identity
- NO rotate Product identity
- NO pair debt1_e4 / debt1_cont
- Final Product installation remains stable until Closure.
