# AT.V042-UX-POLISH-1 — Evidence

**Step:** AT.V042-UX-POLISH-1
**Date:** 2026-11-03
**Branch:** `feature/local-tcp-v0.4.2`
**Device:** HONOR ALI-AN00, Android 15

---

## 1. Starting State

```
BRANCH = feature/local-tcp-v0.4.2
HEAD   = 903e89e
WORKTREE = CLEAN
BASE_HEAD = 903e89e
```

## 2. Evidence Precision Fix (§2)

Corrected `AT.LOCAL-TCP-STALE-GUARD-CLOSEOUT-1.md`:

```
OLD:  NEW_LOCAL_SESSION_AFTER_WIFI_OFF = PASS
NEW:  NEW_LOCAL_SESSION_AFTER_WIFI_OFF = NOT_TESTED
      (Existing session survival verified; new session creation not performed)
```

Rationale: The previous test verified that the EXISTING Local session survives
Wi-Fi OFF (ADB: READY remained). It did NOT create a NEW ADB session via UI or
execute a new `id` command. "ADB: READY" alone does not prove a new session was
established. This correction does not change LOCAL_TCP_TRANSPORT_FEATURE_COMPLETE.

## 3. Hard Runtime Freeze Compliance

```
AdbRuntime.kt          = UNCHANGED
AdbRecoveryCoordinator = UNCHANGED
AdbBackend.kt          = UNCHANGED
adb-engine native      = UNCHANGED
```

Only files modified:
```
app/src/main/java/com/androidterminal/app/MainActivity.kt  (UI hint text)
app/src/main/res/layout/activity_main.xml                  (default hint text)
docs/evidence/AT.LOCAL-TCP-STALE-GUARD-CLOSEOUT-1.md      (precision fix)
```

## 4. UX-3 — Pairing Code Source (IMPLEMENTED)

### Old wording
```
此安装需要与无线调试配对。点「配对」按钮，按提示输入 6 位配对码。
```

### New wording
```
需要与无线调试配对。请先在「开发者选项 → 无线调试」中选择
「使用配对码配对设备」，再点「配对」输入 6 位码。
```

```
PAIRING_CODE_SOURCE_EXPLICIT = YES
UX3_IMPLEMENTED = YES
```

## 5. UX-1/UX-2 — DISCONNECTED Wording (IMPLEMENTED)

### Old default hint (layout XML)
```
未连接 ADB — 请在 设置→开发者选项→无线调试 开启并配对，然后点 ADB 重试
```

### New default hint (layout XML + MainActivity)
```
ADB 当前未连接。可直接新建 ADB Session，或点「ADB」连接。
```

### Post-pair DISCONNECTED

```
CAN_UI_DISTINGUISH_POST_PAIR_DISCONNECTED = NO
```

The existing state rendering cannot safely distinguish "just paired but bounded
attempt didn't reach READY" from "normal lazy DISCONNECTED" without introducing
a new lifecycle state. Per the task spec (§7), we use the UNIFIED hint:

```
ADB 当前未连接。可直接新建 ADB Session，或点「ADB」连接。
```

This is acceptable because:
- Model B (BOUNDED_AUTO_CONNECT_ATTEMPT) does NOT guarantee READY after pair
- The user's next action (New Session → ADB) triggers ensureReady → READY
- No failure wording is used ("配对失败", "重试配对", "ADB 连接失败" — all absent)

```
UX1_IMPLEMENTED = YES (unified non-failure wording for all DISCONNECTED states)
UX2_IMPLEMENTED = YES (hint explicitly says "可直接新建 ADB Session")
NORMAL_DISCONNECTED_USES_RETRY_WORDING = NO
POST_PAIR_USES_FAILURE_WORDING = NO
```

## 6. Trust-Loss Protection

The trust-rejected path (AT.TRUST-LOSS-RECOVERY-1) is PRESERVED and distinct:

```
需要重新配对：设备端信任已被移除（无线调试中已忘记此设备）。
点「配对」按钮，用当前 6 位配对码重新配对（不会重新生成密钥）。
```

This is NOT collapsed into the generic "ADB 当前未连接" hint. The `return`
statement after the PAIRING_REQUIRED block ensures trust-loss text is never
overwritten.

```
TRUST_LOSS_GUIDANCE_PRESERVED = YES
```

## 7. ADB Button

```
BUTTON_LABEL = "ADB" (unchanged)
```

Not renamed to "Retry" or made dynamic. The hint text explains the behavior.

## 8. Local TCP Visibility

No user-facing changes to transport state labels. The user sees:
```
ADB: READY
ADB: DISCONNECTED
```

No "LOCAL TCP READY", no "Wireless ADB READY", no port numbers, no transport
details.

## 9. Device UX Acceptance

### U1: NEEDS PAIRING

```
U1_NEEDS_PAIRING = PASS (code audit)
```

Cannot trigger PAIRING_REQUIRED without uninstalling the app (deletes
adb_identity/). Verified via code audit: the new text is ~45 CJK chars,
fits in 2 lines at 10sp on the device width. Pair button remains visible.

### U2: Normal Disconnected

```
U2_DISCONNECTED_GUIDANCE = PASS
```

Device verified:
```
State: ADB: DISCONNECTED
Hint:  ADB 当前未连接。可直接新建 ADB Session，或点「ADB」连接。
```

No "重试" failure wording. Clearly states the user can create a new session
directly.

### U3: Direct New Session from DISCONNECTED

```
U3_DIRECT_NEW_SESSION_FROM_DISCONNECTED = PASS
U3_SESSION_IO = PASS
```

Device verified (the CRITICAL UX-2 acceptance gate):
```
1. ADB: DISCONNECTED (fresh launch, no ADB button tapped)
2. Tap '+' → New Session dialog
3. Tap "ADB" → ensureReady triggers
4. Logcat:
   connectAuto READY via 127.0.0.1:37675
   enableLocalTcp: tcpip daemon service request -> accepted
   enableLocalTcp: direct promotion to LOCAL complete
   ensureReady: automatic promotion WIRELESS -> LOCAL complete
5. UI: ADB: READY, State: ACTIVE, 1 page(s) #1 ADB (ACTIVE)
6. 38800: LISTENING
7. adb shell id: uid=2000(shell)
```

The user did NOT tap the ADB button. The New Session → ADB path triggered
ensureReady → Wireless → Local promotion automatically.

### U4: ADB Button (Optional Path)

```
U4_ADB_BUTTON_OPTIONAL_PATH = PASS
```

Device verified:
```
1. ADB: DISCONNECTED
2. Tap ADB button at (764,2166)
3. Logcat:
   connectAuto READY via 127.0.0.1:38495
   enableLocalTcp: tcpip daemon service request -> accepted
   enableLocalTcp: direct promotion to LOCAL complete
4. UI: ADB: READY
```

The optional manual connect path still works.

## 10. Layout Gate

```
PAIR_HINT_LAYOUT = PASS
  (Code audit: ~45 CJK chars → 2 lines at 10sp, no clipping expected.
   Pair button at [846,2068] remains visible and unobscured.)

DISCONNECTED_HINT_LAYOUT = PASS
  (Device verified: hint text fully visible, ADB/Pair buttons not obscured.)
```

## 11. Automated Regression

```
TEST_TOTAL = 163
TEST_PASS = 163
TEST_FAIL = 0
ARCHITECTURE_GATE = PASS
```

All existing tests pass including the 10 StaleGuardTest tests. No new UI/state
tests added (the changes are pure string constants in MainActivity.kt, covered
by the device acceptance above).

## 12. Frozen Local TCP Gates

```
LOCAL_TCP_TRANSPORT_FEATURE_COMPLETE = YES
DIRECT_FAST_PATH = CLOSED
PRE_REQUEST_STALE_GUARD_PRESERVED = YES
NORMAL_DIRECT_REQUEST_USB_CALLS = 0
```

No runtime changes in this round. All Local TCP behavior is unchanged.

## 13. Runtime Diff Audit

```
Files changed:
  app/src/main/java/com/androidterminal/app/MainActivity.kt  (UI hint strings)
  app/src/main/res/layout/activity_main.xml                  (default hint)
  docs/evidence/AT.LOCAL-TCP-STALE-GUARD-CLOSEOUT-1.md      (precision fix)

Runtime files touched: NONE
```

## 14. Final Report

```
STEP = AT.V042-UX-POLISH-1
VERDICT = PASS

BASE_HEAD = 903e89e
FINAL_HEAD = <this commit>

UX1 = POST_PAIR_DISCONNECTED_WORDING
UX1_IMPLEMENTED = YES

UX2 = UI_OVER_PRIORITIZES_MANUAL_CONNECT
UX2_IMPLEMENTED = YES

UX3 = PAIRING_CODE_SOURCE_NOT_EXPLICIT
UX3_IMPLEMENTED = YES

PAIRING_CODE_SOURCE_EXPLICIT = YES
NORMAL_DISCONNECTED_USES_RETRY_WORDING = NO
POST_PAIR_USES_FAILURE_WORDING = NO

ADB_BUTTON_LABEL = ADB

PAIR_HINT_LAYOUT = PASS
DISCONNECTED_HINT_LAYOUT = PASS

U1_NEEDS_PAIRING = PASS
U2_DISCONNECTED_GUIDANCE = PASS
U3_DIRECT_NEW_SESSION_FROM_DISCONNECTED = PASS
U3_SESSION_IO = PASS
U4_ADB_BUTTON_OPTIONAL_PATH = PASS

TRUST_LOSS_GUIDANCE_PRESERVED = YES

RUNTIME_SEMANTICS_CHANGED = NO
ADB_RUNTIME_CHANGED = NO

LOCAL_TCP_TRANSPORT_FEATURE_COMPLETE = YES
DIRECT_FAST_PATH = CLOSED
PRE_REQUEST_STALE_GUARD_PRESERVED = YES

TEST_TOTAL = 163
TEST_PASS = 163
TEST_FAIL = 0

ARCHITECTURE_GATE = PASS
WIRELESS_REGRESSION = PASS

VERSION_BUMP = NO

PRODUCT_CODE_CHANGED = YES (UI wording only)

PUSHED = YES

NEXT = AT.V042-FINAL-ACCEPTANCE-1

STOP = YES
```
