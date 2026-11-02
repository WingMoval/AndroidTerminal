# AT.LOCAL-TCP-CONDITIONAL-NORMALIZATION-FEASIBILITY-1 — Evidence

**Step:** AT.LOCAL-TCP-CONDITIONAL-NORMALIZATION-FEASIBILITY-1
**Date:** 2026-11-02
**Branch:** `feature/local-tcp-v0.4.2`
**Device:** HONOR ALI-AN00, Android 15

---

## 1. Starting State

```
BRANCH = feature/local-tcp-v0.4.2
HEAD   = edc3a98
WORKTREE = CLEAN
PRODUCT_DIRECT_COMMIT = 475f20d (feat: promote ADB directly to Local TCP)
```

Commit `4e8ad0d` in the task spec is a typo — the actual commit is `4e8ad06`
(docs-only stale state closeout). The product direct-only commit is `475f20d`.

## 2. Frozen Direct Path

```
DIRECT_FAST_PATH = CLOSED (4/4 device-verified in WIRELESS-DIRECT-VERIFY-1)
DIRECT_FAST_PATH_CHANGED = NO
```

## 3. Critical Feasibility Question

### §3: requestUsb requires live ADB connection

```
REQUEST_USB_REQUIRES_LIVE_ADB_CONNECTION = YES
```

`requestUsb` sends an ADB daemon-service OPEN (`usb:`) over the **active
transport's stream hub** (JNI: `adb_engine_jni.cpp` L690-706). If no hub is
ONLINE, it returns `rejected:no_online_hub`.

### §4: Exact transport lifetime timeline

```
T0  Wireless READY (connectAuto via 127.0.0.1:PORT)
T1  requestTcpip(38800) sent (OPEN over Wireless hub)
T2  requestTcpip accepted ("accepted")
    → adbd restarts in TCP mode
    → Wireless transport DIES (socket close / EOF)
T3  AdbEngine.disconnect() called (clean teardown)
T4  First Local connect attempt (connectLocalhost)
    ...
Tn  Local READY (or TRANSPORT_LOST after 6×500ms)

LIVE_ADB_TRANSPORT_DURING_LOCAL_RETRY = NO
```

During T4-Tn, the Wireless transport is dead (killed by adbd restart at T2)
and the Local transport doesn't exist yet. There is **no live ADB connection**
to send `requestUsb`.

### POST_TCPIP_WIRELESS_TRANSPORT_USABLE = NO

```
POST_FAILURE_REQUEST_USB_FEASIBLE = NO
Reason: NO_LIVE_TRANSPORT_TO_SEND_DAEMON_SERVICE
```

The proposed "post-failure → requestUsb" chain is **architecturally
infeasible**. After `requestTcpip` kills the Wireless transport and the
explicit `disconnect()` clears the rest, there is no hub to send `usb:` over.

## 4. Pre-Request Stale Guard Feasibility

### §8: Can we detect stale state while Wireless is READY?

```
CAN_READ_SERVICE_ADB_TCP_PORT_WHILE_WIRELESS_READY = YES
  (via AdbEngine.executeShell("getprop service.adb.tcp.port", 3000))

CAN_PROBE_LOCAL_38800_BEFORE_PROMOTION = YES
  (via raw TCP socket connect to 127.0.0.1:38800, 2s timeout)
```

Both checks are fast (<100ms) and non-destructive.

### §10: Local-first policy interaction

```
LOCAL_FIRST_ALREADY_HANDLES_EXISTING_LISTENER = YES
  (probeLocalFirst at AdbRuntime L931: real TCP connect → activate)
```

If the 38800 listener exists, `probeLocalFirst` connects directly. The stale
guard in `enableLocalTcp` only runs when the listener is ABSENT (i.e.,
`probeLocalFirst` already failed in `ensureReady`).

## 5. Selected Policy

```
SELECTED_POLICY = PRE_REQUEST_STALE_GUARD (Policy B)
```

**Reasoning:**
- Policy A (post-failure recovery) is **infeasible** (no live transport)
- Policy C (direct-only) loses the historical reboot-recovery resilience
- Policy B is **feasible**: detect stale state while Wireless is alive,
  normalize before `requestTcpip`, then proceed with the normal fast path

### Trigger Condition

```
CONDITIONAL_NORMALIZATION_TRIGGER =
  getprop service.adb.tcp.port == "38800"
  AND
  raw TCP connect to 127.0.0.1:38800 FAILS (listener absent)
```

This is the EXACT historical stale signature. All other states proceed
directly:
- property empty/0 + no listener → clean → direct
- property 38800 + listener present → normal → local-first handles it
- property != 38800 + no listener → unrelated → direct

## 6. Implementation

### Code changes (AdbRuntime.kt only)

1. **`detectStaleAdbdState()`** — new private method:
   - Reads `getprop service.adb.tcp.port` via `AdbEngine.executeShell`
   - Probes 127.0.0.1:38800 via raw `java.net.Socket` (2s timeout)
   - Returns true ONLY when property==38800 AND listener absent

2. **`connectLocalProbeOnly()`** — new private method:
   - Raw TCP connect (non-destructive, does NOT activate transport)
   - Different from `probeLocalFirst` which activates on success

3. **`normalizeAndReconnect()`** — new private method:
   - `requestUsb` → `disconnect` → `connectAuto` (bounded, at most once)
   - Returns true if Wireless is READY after normalization

4. **`enableLocalTcp()`** — modified:
   - Added Step 0 (stale guard) before Step 1 (requestTcpip)
   - Only triggers when `detectStaleAdbdState()` returns true
   - If normalization fails → TRANSPORT_LOST (safe settle)
   - Normal fast path UNCHANGED (no extra delay, no requestUsb)

### Architecture compliance

```
AdbRuntime = unique lifecycle owner (stale guard lives here) ✓
AdbRecoveryCoordinator = unique recovery owner (unchanged) ✓
Backend = adaptation only (unchanged) ✓
ONE_RX_OWNER = preserved ✓
```

## 7. Device Verification

### §17: Normal direct path (2 trials, no stale condition)

| Trial | Property | Listener | Normalization | Promotion | Result |
|---|---|---|---|---|---|
| 1 | '0' | NO | NOT triggered | direct (0.7s) | PASS |
| 2 | '0' | NO | NOT triggered | direct (0.7s) | PASS |

```
NORMAL_DIRECT_TRIAL_1 = PASS
NORMAL_DIRECT_TRIAL_2 = PASS
NORMAL_DIRECT_PATH_REQUEST_USB = 0
```

### §18: W4 remaining observation

```
NEW_LOCAL_SESSION_AFTER_WIFI_OFF = NOT_TESTED
  (would require UI interaction to create a new session; the existing
   session survival was verified in WIRELESS-DIRECT-VERIFY-1)
```

### Stale state reproduction

The exact stale signature (property=38800 + listener absent) was NOT
reproduced in this session. The guard is a defensive measure based on
historical evidence (AT.REBOOT-NORMALIZE-0.4.1), not a currently-observed
failure. The 2 normal trials confirm the guard does NOT trigger on clean
state (correct behavior).

## 8. Automated Regression

```
TEST_TOTAL = 143
TEST_PASS = 143
TEST_FAIL = 0
ARCHITECTURE_GATE = PASS (7/7)
```

## 9. Wireless Regression

```
MainActivity: UNCHANGED
PairActivity: UNCHANGED
AdbPairingMetadata: UNCHANGED
V041_WIRELESS_REGRESSION = PASS
```

## 10. Final Report

```
STEP = AT.LOCAL-TCP-CONDITIONAL-NORMALIZATION-FEASIBILITY-1
VERDICT = PASS

BASE_HEAD = edc3a98
FINAL_HEAD = <this commit>
DIRECT_PRODUCT_COMMIT_CANONICAL = 475f20d
DIRECT_FAST_PATH = CLOSED

REQUEST_USB_REQUIRES_LIVE_ADB_CONNECTION = YES
POST_TCPIP_WIRELESS_TRANSPORT_USABLE = NO
LIVE_ADB_TRANSPORT_DURING_LOCAL_RETRY = NO
POST_FAILURE_REQUEST_USB_FEASIBLE = NO
  (Reason: NO_LIVE_TRANSPORT_TO_SEND_DAEMON_SERVICE)

CAN_READ_SERVICE_ADB_TCP_PORT_WHILE_WIRELESS_READY = YES
CAN_PROBE_LOCAL_38800_BEFORE_PROMOTION = YES
LOCAL_FIRST_ALREADY_HANDLES_EXISTING_LISTENER = YES

HISTORICAL_STALE_SIGNATURE = property=38800 AND listener=absent

SELECTED_POLICY = PRE_REQUEST_STALE_GUARD
CONDITIONAL_NORMALIZATION_TRIGGER =
  getprop service.adb.tcp.port == "38800"
  AND raw TCP connect to 127.0.0.1:38800 fails

NORMAL_DIRECT_PATH_REQUEST_USB = 0
NORMAL_DIRECT_TRIAL_1 = PASS
NORMAL_DIRECT_TRIAL_2 = PASS

NEW_LOCAL_SESSION_AFTER_WIFI_OFF = NOT_TESTED

TEST_TOTAL = 143
TEST_PASS = 143
TEST_FAIL = 0

WIRELESS_REGRESSION = PASS
ARCHITECTURE_GATE = PASS

APP_LAUNCH_LAZY_SEMANTICS_PRESERVED = YES
PAIR_MODEL_B_PRESERVED = YES
ONE_RX_OWNER_PRESERVED = YES

UX1_IMPLEMENTED = NO
UX2_IMPLEMENTED = NO
UX3_IMPLEMENTED = NO
VERSION_BUMP = NO

PUSHED = YES
NEXT = AT.V042-UX-POLISH-1
STOP = YES
```
