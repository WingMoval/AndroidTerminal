# AT.v0.4.1-FRESH-INSTALL-FINDINGS-REVIEW — Evidence

**Step:** AT.v0.4.1-FRESH-INSTALL-FINDINGS-REVIEW
**Date:** 2026-11-02
**Nature:** Doc / evidence / UX review only — closes out the v0.4.1
fresh-install findings, corrects evidence boundaries, and decides the v0.4.2
UX backlog. **No product code changes. No release mutation.**
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master, base c4e5bc5)

---

## 1. Source Evidence

Primary: `docs/evidence/AT.v0.4.1-GITHUB-FRESH-INSTALL-1.md` (commit c4e5bc5,
amended in this round).

Real user path (frozen facts — NOT re-litigated):

```
fresh install (GitHub asset) → NEEDS PAIRING → Pair SUCCESS
→ POST_PAIR_STATE = CONNECT_FAILED (Model B: bounded attempt, not READY)
→ user tapped ADB button → READY
→ + → ADB → Session ACTIVE
→ id → uid=2000(shell) context=u:r:shell:s0

restart: force-stop → relaunch → DISCONNECTED (no re-pair prompt)
→ user tapped ADB button → READY
→ + → ADB → id → uid=2000(shell)
```

---

## 2. Evidence-Boundary Correction (§5, §7, §20)

The original evidence used `ON_DEMAND_CONNECT_TRIGGERED = PASS` and
`RESTART_ON_DEMAND_CONNECT = PASS` without specifying the trigger. This is
readable as "New Session → ADB from DISCONNECTED itself triggered
ensureReady," which is **not what happened this round**.

What actually happened:

```
ADB button tap → ensureReady → READY      (connect triggered by button)
+ → ADB → session from ALREADY-READY       (fast path, no connect needed)
```

Corrected fields (amended in the evidence doc):

```
THIS_ROUND_ADB_BUTTON_CONNECT = DEVICE_VERIFIED
THIS_ROUND_SESSION_FROM_READY = DEVICE_VERIFIED
THIS_ROUND_SESSION_TRIGGERED_CONNECT_FROM_DISCONNECTED = NOT_TESTED
```

**Canonical capability preserved** (§6): The capability "New Session → ADB
from DISCONNECTED → ensureReady → READY" WAS verified elsewhere:

```
AT.ON-DEMAND-ADB-READINESS-VERIFY-1:
  SESSION_TRIGGERED_ENSURE_READY = PASS
  SESSION_TRIGGERED_CONNECT      = PASS
  (source: MainActivity.openAdbSessionAsync → ensureReady, L284-296)
```

```
CANONICAL_SESSION_TRIGGERED_READINESS = VERIFIED_ELSEWHERE
```

This is NOT `UNKNOWN` and NOT `FAIL` — it is a known, previously-verified
canonical capability that was simply not the path taken in this black-box
round.

---

## 3. UX-1 — Post-Pair Disconnected Wording

**Finding:** After pair success, the bounded ensureReady attempt ended in
CONNECT_FAILED. The app showed DISCONNECTED with the hint "未连接 ADB，请点
ADB 重试" (Not connected to ADB, tap ADB to retry).

**Problem:** The word "重试" (retry) implies a failure or broken state. In
reality: pairing succeeded, the bounded connection attempt simply did not
reach READY in time (Model B). A first-time user who just completed a
deliberate pairing action and is told "retry" may think the pairing failed.

```
UX1 = POST_PAIR_DISCONNECTED_WORDING
SEVERITY = LOW-MEDIUM
```

**Candidate improvement:**
```
Current:  未连接 ADB，请点 ADB 重试
Proposed: 配对成功，ADB 尚未连接。可点「ADB」连接，或直接新建 ADB Session 自动连接。
```

The proposed text:
1. Confirms pairing succeeded (reduces anxiety)
2. States the actual state (not connected yet)
3. Offers TWO paths (ADB button OR New Session) — surfaces the canonical
   session-triggered readiness capability
4. Avoids "retry" language

```
UX1_RECOMMENDATION = ACCEPT
UX1_CHANGE_SIZE = SMALL (string change in MainActivity hint logic)
UX1_ARCHITECTURAL_RISK = LOW (no runtime behavior change; wording only)
```

**Constraint:** This must NOT change the runtime semantics. The bounded
attempt still runs; the state is still CONNECT_FAILED; the only change is
the hint text. §18 compliance: wording/guidance/presentation only.

---

## 4. UX-2 — UI Over-Prioritizes Manual Connect

**Finding:** The DISCONNECTED state hint says "点 ADB 重试" (tap ADB to
retry), which steers the user toward the manual ADB button. The canonical
capability "New Session → ADB → auto ensureReady" is NOT mentioned. This
masks the lazy/on-demand readiness design.

A user who reads "tap ADB to retry" will:
1. Tap ADB button → wait for READY
2. Then tap + → ADB → session

Instead of the more natural:
1. Tap + → ADB → session (auto-connects)

Both work, but the first adds an unnecessary step and teaches the user that
they must always tap the ADB button before creating a session.

```
UX2 = UI_OVER_PRIORITIZES_MANUAL_CONNECT
SEVERITY = LOW
```

**Review questions (§11):**

```
Q1. Should the ADB button remain?
   YES. It is a valid explicit connect trigger (useful for recovery, for
   users who want to pre-connect before creating a session, and as the
   canonical "manual optional readiness trigger").

Q2. What should it be called?
   Current label "ADB" is neutral and acceptable. "Retry" would be wrong
   (implies failure). "Connect" would be more accurate but changes the
   button label for all states. RECOMMENDATION: keep "ADB" as the label
   (it identifies the ADB subsystem, not a specific action). The ACTION
   it performs can be clarified in the hint text (UX-1) rather than the
   button label.

Q3. Should DISCONNECTED hint mention New Session?
   YES. The hint should say "可直接新建 ADB Session，或点「ADB」连接"
   (Can create a new ADB Session directly, or tap ADB to connect). This
   surfaces both paths without mandating one.

Q4. Does the user need to understand readiness state?
   NO. The user should not need to understand "DISCONNECTED" vs "READY"
   vs "CONNECTING" as internal states. The UI should present actionable
   guidance, not state names. (The current "ADB: DISCONNECTED" label is
   acceptable as a status indicator, but the HINT should be action-oriented.)

Q5. Should Retry only appear for true failure/recovery?
   YES. The word "重试/Retry" should be reserved for actual recovery
   scenarios (transport loss, connection failure after READY). For
   DISCONNECTED (never connected / lazy), the action is "connect," not
   "retry."
```

```
UX2_RECOMMENDATION = ACCEPT
UX2_CHANGE_SIZE = SMALL (hint text change, same as UX-1; they are the
  same code location)
UX2_ARCHITECTURAL_RISK = LOW
```

**Note:** UX-1 and UX-2 are effectively the same fix (the DISCONNECTED
hint text after pair). They can be addressed together in one string change.

---

## 5. UX-3 — Pairing Code Source Not Explicit

**Finding:** The NEEDS PAIRING hint says "点「配对」按钮，按提示输入 6 位
配对码" (tap 配对, enter 6-digit code per instructions). But it does NOT
say WHERE the 6-digit code comes from: the phone's Developer Options →
Wireless Debugging → "Pair device with pairing code" dialog.

A truly new user may not know they need to open that system dialog first.
They see "enter 6-digit code" but don't know where to find the code.

```
UX3 = PAIRING_CODE_SOURCE_NOT_EXPLICIT
SEVERITY = LOW
```

**Candidate improvement:**
```
Current:  此安装需要与无线调试配对。点「配对」按钮，按提示输入 6 位配对码。
Proposed: 此安装需要与无线调试配对。
          请先打开：开发者选项 → 无线调试 → 使用配对码配对设备，
          然后点「配对」输入 6 位配对码。
```

```
UX3_RECOMMENDATION = ACCEPT
UX3_CHANGE_SIZE = SMALL (string change in NEEDS PAIRING hint)
UX3_ARCHITECTURAL_RISK = LOW
```

**Note:** This is a longer string and may wrap awkwardly on small screens.
The implementation should verify the layout handles the longer text
gracefully. If it doesn't fit, a shorter variant could be used:
"请先在 设置→开发者选项→无线调试 中打开配对，然后输入 6 位码。"

---

## 6. Observations

### OBS-1 — Terminal Output Not in uiautomator Dump

```
OBS1 = TEST_OBSERVABILITY_LIMITATION
OBS1_PRODUCT_DEFECT = NO
```

The `tvOutput` view (terminal output) returns empty text in uiautomator
dump. This is expected — the terminal is custom-rendered, not a standard
TextView with a `text` property. Agent verification requires DebugBridge
(debug-only) or visual inspection.

This is NOT a product defect. It is a test-observability limitation.

**Constraint:** DebugBridge is debug-only. This observation must NOT be used
to justify exposing DebugBridge in release builds. Future AG integration
should use the AT CLI bridge or a formal agent/session protocol — that is a
separate research track, not a v0.4.2 Local TCP prerequisite.

### OBS-2 — Leftover Test Packages

```
OBS2 = DEV_ENVIRONMENT_HYGIENE
OBS2_PRODUCT_DEFECT = NO
```

The device had leftover test packages (com.androidterminal.app.test,
com.androidterminal.testhost, etc.) from prior development rounds. These are
separate package names and do not affect com.androidterminal.app.

**Recommendation:** Add a `DEVICE_TEST_PACKAGE_CLEANUP` hygiene step before
future black-box test rounds (uninstall all com.androidterminal.* test
packages except the one under test).

---

## 7. v0.4.2 Backlog

### Scope Protection (§17)

v0.4.2 primary goal is **Local TCP / tcpip transport / transport
promotion**. UX items must not expand scope. Only `SMALL + LOW` UX
improvements are recommended for inclusion; others should be DEFERRED.

### Backlog Items

| # | Finding | Type | Severity | v0.4.2 | Change Size | Risk | Priority |
|---|---|---|---|---|---|---|---|
| UX-1 | Post-pair disconnected wording | UX | LOW-MEDIUM | YES | SMALL | LOW | SHOULD |
| UX-2 | Manual connect guidance (same fix as UX-1) | UX | LOW | YES | SMALL | LOW | SHOULD |
| UX-3 | Pairing code source instruction | UX | LOW | YES | SMALL | LOW | SHOULD |
| OBS-1 | Terminal UI automation | Observation | N/A | NO (FUTURE_RESEARCH) | N/A | N/A | DEFER |
| OBS-2 | Leftover test packages | Hygiene | N/A | NO (test hygiene step) | N/A | N/A | DEFER |

**UX-1 + UX-2** are the same code change (DISCONNECTED hint text after
pair). They should be implemented together as one fix:
```
"配对成功，ADB 尚未连接。可点「ADB」连接，或直接新建 ADB Session 自动连接。"
```

**UX-3** is a separate string change (NEEDS PAIRING hint).

All three are `SMALL + LOW` and can be included in v0.4.2 without
significantly expanding scope. They are string-only changes with no
runtime behavior impact.

### NOT in v0.4.2 backlog (FUTURE_RESEARCH)

```
- Automatic Settings deep-link (open Wireless Debugging pairing dialog)
- Wireless Debugging Intent research
- OEM Settings compatibility
- Auto-open pairing dialog
- Agent/session protocol (AG integration)
- AT CLI bridge redesign
- Local TCP (v0.4.2 primary goal, tracked separately)
```

---

## 8. Non-Goals

```
- NO v0.4.1 hotfix
- NO tag mutation
- NO GitHub Release mutation
- NO rebuild / APK replacement
- NO Local TCP implementation (yet)
- NO branch realign (yet)
- NO automatic Settings deep-link
- NO agent protocol
- NO CLI bridge redesign
```

---

## 9. Canonical Runtime Semantics Protection (§18)

The UX fixes change **wording, guidance, presentation, action affordance**
only. They do NOT change:

```
launch = lazy (no auto-connect)
pair success = bounded auto-connect attempt (Model B)
normal execution = lazy ensureReady
manual ADB button = explicit optional readiness trigger
```

Prohibited changes (NOT part of UX fixes):
```
- App launch always auto-connect
- Pair success must eventually auto-READY
- Background retry loop
```

If a future task wants to change these, it must be a separate
behavior-change task with its own audit.

---

## 10. Recommended UX Direction (§19)

```
State: NEEDS PAIRING
Hint: "请在系统无线调试中打开「使用配对码配对设备」，
      然后点「配对」输入 6 位码。"

State: DISCONNECTED after successful pair
Hint: "配对成功，ADB 尚未连接。
      可点「ADB」连接，或直接新建 ADB Session 自动连接。"

State: DISCONNECTED after restart
Hint: "ADB 当前未连接。
      可直接新建 ADB Session，或点「ADB」连接。"
```

Key principle: **Do NOT equate DISCONNECTED with error/retry.**
DISCONNECTED is a normal lazy state, not a failure.

---

## 11. Decision Matrix

| Finding | Type | Severity | v0.4.2 | Change Size | Risk |
|---|---|---|---|---|---|
| UX-1 post-pair wording | UX | LOW-MEDIUM | YES (SHOULD) | SMALL | LOW |
| UX-2 manual connect guidance | UX | LOW | YES (SHOULD, same fix as UX-1) | SMALL | LOW |
| UX-3 pairing-code source | UX | LOW | YES (SHOULD) | SMALL | LOW |
| OBS-1 terminal UI automation | Observation | N/A | NO (FUTURE_RESEARCH) | N/A | N/A |
| OBS-2 leftover test packages | Hygiene | N/A | NO (hygiene step) | N/A | N/A |

---

## 12. Release Disposition

```
V041_RELEASE_REMAINS_VALID = YES
V041_HOTFIX_REQUIRED = NO
```

No blocker or functional defect was found. The UX findings are LOW to
LOW-MEDIUM and are appropriately deferred to v0.4.2. The v0.4.1 release
remains valid as-is.

---

## 13. Final Report

```
STEP = AT.v0.4.1-FRESH-INSTALL-FINDINGS-REVIEW
VERDICT = PASS_WITH_BACKLOG

BASE_HEAD = c4e5bc5
FINAL_HEAD = <this docs-only commit>

V041_RELEASE_REMAINS_VALID = YES
V041_HOTFIX_REQUIRED = NO

FRESH_INSTALL_EVIDENCE_BOUNDARY_CORRECTED = YES

THIS_ROUND_ADB_BUTTON_CONNECT = DEVICE_VERIFIED
THIS_ROUND_SESSION_FROM_READY = DEVICE_VERIFIED
THIS_ROUND_SESSION_TRIGGERED_CONNECT_FROM_DISCONNECTED = NOT_TESTED
CANONICAL_SESSION_TRIGGERED_READINESS = VERIFIED_ELSEWHERE

UX1 = POST_PAIR_DISCONNECTED_WORDING
UX1_SEVERITY = LOW-MEDIUM
UX1_RECOMMENDATION = ACCEPT
UX1_CHANGE_SIZE = SMALL
UX1_ARCHITECTURAL_RISK = LOW

UX2 = UI_OVER_PRIORITIZES_MANUAL_CONNECT
UX2_SEVERITY = LOW
UX2_RECOMMENDATION = ACCEPT
UX2_CHANGE_SIZE = SMALL (same fix as UX-1)
UX2_ARCHITECTURAL_RISK = LOW

UX3 = PAIRING_CODE_SOURCE_NOT_EXPLICIT
UX3_SEVERITY = LOW
UX3_RECOMMENDATION = ACCEPT
UX3_CHANGE_SIZE = SMALL
UX3_ARCHITECTURAL_RISK = LOW

OBS1 = TEST_OBSERVABILITY_LIMITATION
OBS1_PRODUCT_DEFECT = NO

OBS2 = DEV_ENVIRONMENT_HYGIENE
OBS2_PRODUCT_DEFECT = NO

CANONICAL_RUNTIME_SEMANTICS_CHANGED = NO
PRODUCT_CODE_CHANGED = NO
TEST_CODE_CHANGED = NO
RELEASE_CHANGED = NO
TAG_CHANGED = NO

V042_BACKLOG = [UX-1 (SHOULD), UX-2 (SHOULD, same fix), UX-3 (SHOULD)]

NEXT = AT.0.4.2-BRANCH-REALIGN → feature/local-tcp-v0.4.2
       UX backlog tracked separately from Local TCP.
STOP = YES
```
