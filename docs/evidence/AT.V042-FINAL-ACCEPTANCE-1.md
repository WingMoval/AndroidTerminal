# AT.V042-FINAL-ACCEPTANCE-1 — Evidence

**Step:** AT.V042-FINAL-ACCEPTANCE-1
**Date:** 2026-11-03
**Branch:** `feature/local-tcp-v0.4.2`
**Device:** HONOR ALI-AN00, Android 15

---

## 1. Release Candidate Identity

```
PACKAGE = com.androidterminal.app
VERSION_NAME = 0.4.2
VERSION_CODE = 4
APK_PATH = app/build/outputs/apk/release/app-release.apk
APK_SIZE = 4465528 bytes (4.26 MB)
APK_SHA256 = 90CD5E5B37186DC971126698A895F86A4F2992A38952C64B38B8919EBE019B2C
ABI = arm64-v8a (single-ABI, as configured in adb-engine)
```

## 2. Signing

```
APK_SIGNATURE = PASS
SIGNER_DN = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
CERT_SHA256 = 7bdcba7781e6fecfe078eb9741e7bfeb380f0a7aa64530007991250b07835cde
SAME_V2_SIGNER_AS_V041 = YES
```

Cert matches `NEW_CERT_SHA256` from `AT.RELEASE-SIGNING-LINEAGE-V2-1.md`.

## 3. Build

```
BUILD_ADB_ENGINE = PASS
BUILD_APP_DEBUG = PASS
BUILD_APP_RELEASE = PASS
```

## 4. Automated Gates

```
TEST_TOTAL = 163
TEST_PASS = 163
TEST_FAIL = 0
ARCHITECTURE_GATE = PASS
WIRELESS_REGRESSION = PASS
STALE_GUARD_TESTS = PASS (10/10)
TRUST_LOSS_REGRESSION = PASS
```

## 5. Fresh Install (F1)

```
FRESH_INSTALL = PASS
FRESH_INSTALL_NEEDS_PAIRING = PASS
```

Installed v0.4.2 RELEASE APK on clean device (app uninstalled first).
First launch shows:
```
ADB: NEEDS PAIRING
Hint: 需要与无线调试配对。请先在「开发者选项 → 无线调试」中选择
       「使用配对码配对设备」，再点「配对」输入 6 位码。
```

```
PAIRING_CODE_SOURCE_GUIDANCE = PASS
PAIR_BUTTON_VISIBLE = PASS
NO_CLIPPING = PASS
```

## 6. Pairing (F2)

```
PAIR_RESULT = PASS
PAIRING_KEY_REGENERATED_DURING_PAIR = NO
POST_PAIR_STATE = DISCONNECTED (Model B: bounded attempt, not guaranteed READY)
```

User paired via system Wireless Debugging → 6-digit code → AT Pair button.
Post-pair state is DISCONNECTED (lazy, correct per Model B).

## 7. First Session Without ADB Button (F3)

```
FIRST_SESSION_WITHOUT_ADB_BUTTON = PASS
FIRST_SESSION_IO = PASS
```

From DISCONNECTED, user tapped `+` → `ADB` (NO ADB button).
Logcat:
```
local-first probe: LOCAL active on :38800
```
(38800 was already listening from pairing's bounded attempt → local-first connected.)

UI: `ADB: READY`, `State: ACTIVE`, `1 page(s) #1 ADB (ACTIVE)`
`id` → `uid=2000(shell)`

## 8. Local Promotion Gate (F4)

```
NORMAL_PROMOTION_SOURCE = WIRELESS (via local-first probe, not requestTcpip)
NORMAL_PROMOTION_REQUEST_USB_CALLS = 0
LOCAL_PROMOTION = PASS
```

No `requestUsb`, no `stale`, no `normaliz` in logcat. Clean path.

## 9. Local Multi-Session (F5)

```
MULTI_SESSION = PASS
```

Two ADB sessions created. Both execute independent commands:
```
SESSION_ONE
SESSION_TWO
```
No cross-talk, no reconnect.

## 10. Wi-Fi-Off Acceptance (F6)

```
WIFI_OFF_LOCAL_SURVIVES = PASS
NEW_SESSION_WITH_WIFI_OFF = PASS
```

```
Pre: Local READY, 38800 LISTENING
Action: svc wifi disable → Wi-Fi OFF
Result: ADB: READY, 38800 LISTENING
New session: + → ADB → ACTIVE
id: uid=2000(shell)
Cleanup: svc wifi enable → Wi-Fi restored
```

This CLOSES the previously `NOT_TESTED` gate from STALE-GUARD-CLOSEOUT-1.

## 11. App Restart / Local-First (F7)

```
RELAUNCH_LOCAL_FIRST = PASS
RELAUNCH_SESSION_IO = PASS
```

```
Pre: 38800 LISTENING
Action: force-stop → relaunch
State: ADB: DISCONNECTED (lazy, correct)
Action: + → ADB (no ADB button)
Logcat: local-first probe: LOCAL active on :38800
Result: ADB: READY, State: ACTIVE
id: uid=2000(shell)
```

Wireless discovery/reconnect NOT REQUIRED. Local-first connected existing listener.

## 12. Device Reboot (F8)

```
POST_REBOOT_TCP_PROPERTY = '' (empty)
POST_REBOOT_38800_LISTENER = ABSENT
POST_REBOOT_RECOVERY_PATH = WIRELESS_PROMOTION
POST_REBOOT_REPAIR_REQUIRED = NO
POST_REBOOT_SESSION_IO = PASS
```

Case B: 38800 absent after reboot. Recovery:
```
connectAuto READY via 127.0.0.1:41413
enableLocalTcp: tcpip daemon service request -> accepted
enableLocalTcp: direct promotion to LOCAL complete
ensureReady: automatic promotion WIRELESS -> LOCAL complete
```

No re-pair required. Wireless trust survived reboot.

## 13. Local Loss (F9)

```
LOCAL_LOSS_SEMANTICS = PASS
POST_LOSS_RECOVERY = PASS
```

```
Pre: LOCAL READY (38800 LISTENING)
Action: adb usb → 38800 KILLED
Logcat:
  transport observer: active transport ONLINE->!ONLINE edge; projecting loss
  local transport loss; activeTransport -> WIRELESS (fallback on next ensureReady)
Action: + → ADB
Result: ADB: READY (recovered via on-demand Wireless readiness)
id: uid=2000(shell)
```

No incorrect automatic Wireless rediscovery. Loss detected, fallback on next ensureReady.

## 14. ADB Button Optional Path (F10)

```
ADB_BUTTON_OPTIONAL_PATH = PASS
ADB_BUTTON_REQUIRED = NO
```

DISCONNECTED → tap ADB → READY. Works.

## 15. Trust-Loss Regression

```
TRUST_LOSS_REGRESSION = PASS
```

TrustLossRecoveryTest: PASS (in 163/163).
Trust-loss UI code: UNCHANGED.
PAIRING_REQUIRED guidance: PRESERVED (distinct from generic DISCONNECTED).

## 16. Stale Guard Release Gate

```
STALE_GUARD = PASS
NORMALIZATION_MAX_CYCLES = 1
```

StaleGuardTest 10/10:
- Clean → no normalization ✓
- Valid listener → no normalization ✓
- Exact stale → normalize once ✓
- Normalization failure → safe settle ✓

## 17. Runtime Diff Audit

```
v0.4.1 (master 536fbb7) → v0.4.2 RC (de54167 + version bump)

Classification:
  LOCAL_TCP_PRODUCT: JNI, C++ (stream hub, plain TCP), AdbRuntime,
    AdbRecovery, AdbRecoveryCoordinator, AdbTransportMode, AdbBackend (adaptation)
  LOCAL_TCP_TEST: StaleGuardTest, AdbRuntimeLifecycleTest,
    AdbBackendRoutingTest, LocalTcpCoreSanityTest, LocalTcpProductWiringTest
  UX: MainActivity.kt, activity_main.xml
  VERSION_METADATA: app/build.gradle.kts (versionCode 3→4, versionName 0.4.1→0.4.2)
  DOC: 7 evidence docs, 本轮交付

UNEXPLAINED_DIFF = NONE
```

All changes are within the v0.4.2 release scope:
- Local TCP transport productization
- Direct Wireless → Local promotion
- Pre-request stale guard
- Local routing/recovery integration
- Local TCP tests
- UX-1/2/3
- Version metadata
- Docs/evidence

No AG integration, no CLI protocol, no Maven, no terminal redesign.

## 18. Release Scope Audit

```
v0.4.2 = v0.4.1 Wireless baseline
       + Local TCP productization
       + direct Wireless → Local promotion
       + pre-request stale guard
       + UX-1/2/3
```

## 19. Release Candidate APK Gate

```
INSTALL = PASS
LAUNCH = PASS
PAIR = PASS
WIRELESS = PASS
LOCAL_TCP = PASS
SESSION_IO = PASS
MULTI_SESSION = PASS
WIFI_OFF = PASS
RESTART = PASS
REBOOT = PASS
LOCAL_LOSS_RECOVERY = PASS
```

## 20. Final Report

```
STEP = AT.V042-FINAL-ACCEPTANCE-1
VERDICT = PASS

BASE_HEAD = de54167
FINAL_HEAD = <this commit>

BRANCH = feature/local-tcp-v0.4.2

VERSION_NAME = 0.4.2
VERSION_CODE = 4
PACKAGE = com.androidterminal.app

APK_PATH = app/build/outputs/apk/release/app-release.apk
APK_SIZE = 4465528 bytes (4.26 MB)
APK_SHA256 = 90CD5E5B37186DC971126698A895F86A4F2992A38952C64B38B8919EBE019B2C

APK_SIGNATURE = PASS
SAME_V2_SIGNER_AS_V041 = YES

BUILD_ADB_ENGINE = PASS
BUILD_APP_DEBUG = PASS
BUILD_APP_RELEASE = PASS

TEST_TOTAL = 163
TEST_PASS = 163
TEST_FAIL = 0

ARCHITECTURE_GATE = PASS
WIRELESS_REGRESSION = PASS

FRESH_INSTALL = PASS
FRESH_INSTALL_NEEDS_PAIRING = PASS
PAIRING_CODE_SOURCE_GUIDANCE = PASS

PAIR_RESULT = PASS
PAIRING_KEY_REGENERATED_DURING_PAIR = NO
POST_PAIR_STATE = DISCONNECTED

FIRST_SESSION_WITHOUT_ADB_BUTTON = PASS
FIRST_SESSION_IO = PASS

NORMAL_PROMOTION_SOURCE = WIRELESS
NORMAL_PROMOTION_REQUEST_USB_CALLS = 0
LOCAL_PROMOTION = PASS

MULTI_SESSION = PASS

WIFI_OFF_LOCAL_SURVIVES = PASS
NEW_SESSION_WITH_WIFI_OFF = PASS

RELAUNCH_LOCAL_FIRST = PASS
RELAUNCH_SESSION_IO = PASS

POST_REBOOT_TCP_PROPERTY = '' (empty)
POST_REBOOT_38800_LISTENER = ABSENT
POST_REBOOT_RECOVERY_PATH = WIRELESS_PROMOTION
POST_REBOOT_REPAIR_REQUIRED = NO
POST_REBOOT_SESSION_IO = PASS

LOCAL_LOSS_SEMANTICS = PASS
POST_LOSS_RECOVERY = PASS

ADB_BUTTON_OPTIONAL_PATH = PASS
ADB_BUTTON_REQUIRED = NO

TRUST_LOSS_REGRESSION = PASS

STALE_GUARD = PASS
NORMALIZATION_MAX_CYCLES = 1

UNEXPLAINED_DIFF = NONE

LOCAL_TCP_TRANSPORT_FEATURE_COMPLETE = YES

UX1 = CLOSED
UX2 = CLOSED
UX3 = CLOSED

RELEASE_CANDIDATE_ACCEPTED = YES

TAG_CREATED = NO
GITHUB_RELEASE_CREATED = NO

PUSHED = YES

NEXT = AT.V042-RELEASE-1

STOP = YES
```
