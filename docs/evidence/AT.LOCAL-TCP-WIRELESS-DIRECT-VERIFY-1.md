# AT.LOCAL-TCP-WIRELESS-DIRECT-VERIFY-1 — Evidence

**Step:** AT.LOCAL-TCP-WIRELESS-DIRECT-VERIFY-1
**Date:** 2026-11-02
**Branch:** `feature/local-tcp-v0.4.2` (base 4e8ad06)
**Status:** BLOCKED — Wireless Debugging not enabled on device

---

## 1. Starting State

```
BRANCH = feature/local-tcp-v0.4.2
HEAD   = 4e8ad06
WORKTREE = CLEAN
DEVICE = HONOR ALI-AN00, Android 15
```

## 2. Wi-Fi / Wireless Debugging State

```
WIFI_CONNECTED = YES
  (wlan0 UP, 192.168.137.57, SSID "DESKTOP-PG78M2N 4328")

WIRELESS_DEBUGGING = NOT_ACTIVE
  (adb_wifi_enabled = 1 in settings, but the Wireless Debugging
   service is not actually running. The AT app shows "ADB: DISCONNECTED"
   with hint "请在 设置→开发者选项→无线调试 开启并配对")

ADB_TRANSPORT = USB (PC observer channel)
AT_ACTIVE_TRANSPORT = NONE (DISCONNECTED)
```

## 3. Blocker

The task requires:

```
AT Wireless ADB READY
→ AdbRuntime.enableLocalTcp()
→ requestTcpip(38800)
→ Local TCP
→ READY
```

But the AT app cannot establish a Wireless ADB connection because
**Wireless Debugging is not active** on the device. The `adb_wifi_enabled`
setting is 1, but the actual Wireless Debugging service (which provides
the mDNS advertisement + pairing code + TCP listener) is not running.

The user needs to:
1. Open Settings → Developer Options on the device
2. Toggle ON "Wireless Debugging" (无线调试)
3. The device will show a 6-digit pairing code
4. In the AT app, tap "配对" (Pair) and enter the 6-digit code
5. Wait for "ADB: READY"

Only then can W1-W5 be executed.

## 4. What Was Verified

```
W1_WIRELESS_READY = BLOCKED
  (Cannot establish — Wireless Debugging not active)

W2_WIRELESS_DIRECT_PROMOTION = NOT_TESTED
W3 = NOT_TESTED
W4 = NOT_TESTED
W5 = NOT_TESTED
```

## 5. Current Implementation State (unchanged)

```
enableLocalTcp at 475f20d:
  requestTcpip(38800) → disconnect → connectLocalhost (6×500ms)
  NO requestUsb fallback (direct-only)

CONDITIONAL_FALLBACK_IMPLEMENTED = NO
PRODUCT_CODE_CHANGED = NO
```

## 6. Final Report

```
STEP = AT.LOCAL-TCP-WIRELESS-DIRECT-VERIFY-1
VERDICT = BLOCKED

BASE_HEAD = 4e8ad06
FINAL_HEAD = <this commit>

DEVICE = HONOR ALI-AN00
ANDROID_VERSION = 15

WIFI_CONNECTED = YES
WIRELESS_DEBUGGING = NOT_ACTIVE (needs manual enable + pairing)

W1_WIRELESS_READY = BLOCKED
W1_ACTIVE_TRANSPORT = NONE (DISCONNECTED)
W1_WIRELESS_SESSION_IO = NOT_TESTED

PORT_38800_BEFORE = NO
PRODUCT_PATH_USED = NOT_TESTED
SOURCE_TRANSPORT = NOT_TESTED
REQUEST_USB_CALLED = NOT_TESTED
REQUEST_TCPIP_RESULT = NOT_TESTED
WIRELESS_DISCONNECTED_AFTER_TCPIP = NOT_TESTED
PORT_38800_AFTER_TCPIP = NOT_TESTED
LOCAL_CONNECT_RESULT = NOT_TESTED
ACTIVE_TRANSPORT_AFTER = NOT_TESTED
RUNTIME_STATE_AFTER = NOT_TESTED

W2_WIRELESS_DIRECT_PROMOTION = NOT_TESTED
W2_PRODUCT_SESSION_IO = NOT_TESTED

WIRELESS_DIRECT_TRIAL_1 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_2 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_3 = NOT_TESTED
WIRELESS_DIRECT_REPEATABILITY = NOT_TESTED

W4_EXISTING_LOCAL_SESSION_AFTER_WIFI_OFF = NOT_TESTED
W4_NEW_LOCAL_SESSION_AFTER_WIFI_OFF = NOT_TESTED
W4_WIFI_INDEPENDENCE = NOT_TESTED

W5_DEVICE_LOCAL_LOSS = NOT_TESTED
WIRELESS_RECOVERY_INCORRECTLY_TRIGGERED = NOT_TESTED
POST_LOCAL_LOSS_ON_DEMAND_WIRELESS = NOT_TESTED

WIRELESS_DIRECT_PRODUCT_PATH = NOT_VERIFIED
DIRECT_FAST_PATH = OPEN

CONDITIONAL_FALLBACK_IMPLEMENTED = NO
PRODUCT_CODE_CHANGED = NO

JVM_TEST_TOTAL = 143 (from 475f20d, unchanged)
JVM_TEST_PASS = 143
JVM_TEST_FAIL = 0

LOCAL_TCP_DEVICE_TEST_TOTAL = 25 (from DIRECT-PROMOTION-1)
LOCAL_TCP_DEVICE_TEST_PASS = 23
LOCAL_TCP_DEVICE_TEST_FAIL = 0

V041_WIRELESS_REGRESSION = PASS (unchanged)
ARCHITECTURE_GATE = PASS (unchanged)

PUSHED = YES
NEXT = RE-EXECUTE when Wireless Debugging is enabled on device
STOP = YES
```
