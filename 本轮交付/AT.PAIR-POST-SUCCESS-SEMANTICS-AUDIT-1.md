# AT.PAIR-POST-SUCCESS-SEMANTICS-AUDIT-1 — Evidence

**Step:** AT.PAIR-POST-SUCCESS-SEMANTICS-AUDIT-1
**Date:** 2026-10-31
**Nature:** Read-only semantic audit — is "Pair SUCCESS → auto-READY (no user
action)" a canonical product requirement, distinct from the already-closed
execution-triggered lazy readiness?
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master, base 01caf1a)

> Trigger: during AT.0.4.1-RELEASE-V2 §15 (PAIR_AUTO_RECONNECT), the observed
> READY after pairing was reached via a **manual** tap, so it could not be
> recorded as "automatic". This round determines whether pair→auto-reconnect is
> canonical, so the release gate can be stated correctly.
>
> **No production code / test / release-behavior changes this round. No
> uninstall / fresh install / re-pair / device reboot. AT.0.4.1-RELEASE-V2 =
> PAUSED.**

---

## 1. Starting State

```text
BRANCH   = master
HEAD     = 01caf1a
WORKTREE = DIRTY (app/build.gradle.kts version bump from paused release round;
            preserved, NOT reset/stashed/cleaned)
```

---

## 2. Already-Confirmed Baseline (NOT re-litigated)

```text
APP_LAUNCH_AUTO_CONNECT_CANONICAL     = NO
EXECUTION_TRIGGERED_READINESS_CANONICAL = YES
MANUAL_ADB_BUTTON_ROLE                = OPTIONAL_RECOVERY
```

This round only resolves **pair-success completion semantics**.

---

## 3. Historical Evidence Classification (§4-§5)

### H1 — Explicit requirement
- **AT.REPAIRING-UX-1** Gate C (docs/evidence/AT.REPAIRING-UX-1.md:74-78):
  ```
  ### Gate C — Auto Reconnect After Pair
  AUTO_RECONNECT_AFTER_PAIR = PASS (ADB: READY after pairing)
  READY_AFTER_PAIR = PASS
  ```
  A **named, canonical release gate** specifically for pair→auto-reconnect.
- **AT.TRUST-LOSS-RECOVERY-1** T6 (line 198):
  `re-pair 成功 → trust flag 清除 + metadata valid（auto reconnect）`.

### H2 — Implementation description
- **PairActivity.kt** header (lines 17-18): "…triggers pairing via the
  bootstrap, **then connects and reports READY**."
- **PairActivity.kt** user hint (line 71): "（配对成功后 App 自动连接，重启无需
  再配对）" = "(After pairing succeeds, the App **auto-connects**; no re-pairing
  needed on restart)."

### H3 — Observed test result
- **AT.REPAIRING-UX-1** Gate B: `KEY_FINGERPRINT_BEFORE_PAIR == AFTER_PAIR`
  (`3a46ca14...`), `KEY_PRESERVED = YES` (same-key, no regeneration).
- **AT.REPAIRING-UX-1** Gate C: ADB reached READY after pairing.
- **AT.TRUST-LOSS-RECOVERY-1** TrustLossRecoveryTest T6: re-pair → auto reconnect
  (11/11 PASS).

### H4 — Ambiguous wording
- "auto reconnect after pairing" appears in several docs **without** an explicit
  "NO Retry tap / NO New Session" annotation in the historical evidence. Per the
  §15 strict standard, the *historical real-device* record alone is therefore
  **AMBIGUOUS** as standalone proof of "no external readiness action". However,
  the **current source** (§7) removes that ambiguity structurally (below).

---

## 4. Source Trace — Pair Entry & Success Callback (§7-§9)

```text
PAIR_ENTRY        = MainActivity 配对 button (btnPairAdb) → PairActivity
PAIR_ACTIVITY     = app/src/main/java/com/androidterminal/app/PairActivity.kt
PAIR_CLIENT       = control.pairDiscovered(code)
                    → ATAndroidAdbControl.pairDiscovered
                    → AdbConsumerBootstrap.pairDiscovered (single pairing boundary)
                    → AdbEngine.pair
```

**Pair SUCCESS callback** (`PairActivity.doPair`, lines 128-180):
```kotlin
val result = control.pairDiscovered(code)          // line 133 — run pairing
...
Outcome.SUCCESS -> {                                // line 141
    val ready = control.ensureReady { }             // line 144 — EXPLICIT ensureReady
    if (AppRuntime.adbState() == ATAdbReadinessState.READY) {   // line 145
        control.completeFirstUseBootstrap()         // line 150 — self-grant (idempotent)
        "配对成功 — ADB READY (guid=...)"
    } else {
        "配对成功，连接建立中…"
    }
}
...
if (message.startsWith("配对成功")) { finish() }      // line 177-179 — return to MainActivity
```

```text
PAIR_SUCCESS_CALLBACK     = PairActivity.doPair, Outcome.SUCCESS branch
PAIR_SUCCESS_POST_ACTIONS = ensureReady → (if READY) completeFirstUseBootstrap →
                            setResult-less finish() back to MainActivity

PAIR_SUCCESS_CALLS_ENSURE_READY = YES   (PairActivity.kt:144, control.ensureReady {})
PAIR_SUCCESS_CALLS_CONNECT_AUTO = NO    (no connectAuto; ensureReady is the single owner)
PAIR_SUCCESS_CALLS_RETRY_PATH   = NO    (retryAdb is the OPTIONAL manual control,
                                         NOT invoked on pair success)
```

> Note: `ATAndroidAdbControl.pairDiscovered` (lines 91-92) does **not** internally
> call `ensureReady` — it only delegates to `bootstrap.pairDiscovered`. The
> **eager reconnect is owned by the pairing flow (PairActivity)**, which calls
> `ensureReady` after a successful pair. This matches the conformance test's
> documented pattern.

---

## 5. MainActivity Post-Pair Behavior (§13)

After `PairActivity.finish()`, MainActivity resumes. `updateAdbStatus()` (lines
322-347) reads `bootstrapState()`:
- If `PAIRING_REQUIRED` → "ADB: NEEDS PAIRING".
- Otherwise it reflects the **AdbRuntime** readiness state (READY / DISCONNECTED…).

MainActivity does **not** call `ensureReady`/`connectAuto` in `onResume` as a
side effect of returning from pairing (the only `ensureReady` call sites are
`openAdbSessionAsync` line 287 and `retryAdb` line 314 — both explicit user
actions). So MainActivity is a **passive projection**; the READY reached after
pairing is the one established by PairActivity's `ensureReady`.

```text
MAIN_ACTIVITY_AUTO_CONNECT_AFTER_PAIR = NO
  (MainActivity does not independently trigger connect on pair return;
   the READY is from PairActivity's own ensureReady)
```

---

## 6. Metadata vs Readiness (§10)

```text
PAIRING_OWNS_IDENTITY_TRUST      = YES (pairDiscovered → AdbPairingMetadata persists
                                          paired_key_fingerprint + device_guid + paired_at)
PAIRING_OWNS_TRANSPORT_READINESS = YES (the pairing FLOW calls ensureReady after
                                          success; PairActivity.kt:144)
```

Pairing is **not** merely a trust annotation here — the pairing flow explicitly
drives readiness. (The trust metadata is written by the bootstrap on success;
the transport connect is driven by the flow's `ensureReady`.)

---

## 7. AdbRuntime Ownership (§11)

`AdbRuntime.ensureReady` is the single readiness owner (NORMAL_COMMAND_READINESS_OWNER).
Pairing **may** invoke it (and does, via PairActivity), but it does not bypass
Runtime ownership — it calls the same `ensureReady`.

```text
POST_PAIR_READINESS_OWNER = PAIRING_FLOW
  (the pairing flow is an allowed readiness *trigger*, exactly like a normal
   command/session; AdbRuntime remains the readiness *owner*)
```

---

## 8. PairActivity Return Semantics (§12)

```text
PAIR_ACTIVITY_ON_SUCCESS = finish() (line 178) → return to MainActivity
                           (NO setResult; MainActivity passively reflects state)
```

---

## 9. Tests Audit (§14)

- **ConformancePairingTest.kt** (at-consumer-conformance-host, line 47-48):
  explicitly documents the canonical pattern — "After pairing, drive
  `ensureReady` to establish the connection" — i.e. pair success is *followed
  by* an `ensureReady`. This is an **integration test** that pair→ensureReady is
  the expected sequence (though the test itself calls ensureReady to model the
  flow).
- **TrustLossRecoveryTest** T6: re-pair → auto reconnect (unit, 11/11 PASS).
- No unit test asserts "pair success → Runtime READY with **zero** external
  action" in isolation; the guarantee comes from the PairActivity source path
  (which always calls `ensureReady` on success).

```text
PAIR_AUTO_RECONNECT_UNIT_TEST      = PARTIAL (TrustLossRecoveryTest T6 covers
                                       re-pair→reconnect; no isolated pair→READY
                                       no-action unit test)
PAIR_AUTO_RECONNECT_INTEGRATION_TEST = YES (ConformancePairingTest models
                                       pair→ensureReady→connection)
PAIR_AUTO_RECONNECT_REAL_DEVICE_TEST = AMBIGUOUS (AT.REPAIRING-UX-1 Gate C
                                       records READY-after-pair but does not
                                       explicitly annotate "no retry/new-session";
                                       source path confirms the auto path)
```

---

## 10. Contradictory Semantics Search (§16)

No doc states `PAIR_SUCCESS_IMPLIES_READY = NO` or "pairing != transport
readiness". The consistent direction is pair→connect→READY.

```text
PAIR_SUCCESS_IMPLIES_READY_CANONICAL = YES
```

---

## 11. Compare With Lazy Readiness (§17)

This is **Model A** (pair is a special lifecycle event that eagerly reconnects):

```text
launch          = lazy (may stay DISCONNECTED)
pair success    = EAGER reconnect (PairActivity calls ensureReady)
execution       = lazy fallback (normal command/session triggers ensureReady)
```

Pair success is **not** the same as app launch: it is an explicit user pairing
action whose completion naturally establishes the connection. The already-confirmed
lazy-readiness verdict (launch = lazy) is **not** contradicted — pairing is a
distinct, eager lifecycle trigger.

```text
POST_PAIR_ON_DEMAND_CONNECT_CANONICAL = YES (also valid as a fallback: if the
  post-pair ensureReady did not reach READY, a normal New Session/ADB action
  would still reach it lazily)
```

---

## 12. Canonical Decision (§18) — **Case A**

Both required conditions are met:
1. **Historical explicit requirement**: AT.REPAIRING-UX-1 Gate C "Auto Reconnect
   After Pair" is a named canonical gate; AT.TRUST-LOSS-RECOVERY-1 T6 corroborates.
2. **Clear current source path**: `PairActivity.doPair` → `Outcome.SUCCESS` →
   `control.ensureReady {}` (PairActivity.kt:144), with no user action between
   pair success and the connect attempt.

```text
PAIR_AUTO_RECONNECT_CANONICAL = YES
CURRENT_SOURCE_EXPLICIT_AUTO_RECONNECT = YES
HISTORICAL_EXPLICIT_REQUIREMENT = YES
```

---

## 13. Real-Device Evidence Quality (§15)

The historical Gate C is **AMBIGUOUS** as standalone strict proof (no explicit
"no retry/new-session" annotation). However, the **current source** makes the
auto-reconnect structural: the pairing flow *always* calls `ensureReady` on
success. Therefore:

- The **canonical verdict** (PAIR_AUTO_RECONNECT_CANONICAL = YES) is solid on
  source + historical requirement.
- For a **strict device-confirmed** `PAIR_AUTO_RECONNECT_REAL_DEVICE_VERIFIED =
  YES`, one clean run is recommended:
  ```
  fresh install → fresh identity → NEEDS PAIRING → enter code → Pair SUCCESS →
  NO Retry → NO New Session → NO ADB command → wait → READY
  ```
  This is the remaining verification, not a semantic blocker.

---

## 14. Release-Gate Recommendation

Because pair→auto-reconnect is canonical (Case A), the **original** release gate
is correct and should be **retained** (not replaced by on-demand-only):

```text
PAIR_RESULT = PASS
PAIRING_REGENERATED_KEY = NO
PAIR_AUTO_RECONNECT = PASS          (retain — canonical, Model A)
WIRELESS_READY_AFTER_PAIR = PASS    (retain)
```

The strict device confirmation of PAIR_AUTO_RECONNECT (no manual tap) is the one
outstanding real-device check. Since the current device session already reached
READY via a manual tap (not a clean auto observation), the clean strict run
(§13) should be performed to record `PAIR_AUTO_RECONNECT_REAL_DEVICE_VERIFIED = YES`.

> This does NOT conflict with the lazy-readiness release gates:
> - App restart / device reboot → on-demand connect (lazy) — already corrected.
> - Pair success → auto reconnect (eager) — canonical, retain.

---

## 15. Verdict & Status

```text
STEP = AT.PAIR-POST-SUCCESS-SEMANTICS-AUDIT-1
VERDICT = PASS (Case A: pair-success auto-reconnect is canonical; Model A)
BASE_HEAD = 01caf1a
FINAL_HEAD = <this docs-only commit>

PAIR_ENTRY          = MainActivity btnPairAdb → PairActivity
PAIR_ACTIVITY       = PairActivity.kt
PAIR_CLIENT         = control.pairDiscovered → AdbConsumerBootstrap.pairDiscovered → AdbEngine.pair

PAIR_SUCCESS_CALLBACK     = PairActivity.doPair Outcome.SUCCESS
PAIR_SUCCESS_POST_ACTIONS = ensureReady → (if READY) completeFirstUseBootstrap → finish()

PAIR_SUCCESS_CALLS_ENSURE_READY = YES (PairActivity.kt:144)
PAIR_SUCCESS_CALLS_CONNECT_AUTO = NO
PAIR_SUCCESS_CALLS_RETRY_PATH   = NO

MAIN_ACTIVITY_AUTO_CONNECT_AFTER_PAIR = NO (passive projection; READY from PairActivity's ensureReady)

PAIRING_OWNS_IDENTITY_TRUST      = YES
PAIRING_OWNS_TRANSPORT_READINESS = YES (via flow's ensureReady)

POST_PAIR_READINESS_OWNER        = PAIRING_FLOW (allowed trigger; AdbRuntime remains owner)

PAIR_SUCCESS_IMPLIES_READY_CANONICAL = YES

PAIR_AUTO_RECONNECT_UNIT_TEST      = PARTIAL
PAIR_AUTO_RECONNECT_INTEGRATION_TEST = YES
PAIR_AUTO_RECONNECT_REAL_DEVICE_TEST = AMBIGUOUS (historical) → source-confirmed

HISTORICAL_EXPLICIT_REQUIREMENT = YES (AT.REPAIRING-UX-1 Gate C; TRUST-LOSS T6)
CURRENT_SOURCE_EXPLICIT_AUTO_RECONNECT = YES

PAIR_AUTO_RECONNECT_CANONICAL = YES
POST_PAIR_ON_DEMAND_CONNECT_CANONICAL = YES

MANDATORY_RELEASE_GATE_SHOULD_BE = [AUTO_RECONNECT] (retain PAIR_AUTO_RECONNECT +
                                     WIRELESS_READY_AFTER_PAIR; perform one clean
                                     strict device run to record REAL_DEVICE_VERIFIED)

FRESH_INSTALL_RETEST_REQUIRED = YES (one clean strict run for
                                     PAIR_AUTO_RECONNECT_REAL_DEVICE_VERIFIED = YES)

PRODUCT_CODE_CHANGED = NO
TEST_CODE_CHANGED    = NO
VERSION_BUMP_TOUCHED = NO
TAG_CREATED          = NO
PUSHED               = NO
RELEASED             = NO

NEXT = RESUME AT.0.4.1-RELEASE-V2; perform one clean strict fresh-install pairing
       run to confirm PAIR_AUTO_RECONNECT (no manual tap), then continue release.
STOP = YES
```