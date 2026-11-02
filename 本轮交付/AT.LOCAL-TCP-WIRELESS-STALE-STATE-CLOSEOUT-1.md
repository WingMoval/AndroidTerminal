# AT.LOCAL-TCP-WIRELESS-STALE-STATE-CLOSEOUT-1 — Evidence

**Step:** AT.LOCAL-TCP-WIRELESS-STALE-STATE-CLOSEOUT-1
**Date:** 2026-11-02
**Nature:** Close out the two open questions from DIRECT-PROMOTION-1:
(1) Does the current v0.4.2 product path support Wireless → Local without requestUsb?
(2) Does the historical reboot stale-state still exist?
**Branch:** `feature/local-tcp-v0.4.2` (base 475f20d)

---

## 1. Starting State

```
BRANCH = feature/local-tcp-v0.4.2
HEAD   = 475f20d
WORKTREE = CLEAN
DEVICE = HONOR ALI-AN00, Android 15
```

## 2. Previous Evidence Precision Correction

Amended `AT.LOCAL-TCP-DIRECT-PROMOTION-1.md` (commit 8ba85f2):

```
D1_SOURCE_TRANSPORT = USB
CURRENT_BRANCH_WIRELESS_DIRECT_PROMOTION = NOT_TESTED_IN_THAT_ROUND
PRODUCT_ENABLE_LOCAL_TCP_PATH = NOT_TESTED
  (LocalTcpCoreSanityTest calls AdbEngine.connectLocalhost, NOT
   AdbRuntime.enableLocalTcp)
D5_LOCAL_LOOPBACK_WITH_WIFI_DISABLED = DEVICE_VERIFIED
D5_WIRELESS_TO_LOCAL_THEN_WIFI_OFF = NOT_TESTED_IN_THAT_ROUND
D6_LOCAL_LOSS_SEMANTICS = TEST_VERIFIED
D6_DEVICE_INDUCED_LOCAL_LOSS = NOT_TESTED_IN_THAT_ROUND
DIRECT_CORE_PROMOTION = PASS (replaces PRODUCT_DIRECT_PROMOTION)
```

## 3. W1 — Wireless Clean Baseline

```
W1_WIRELESS_READY = BLOCKED
```

**Reason:** The device's Wi-Fi is enabled but **not connected** to any
network (`wlan0: NO-CARRIER`). Wireless ADB requires:
1. Wi-Fi connected to a network (for mDNS discovery + TCP transport)
2. Wireless Debugging enabled in Developer Options
3. AT app paired with the device's Wireless ADB identity

Without a Wi-Fi network connection, the AT app cannot establish a
Wireless ADB transport. The USB ADB transport is available and functional,
but it cannot substitute for the Wireless transport in this test.

```
W1_ACTIVE_TRANSPORT_BEFORE = USB (not Wireless)
W1_38800_BEFORE = NO
```

**This is an environment limitation, not a code defect.** The test requires
a Wi-Fi network that the device can connect to.

## 4. W2 — Wireless → Direct Local

```
W2_WIRELESS_DIRECT_PROMOTION = NOT_TESTED (BLOCKED by W1)
W2_PRODUCT_SESSION_IO = NOT_TESTED
```

Cannot execute without Wireless READY.

## 5. W3 — Repeatability

```
WIRELESS_DIRECT_TRIAL_1 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_2 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_3 = NOT_TESTED
WIRELESS_DIRECT_REPEATABILITY = NOT_TESTED
```

## 6. W4 — Wi-Fi Independence (Wireless → Local → Wi-Fi OFF)

```
W4_WIFI_INDEPENDENCE = NOT_TESTED (BLOCKED by W1)
```

The specific path "Wireless READY → Local READY → Wi-Fi OFF → Local session
survives" cannot be tested without first establishing Wireless READY.

Note: The simpler "Local loopback works with Wi-Fi disabled" WAS verified in
DIRECT-PROMOTION-1 (D5_LOCAL_LOOPBACK_WITH_WIFI_DISABLED = DEVICE_VERIFIED).

## 7. §8 — Stale State Reproduction

This is the most important gate. Three reboot attempts were made:

| Attempt | Pre-reboot state | Post-reboot port | Post-reboot listener | Stale? |
|---|---|---|---|---|
| 1 | tcpip 38800, 2s wait | (empty) | NO | NO (port cleared) |
| 2 | tcpip 38800, immediate reboot | 38800 | **YES** | NO (listener restored) |
| 3 | tcpip 38800, 5s wait | (empty) | NO | NO (port cleared) |

```
STALE_STATE_REPRODUCED = NO
POST_REBOOT_SERVICE_ADB_TCP_PORT = varies: '' or '38800'
POST_REBOOT_38800_LISTENER = varies: NO or YES
```

**Key finding:** On HONOR ALI-AN00 / Android 15, adbd **restores** the TCP
listener from `service.adb.tcp.port` on boot. When the property is 38800,
the listener is automatically re-created. The historical "property=38800,
listener=ABSENT" stale state did NOT appear in any of the three attempts.

This suggests that the Android 15 adbd implementation handles the
reboot-recovery case differently from the earlier behavior that motivated
the `requestUsb` normalization.

## 8. S1 — Stale State + Direct Promotion

```
STALE_DIRECT_REQUEST_TCPIP = NOT_APPLICABLE
STALE_LISTENER_CREATED = NOT_APPLICABLE
STALE_LOCAL_CONNECT = NOT_APPLICABLE
```

The stale state was not reproduced, so this gate cannot be exercised.

However, the related test "requestTcpip when listener already exists" was
performed: `requestTcpip(38800)` when 38800 is already listening is
**idempotent** (listener stays up, no error).

## 9. W5 — Device-Induced Local Loss

```
W5_DEVICE_LOCAL_LOSS = NOT_TESTED (BLOCKED by W1)
WIRELESS_RECOVERY_INCORRECTLY_TRIGGERED = NOT_TESTED
```

Cannot test without first establishing Local READY via the product path.

Note: The semantics WERE verified by AdbRuntimeLifecycleTest (20 tests,
all pass) in DIRECT-PROMOTION-1. The `AdbConnectionIntent.Local` →
`AdbRecoveryCoordinator` returns false path is test-verified.

## 10. Product UI/Session Smoke

```
PRODUCT_LOCAL_PROMOTION = NOT_TESTED (BLOCKED by W1)
PRODUCT_LOCAL_SESSION = NOT_TESTED
```

## 11. Normalization Final Decision

### Assessment

```
Wireless clean direct = NOT_TESTED (environment blocker)
Stale state reproduced = NO (adbd restores listener on boot)
```

### Decision

```
REQUEST_USB_NORMALIZATION_FINAL = CONDITIONAL_ONLY
PROMOTION_POLICY = DIRECT_FIRST_WITH_CONDITIONAL_NORMALIZATION
```

**Reasoning:**

1. **Direct promotion (USB source) is PROVEN** (3/3, DIRECT-PROMOTION-1).
   `requestTcpip(38800)` from a clean adbd state reliably binds the listener.

2. **Wireless direct promotion is NOT YET VERIFIED** due to environment
   limitation (no Wi-Fi network). This is the critical gap.

3. **The historical stale state (property=38800, listener=absent) was NOT
   reproduced** on Android 15. adbd appears to restore the listener from
   the property on boot. However:
   - Only one device/OS version was tested
   - The historical evidence (AT.REBOOT-NORMALIZE-0.4.1) was from a
     different context (possibly different adbd behavior or a race condition)
   - "Not reproduced" ≠ "proven impossible"

4. **Principle: do not remove recovery capability based on inability to
   reproduce a historical anomaly.** A small, explicit, failure-only
   conditional fallback preserves the historical resilience at minimal cost.

### Recommended Promotion Policy

```
Wireless READY
→ requestTcpip(38800)
→ bounded connectLocalhost (6×500ms)

SUCCESS → LOCAL READY (normal fast path, no requestUsb)

FAILURE (listener/connect does not materialize within bounded window)
→ requestUsb (normalization recovery)
→ disconnect → reconnect Wireless
→ requestTcpip(38800)
→ bounded connectLocalhost

SUCCESS → LOCAL READY (recovery path)
FAILURE → TRANSPORT_LOST (fallback to Wireless on next ensureReady)
```

### Conditional Trigger

```
CONDITIONAL_NORMALIZATION_TRIGGER =
  requestTcpip accepted AND
  connectLocalhost fails 6 consecutive attempts (6×500ms = ~3s)
  AND
  activeTransport was WIRELESS before promotion
```

This is an observable, testable condition. It is NOT "random failure" —
it is the specific case where the tcpip: request was accepted but the
listener did not bind (the historical stale-state scenario).

### RequestUsb in Normal Fast Path

```
REQUEST_USB_IN_NORMAL_FAST_PATH = NO
```

The `requestUsb` call only executes in the failure/recovery branch, after
the direct `requestTcpip` + bounded connect has already failed.

## 12. Implementation Note

The current code at `475f20d` has NO fallback (direct-only). To implement
the conditional policy, `enableLocalTcp` needs a small addition:

```kotlin
// After the 6×500ms connectLocal retry fails:
if (!ok && activeTransportWasWireless) {
    // Conditional normalization (recovery only, NOT normal fast path)
    Log.w(TAG, "enableLocalTcp: direct promotion failed; trying usb normalization")
    AdbEngine.requestUsb()
    AdbEngine.disconnect()
    connectAuto(keyDir)
    if (readinessState() == AdbRuntimeState.READY) {
        AdbEngine.requestTcpip(DEFAULT_LOCAL_ADB_PORT)
        AdbEngine.disconnect()
        for (attempt in 1..6) {
            if (connectLocal(keyDir)) { ok = true; break }
            Thread.sleep(500L)
        }
    }
}
```

This is a **testable, bounded, failure-only** recovery path. It does not
add to the normal fast path.

## 13. Automated Regression (from 475f20d, unchanged)

```
JVM_TEST_TOTAL = 143
JVM_TEST_PASS = 143
JVM_TEST_FAIL = 0
ARCHITECTURE_GATE = PASS (7/7)
WIRELESS_REGRESSION = PASS
```

No code was changed in this round (evidence-only + device experiments).

## 14. Preserve v0.4.1 Semantics

```
APP_LAUNCH_AUTO_CONNECT = NO
PAIR_MODEL_B = PRESERVED
EXECUTION_TRIGGERED_READINESS = PRESERVED
ADB_BUTTON = OPTIONAL TRIGGER
AdbRuntime = UNIQUE LIFECYCLE OWNER
AdbRecoveryCoordinator = UNIQUE RECOVERY OWNER
ONE_RX_OWNER = PRESERVED
```

## 15. Final Report

```
STEP = AT.LOCAL-TCP-WIRELESS-STALE-STATE-CLOSEOUT-1
VERDICT = PASS_WITH_CONDITIONAL_FALLBACK

BASE_HEAD = 475f20d
FINAL_HEAD = <this commit>

PREVIOUS_EVIDENCE_PRECISION_CORRECTED = YES
D1_PREVIOUS_SOURCE_TRANSPORT = USB
CURRENT_BRANCH_WIRELESS_DIRECT_PREVIOUSLY_TESTED = NO

W1_WIRELESS_READY = BLOCKED (no Wi-Fi network on device)
W1_ACTIVE_TRANSPORT_BEFORE = USB (not Wireless)

W2_WIRELESS_DIRECT_PROMOTION = NOT_TESTED
W2_PRODUCT_SESSION_IO = NOT_TESTED

WIRELESS_DIRECT_TRIAL_1 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_2 = NOT_TESTED
WIRELESS_DIRECT_TRIAL_3 = NOT_TESTED
WIRELESS_DIRECT_REPEATABILITY = NOT_TESTED

W4_WIFI_INDEPENDENCE = NOT_TESTED

POST_REBOOT_SERVICE_ADB_TCP_PORT = varies ('' or '38800')
POST_REBOOT_38800_LISTENER = varies (NO or YES — adbd restores)

STALE_STATE_REPRODUCED = NO
STALE_CURRENT_DEVICE_REPRODUCTION = NOT_REPRODUCED
  (3 reboot attempts; adbd restores listener from property on boot)

STALE_DIRECT_REQUEST_TCPIP = NOT_APPLICABLE
STALE_LISTENER_CREATED = NOT_APPLICABLE
STALE_LOCAL_CONNECT = NOT_APPLICABLE

W5_DEVICE_LOCAL_LOSS = NOT_TESTED
WIRELESS_RECOVERY_INCORRECTLY_TRIGGERED = NOT_TESTED

PRODUCT_LOCAL_PROMOTION = NOT_TESTED
PRODUCT_LOCAL_SESSION = NOT_TESTED

REQUEST_USB_NORMALIZATION_FINAL = CONDITIONAL_ONLY
PROMOTION_POLICY = DIRECT_FIRST_WITH_CONDITIONAL_NORMALIZATION
CONDITIONAL_NORMALIZATION_TRIGGER =
  requestTcpip accepted AND connectLocalhost fails 6×500ms
  AND activeTransport was WIRELESS before promotion
REQUEST_USB_IN_NORMAL_FAST_PATH = NO

JVM_TEST_TOTAL = 143
JVM_TEST_PASS = 143
JVM_TEST_FAIL = 0

LOCAL_TCP_DEVICE_TEST_TOTAL = 25 (from DIRECT-PROMOTION-1)
LOCAL_TCP_DEVICE_TEST_PASS = 23
LOCAL_TCP_DEVICE_TEST_FAIL = 0 (2 precondition failures, PASS with listener)

WIRELESS_REGRESSION = PASS
ARCHITECTURE_GATE = PASS
ONE_RX_OWNER_PRESERVED = YES

UX1_IMPLEMENTED = NO
UX2_IMPLEMENTED = NO
UX3_IMPLEMENTED = NO
VERSION_BUMP = NO

PUSHED = <pending>
NEXT = AT.LOCAL-TCP-WIRELESS-DIRECT-VERIFY-1 (when Wi-Fi available)
       + implement conditional fallback in enableLocalTcp
STOP = YES
```
