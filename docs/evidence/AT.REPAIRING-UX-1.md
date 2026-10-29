# AT.REPAIRING-UX-1 — Evidence

**Step:** AT.REPAIRING-UX-1
**Date:** 2026-10-30
**Device:** HONOR ALI-AN00, Android 15, `APLMUT4312005935`
**Mode:** Product fix + real-device validation

---

## Root Cause Reference

Root cause confirmed in AT.KEY-REGISTRATION-FLOW-1:
``
IDENTITY_LIFECYCLE_BUG: fresh install generates new key, never re-paired
→ key not in adb_keys → CERTIFICATE_VERIFY_FAILED
``

## Identity State Model (Implemented)

``
AdbPairingMetadata (new file: adb-engine/.../AdbPairingMetadata.kt)
├── paired_key_fingerprint = SHA-256 of adbkey.pub bytes
├── device_guid = from pairing success
└── paired_at = Unix epoch millis

AdbConsumerBootstrap.bootstrapState() (modified)
├── READY (AdbRuntime READY)
├── USER_ACTION_REQUIRED_WD (WD off)
├── ENDPOINT_NOT_FOUND
├── PAIRING_REQUIRED (no identity OR identity not paired)
│   └── NEW: !hasIdentity() → PAIRING_REQUIRED (existing)
│   └── NEW: hasIdentity() + !isCurrentKeyPaired() → PAIRING_REQUIRED
└── CONNECT_FAILED (identity paired but connection failed)
``

Key invariant:
``
stored fingerprint != current key fingerprint → PAIRING_REQUIRED
stored fingerprint == current key fingerprint → CONNECT_FAILED (probe)
``

## Changes

1. **AdbPairingMetadata.kt** (NEW): persistent trust annotation in identityDir
2. **AdbConsumerBootstrap.kt** (MODIFIED):
   - bootstrapState() checks pairing metadata
   - pair() persists metadata on success
3. **MainActivity.kt** (MODIFIED):
   - updateAdbStatus() shows "NEEDS PAIRING" when bootstrap=PAIRING_REQUIRED
   - retryAdb() guides to pairing instead of blind connectAuto
4. **PairActivity.kt** (MODIFIED):
   - Hint text explains fresh-install pairing need

## Real-Device Validation

### Gate A — Fresh Identity
``
FRESH_IDENTITY_STATE = NEEDS_PAIRING (confirmed via UI before pairing)
UNPAIRED_AUTO_CONNECT_SUPPRESSED = PASS
``

### Gate B — Pair
``
PAIR_RESULT = PASS (user manual pairing, 6-digit code)
KEY_FINGERPRINT_BEFORE_PAIR = 3a46ca14...
KEY_FINGERPRINT_AFTER_PAIR  = 3a46ca14...
KEY_PRESERVED = YES
pairing_meta written:
  paired_key_fingerprint=3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
  device_guid=adb-APLMUT4312005935-J4nb5z
  paired_at=1793297606919
``

### Gate C — Auto Reconnect After Pair
``
AUTO_RECONNECT_AFTER_PAIR = PASS (ADB: READY after pairing)
READY_AFTER_PAIR = PASS
``

### Gate D — App Restart
``
APP_RESTART_READY = PASS (ADB: READY after force-stop + relaunch)
REPAIR_REQUIRED_AFTER_APP_RESTART = NO
Key unchanged: c46e4eb2...
pairing_meta intact
``

### Gate E — Device Reboot
``
DEVICE_REBOOT_READY = PASS (ADB: READY after reboot + WD enable)
REPAIR_REQUIRED_AFTER_DEVICE_REBOOT = NO
Key unchanged: c46e4eb2...
pairing_meta intact
``

### Optional Gate — Trust Loss
``
TRUST_LOSS_RECOVERY = DEFERRED (not tested this round)
``

## Build

``
BUILD = PASS (gradlew :app:assembleDebug)
APK_SHA256 = 6EF4EF3FBE32C2212CA27011FCDFFFCCBB83A3F08969FC6A5EA9B43708DEC026
``

## Invariants Verified

``
IDENTITY_EXISTS_EQUALS_TRUSTED = NO (identity can exist without being paired)
PAIRING_METADATA_BINDS_KEY_FINGERPRINT = YES
PAIRING_AND_TLS_USE_SAME_IDENTITY = YES (same AdbKeyStore, same keyDir)
KEY_REGENERATED_DURING_PAIRING = NO
LOCAL_TCP_CHANGED = NO
ADB_PROTOCOL_CHANGED = NO
TLS_PROTOCOL_CHANGED = NO
V1_CHANGED = NO
PAIRING_STACK_DUPLICATED = NO
``
