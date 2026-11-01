# AT.PAIR-POST-SUCCESS-RUNTIME-SEMANTICS-1 — Evidence

**Step:** AT.PAIR-POST-SUCCESS-RUNTIME-SEMANTICS-1
**Date:** 2026-10-31
**Nature:** Source / test / doc audit ONLY — what runtime guarantee does the
`ensureReady()` called after Pair SUCCESS actually provide?
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master, base 393b2a1)

> Trigger: AT.PAIR-POST-SUCCESS-SEMANTICS-AUDIT-1 (393b2a1) confirmed
> `PAIR_SUCCESS_CALLS_ENSURE_READY = YES` but used over-strong wording
> (`PAIR_SUCCESS_IMPLIES_READY = YES`, `PAIR_AUTO_RECONNECT = PASS`).
> This round classifies the actual runtime semantics into one of:
>
> ```text
> Model A — GUARANTEED_SYNC_READY     (function does not complete until READY)
> Model B — BOUNDED_AUTO_CONNECT_ATTEMPT (bounded try; may end non-READY;
>                                        no background continuation)
> Model C — ASYNC_EVENTUAL_READY      (returns early; Runtime continues in
>                                        background until READY)
> Model D — Other
> ```
>
> **No phone testing. No uninstall / re-pair / fresh install. No production /
> test / behavior changes. No version change / tag / push / release.
> AT.0.4.1-RELEASE-V2 = PAUSED (version bump left in worktree, untouched).**

---

## 1. Starting State

```text
BRANCH   = master
HEAD     = 393b2a1
WORKTREE = DIRTY (app/build.gradle.kts v0.4.1/v3 version bump from the paused
            release round; preserved, NOT reset/stashed/cleaned)
```

---

## 2. Frozen Facts (from 393b2a1 — NOT re-litigated)

```text
APP_LAUNCH_AUTO_CONNECT_CANONICAL     = NO
EXECUTION_TRIGGERED_READINESS_CANONICAL = YES

PAIR_SUCCESS_CALLS_ENSURE_READY       = YES (PairActivity.kt:144)
PAIR_SUCCESS_CALLS_RETRY_PATH         = NO
MAIN_ACTIVITY_AUTO_CONNECT_AFTER_PAIR = NO (passive projection)
```

PairActivity flow (frozen): `Outcome.SUCCESS` → `control.ensureReady {}` →
check `AppRuntime.adbState()` → READY branch / "连接建立中…" branch → `finish()`.

---

## 3. Q1 / Q5 / Q6 — Call Chain (PAIR_CONTROL_ENSURE_READY_CHAIN)

```text
PairActivity.doPair (PairActivity.kt:144)
  control = AppRuntime.adbControl : ATAndroidAdbControl
  → ATAndroidAdbControl.ensureReady(onStatusLine)        (ATAndroidAdbControl.kt:99-100)
      = AdbRuntime.ensureReady(identityDir, onStatusLine) == AdbRuntimeState.READY
  → AdbRuntime.ensureReady(keyDir, statusListener)       (AdbRuntime.kt:263-331)
      → [fast path] state == READY → return READY
      → [WD gate] readiness(); if DISABLED: auto-WD (canWriteSecureSettings)
          → waitForWdEnabled(8_000L) bounded wait
      → [bounded loop] for (attempt in 1..3)
          → connectAuto(keyDir)                          (AdbRuntime.kt:532-577)
              = lock.withLock {
                    readiness() gate (DISABLED → USER_ACTION_REQUIRED)
                    setState(CONNECTING)
                    candidates = store.rediscover()      (mDNS; empty → ENDPOINT_NOT_FOUND)
                    tryConnectCandidates(candidates, keyDir)
                      = for each candidate: connectOne("127.0.0.1", port)
                          then connectOne(candidate.host, port)   (AdbRuntime.kt:721-735)
                          → AdbEngine.connect(host, port, keyDir)  (JNI, blocking)
                    success → connectionIntent = Auto; setState(READY)   (L557-559)
                    fail    → connectionIntent = None; setState(CONNECT_FAILED) (L569-570)
                  }
          → if READY / USER_ACTION_REQUIRED / pairing-diag → break
          → else Thread.sleep(2_000L)
      → return finalState (READY | USER_ACTION_REQUIRED | CONNECT_FAILED |
                           ENDPOINT_NOT_FOUND)
```

```text
PAIR_CONTROL_ENSURE_READY_CHAIN =
  PairActivity → AppRuntime.adbControl (ATAndroidAdbControl)
    → ATAndroidAdbControl.ensureReady (Boolean = (runtime state == READY))
      → AdbRuntime.ensureReady (AdbRuntimeState; fast path; WD gate;
         3× connectAuto bounded loop with 2 s sleeps)
        → connectAuto (lock.withLock; rediscover; tryConnectCandidates;
           AdbEngine.connect JNI; setState READY / CONNECT_FAILED)
```

---

## 4. Q1 — Signatures (CONTROL/RUNTIME ensureReady)

```text
CONTROL_ENSURE_READY_RETURN_TYPE = Boolean
  (ATAndroidAdbControl.kt:99-100: AdbRuntime.ensureReady(...) == AdbRuntimeState.READY)

RUNTIME_ENSURE_READY_RETURN_TYPE = AdbRuntimeState
  (AdbRuntime.kt:263-266)

ENSURE_READY_BLOCKING = YES
  Synchronous Kotlin function (non-suspend, no callback, no coroutine).
  Runs on the CALLER thread. PairActivity calls it inside a plain
  `Thread { ... }.start()` (PairActivity.kt:128) — UI thread is NOT blocked.
  Main-thread callers (MainActivity L287/L314, AppRuntime.connectAuto L142)
  block the main thread for the duration (bounded, see §7).

ENSURE_READY_HAS_BOUNDED_RETRIES = YES (3 attempts at connectAuto)
ENSURE_READY_HAS_TIMEOUT = PARTIAL
  - auto-WD enable wait: waitForWdEnabled(8_000L) — explicit 8 s bound (L283)
  - connect attempts: 3 × (rediscover + N candidate AdbEngine.connect calls)
    + 2 × Thread.sleep(2_000L)
  - AdbEngine.connect itself has no visible app-level timeout in this file
    (JNI-level; STATICALLY_UNRESOLVED for an exact wall-clock total).
```

---

## 5. Q2 — PairActivity `ready` Variable Audit (PAIR_READY_RETURN_VALUE_USED)

```kotlin
// PairActivity.kt:144-163
val ready = control.ensureReady { }
if (AppRuntime.adbState() == ATAdbReadinessState.READY) { ... }
else { "配对成功，连接建立中…" }
```

```text
PAIR_READY_RETURN_VALUE_USED = NO
  The `ready` local is assigned at line 144 and never read anywhere in
  PairActivity (verified: no other occurrence of `ready` in the file).
  The READY decision at line 145 is made from the SECOND, independent read
  `AppRuntime.adbState()`.

PAIR_READY_RETURN_VALUE_SEMANTICS =
  control.ensureReady returns Boolean = (AdbRuntime final state == READY)
  (ATAndroidAdbControl.kt:100). It is functionally equivalent to the
  adbState() check — but the code ignores it and re-reads state.
  No logic depends on the return value. (Cosmetic dead value; NOT a bug:
  both reads observe the same AdbRuntime.state, written synchronously by the
  same thread before either read occurs.)
```

---

## 6. Q3 / Q9 — Return Condition Audit

`AdbRuntime.ensureReady` returns `finalState` (L330), which is:
- `READY` only when a `connectAuto` attempt succeeded and `setState(READY)`
  executed (L559) — i.e. the final state IS READY.
- `USER_ACTION_REQUIRED` (WD off, no auto-WD capability, or auto-WD failed)
  — L280/L286/L291 early returns, or loop break L317.
- `CONNECT_FAILED` / `ENDPOINT_NOT_FOUND` — loop exhausted without READY
  (connectAuto's own setState, L548/L570).

Because `finalState` is assigned from `readinessState()` (L314) immediately
after each `connectAuto`, and `connectAuto` sets READY synchronously on
success, the returned value exactly reflects the live state.

```text
ENSURE_READY_TRUE_REQUIRES_READY_STATE = YES
  (control-level Boolean is literally `== AdbRuntimeState.READY`)

ENSURE_READY_FALSE_CAN_HAVE_CONNECT_IN_PROGRESS = NO
  There is no in-flight connection after return: connectAuto is
  lock.withLock-synchronous; the retry loop is sequential; nothing is
  launched (see §8). A false return means the bounded attempt ENDED in a
  terminal non-READY state (CONNECT_FAILED / ENDPOINT_NOT_FOUND /
  USER_ACTION_REQUIRED).
```

---

## 7. Q10 — Retry Semantics

```text
ENSURE_READY_RETRY_COUNT = 3   (for (attempt in 1..3), AdbRuntime.kt:306)
ENSURE_READY_RETRY_BACKOFF = fixed 2_000 ms between attempts (L319);
                            NO backoff on the 3rd attempt's failure
                            (loop exits after the 3rd connectAuto).
ENSURE_READY_TOTAL_BOUND = 3 × (rediscover() + per-candidate
                            AdbEngine.connect calls) + 2 × 2_000 ms
                            [+ optional 8_000 ms auto-WD wait if WD was off].
  Exact wall-clock: STATICALLY_UNRESOLVED (depends on rediscover() mDNS
  timeout and JNI-level AdbEngine.connect duration). The STRUCTURAL bound
  is exact: at most 3 connectAuto passes, at most 2 sleeps of 2 s.
```

Early breaks (no retry): READY (L315), USER_ACTION_REQUIRED (L317),
pairing diagnostic (L318).

---

## 8. Q4 / Q11 / Q12 — Async / Background Work Audit

Searched AdbRuntime.kt for `launch | async | CoroutineScope | Job | Handler |
post | executor | Thread | callback | observer`:

The ONLY background thread in AdbRuntime is the transport-loss observer
(AdbRuntime.kt:208-230):

```text
  - started in initialize(), stopped in disconnect()/resetForTest
  - polls transportState().isOnline every TRANSPORT_POLL_MS
  - on a single ONLINE → !ONLINE edge WHILE state == READY (L212-216)
    invokes onObservedTransportLost() → onOperationResult(true)
  - READ-ONLY projection: it NEVER connects, never calls ensureReady,
    never calls connectAuto, never starts recovery of a not-yet-READY
    runtime (L214 guards on state == READY).
```

No coroutine, no Handler.post of a connect, no executor, no job survives
`ensureReady`'s return.

```text
POST_ENSURE_READY_BACKGROUND_CONNECT = NO
  After ensureReady returns, NO thread/coroutine/job continues a connect.
  The only live background thread (transport observer) cannot initiate a
  connect: it requires an ONLINE→!ONLINE edge from a previously ONLINE
  transport AND state == READY — neither holds after a failed post-pair
  ensureReady (transport was never ONLINE; state != READY).

CONNECT_AUTO_SYNCHRONOUS = YES
  connectAuto is entirely inside lock.withLock { ... } (L532). It blocks
  through rediscover + all candidate AdbEngine.connect JNI calls and only
  returns after the state is settled (READY or CONNECT_FAILED/
  ENDPOINT_NOT_FOUND/USER_ACTION_REQUIRED).

CONNECT_AUTO_RETURNS_ONLY_AFTER_CNXN_READY = YES (in the failure sense:
  it returns only AFTER the attempt has terminated — either the connection
  is READY (setState L559) or all candidates failed (setState L570).
  It never returns "still connecting in background".)
```

---

## 9. Q13 — READY State Transition Owner

```text
READY_STATE_WRITE_SITE = AdbRuntime.kt:559  (connectAuto success path)
  Also: L588 (explicit connect), L676 (recovery success) — the complete set
  of setState(AdbRuntimeState.READY) sites in the repo (verified by grep:
  exactly these 3).
READY_STATE_OWNER = AdbRuntime (single owner; connectOne → AdbEngine.connect
  JNI handshake completion is the underlying event, but the STATE write is
  owned exclusively by AdbRuntime inside its lock).
```

---

## 10. Q14 — PairActivity "连接建立中…" Branch

```text
PAIR_CONNECTING_BRANCH_REACHABLE = YES
PAIR_CONNECTING_BRANCH_REASON = A (ensureReady does not guarantee READY)
  The branch is reached whenever the bounded 3×connect attempt ends without
  READY: e.g. adbd listener not up yet (slow bring-up), endpoint discovery
  timing, transient TLS/timeout failure. It is a REAL, designed path — the
  status message "配对成功，连接建立中…" is honest: pairing SUCCEEDED, the
  connect attempt was made, but the runtime is not READY yet. It is NOT a
  state-projection lag (C is wrong: there is no async connect to lag with)
  and NOT defensive-only (D is wrong: the branch is reachable).
  IMPORTANT NUANCE: the branch name says "establishing connection…" but by
  the time the user sees it, the bounded attempt has ALREADY FINISHED
  (synchronous). The text is slightly optimistic — the accurate meaning is
  "pair success; bounded connect attempt did not reach READY; next
  execution will retry".
```

---

## 11. Q15 — PairActivity finish() Audit

`finish()` (PairActivity.kt:178) destroys the Activity. The connect work
was done by a plain `Thread` started at L128 — it is NOT bound to the
Activity lifecycle, NOT a coroutine in the Activity scope, and had ALREADY
completed (synchronously, before `mainHandler.post`) by the time
`finish()` runs.

```text
PAIR_ACTIVITY_FINISH_CANCELS_CONNECT = NOT_APPLICABLE
  There is nothing to cancel: the connect finished before finish().
RUNTIME_CONNECT_SURVIVES_ACTIVITY_FINISH = YES (trivially)
  The AdbRuntime (singleton, app-scoped) and its READY state / connection
  are independent of PairActivity's lifetime. If READY was reached, it
  persists after finish(); if not, nothing is running — it simply stays
  non-READY until the next trigger.
```

---

## 12. Q16 — MainActivity Resume Audit

```text
MAIN_ACTIVITY_PASSIVE_AFTER_PAIR = YES
MAIN_ACTIVITY_INDIRECT_CONNECT_TRIGGER = NO
```

- MainActivity has NO onResume/onStart auto-connect (frozen fact; the only
  ensureReady call sites in MainActivity are L287 openAdbSessionAsync and
  L314 retryAdb — both explicit user actions).
- Its AdbCliStatusHub subscription (AdbCliStatusHub.kt:40) is a
  READ-ONLY lifecycle listener (`addLifecycleListener` → state projection
  to UI). Listeners only OBSERVE (AdbRuntime.setState L758-764 is
  notification-only); no listener triggers a connect.
- The transport observer (only background thread) cannot fire post-pair
  failure (§8): it requires a prior ONLINE edge + state==READY.

---

## 13. Q17 — Recovery Coordinator Audit

```text
RECOVERY_COORDINATOR_AUTO_RECOVERS_POST_PAIR = NO
RECOVERY_REQUIRES_ACTIVE_CONNECTION_FAILURE = YES
```

`onOperationResult(transportLost=true)` (AdbRuntime.kt:645-657) runs
recovery ONLY when `state == READY && intent is not None` (L647-649).
After a FAILED post-pair ensureReady, state is CONNECT_FAILED (not READY)
and `connectionIntent = None` (set by connectAuto L569) → recovery is
skipped, only `setState(TRANSPORT_LOST)` reflection would occur (and in
practice nothing calls onOperationResult at all in that situation).

Recovery ingress exists only from:
1. the transport observer's ONLINE→!ONLINE edge while READY (§8), and
2. `AdbBackend` operation failures (at-backend-adb AdbBackend.kt:99/136)
   — i.e. an operation on a previously-READY connection.

Neither can fire after a never-connected post-pair state. The
AdbRecoveryCoordinator.recover() itself is synchronous + bounded
(policy.maxAttempts, backoff) — it would never be a "background eventual
READY" mechanism even if triggered.

---

## 14. Q18 — Post-Pair State Machine (POST_PAIR_VALID_STATE_SEQUENCE)

From `PAIRING_REQUIRED` (pre-pair), after pair success + ensureReady:

```text
happy path:
  PAIRING_REQUIRED → (pair OK; ensureReady entry) CONNECTING
    → READY                                   [ensureReady returns READY]

transient-failure path:
  PAIRING_REQUIRED → CONNECTING → CONNECT_FAILED   [3 attempts exhausted]
  (connectionIntent = None; state stays CONNECT_FAILED)

no-listener / no-endpoint path:
  PAIRING_REQUIRED → CONNECTING → ENDPOINT_NOT_FOUND

WD-off path (no auto-WD capability):
  PAIRING_REQUIRED → USER_ACTION_REQUIRED      [ensureReady early return]

Subsequent execution (New Session / ADB command / Retry):
  CONNECT_FAILED | ENDPOINT_NOT_FOUND | USER_ACTION_REQUIRED
    → (ensureReady again) CONNECTING → READY | CONNECT_FAILED | ...

RECOVERY_* states are NOT reachable post-pair without a prior READY
  (recovery requires wasReady && intent != None, §13).
```

---

## 15. Q19 — Test Semantics Audit (what tests actually assert)

**ConformancePairingTest.firstUsePairing** (at-consumer-conformance-host,
androidTest — real device):
- ASSERTS: `result.success` (pair success, L41-45).
- `ensureReady` is called at L48 and its result is **printed only** (L51) —
  NOT asserted. It models the canonical "drive ensureReady after pairing"
  sequence but does not assert READY.

**ConformanceTest** (L82, L95, L124, L161):
- ASSERTS `comp.adbControl.ensureReady { } == true` — but these run in
  already-paired / steady-state conditions, i.e. they assert
  "ensureReady can reach READY" in general, NOT the post-pair sequence.

**TrustLossRecoveryTest T6** (unit, adb-engine):
- ASSERTS: trust-rejection flag cleared + `isCurrentKeyPaired()` true
  ("auto reconnect path (not pairing)", L163-166).
- It asserts the METADATA precondition for reconnection, NOT a connect
  attempt, NOT READY, NOT no-manual-action.

No test asserts "pair success → READY before the pair flow returns" in
isolation; no test asserts "eventual READY without action"; no test
asserts "no manual action required".

```text
TEST_ASSERTS_CONNECT_ATTEMPT = NO
  (no test drives ensureReady immediately after pair AND asserts the
   attempt/its non-READY outcome; ConformancePairingTest drives it but
   does not assert)
TEST_ASSERTS_READY_BEFORE_PAIR_FLOW_RETURNS = NO
TEST_ASSERTS_EVENTUAL_READY = NO
TEST_ASSERTS_NO_MANUAL_ACTION = NO
```

---

## 16. Q20 — Historical Gate Reinterpretation (AT.REPAIRING-UX-1 Gate C)

```text
Gate C record:
  AUTO_RECONNECT_AFTER_PAIR = PASS (ADB: READY after pairing)
  READY_AFTER_PAIR = PASS
```

Reclassified against the source semantics established here:

```text
HISTORICAL_GATE_PROVES = READY_OBSERVED
```

- It proves READY was OBSERVED after pairing (a real-device fact).
- It does NOT prove STRICT_AUTO_READY: the evidence does not exclude that a
  manual tap (Retry / New Session / any button) occurred between pair
  success and the READY observation. The historical doc has no "no-action"
  annotation.
- It does NOT merely prove ATTEMPT: the observation was READY, not
  "attempted".
- Per the current source (§3-§9), READY-after-pair CAN be reached by the
  flow's own bounded ensureReady (happy path), so READY_OBSERVED is
  consistent with Model B's happy path — but the evidence alone cannot
  distinguish "flow's own attempt reached READY" from "a later manual
  action reached READY".

---

## 17. Q21 — Semantic Decision

The source trace (§3-§13) matches exactly ONE model:

- NOT Model A: `ensureReady` can and does return non-READY
  (USER_ACTION_REQUIRED / CONNECT_FAILED / ENDPOINT_NOT_FOUND); READY is
  not guaranteed.
- NOT Model C: nothing runs in the background after return (§8, §13).
  There is no async continuation, no recovery trigger for the never-connected
  state, no observer-driven reconnect.
- IS Model B: Pair SUCCESS → the pairing flow performs a BOUNDED
  (3 × connectAuto, 2 s fixed sleep, optional 8 s auto-WD wait)
  SYNCHRONOUS connect attempt; the outcome is READY or a terminal
  non-READY state; no background continuation; if not READY, the next
  execution trigger (New Session / ADB command / Retry button — each of
  which calls ensureReady) performs another bounded attempt.

```text
PAIR_POST_SUCCESS_SEMANTICS = BOUNDED_AUTO_CONNECT_ATTEMPT   (Model B)
```

This CORRECTS the over-strong wording of 393b2a1
(`PAIR_SUCCESS_IMPLIES_READY = YES`): the correct statement is
`PAIR_SUCCESS_PERFORMS_BOUNDED_AUTO_CONNECT_ATTEMPT = YES`,
`PAIR_SUCCESS_IMPLIES_READY = NO` (READY is the happy-path outcome, not a
guarantee).

---

## 18. Q22 — Product Guarantee (source-based)

```text
PAIR_SUCCESS_GUARANTEES_CONNECT_ATTEMPT = YES
  (PairActivity.kt:144 always calls control.ensureReady on SUCCESS;
   ensureReady always performs at least one connectAuto pass unless the
   WD gate short-circuits to USER_ACTION_REQUIRED)

PAIR_SUCCESS_GUARANTEES_READY = NO
  (3 bounded attempts; terminal non-READY returns are possible)

PAIR_SUCCESS_GUARANTEES_EVENTUAL_READY = NO
  (no background continuation, §8/§13; eventual READY requires a later
   user execution trigger — consistent with EXECUTION_TRIGGERED_
   READINESS_CANONICAL = YES)

PAIR_SUCCESS_REQUIRES_USER_ACTION_TO_START_CONNECT = NO
  (the connect attempt is initiated by the pairing flow itself, not by
   the user; the user's later action is only NEEDED IF the bounded attempt
   failed)
```

---

## 19. Q23 — Release-Gate Re-evaluation

Model B is selected → the gate set is the **bounded-attempt** variant:

```text
PAIR_RESULT = PASS
PAIRING_REGENERATED_KEY = NO
PAIR_POST_SUCCESS_CONNECT_ATTEMPT = PASS
  (pair success drives ensureReady automatically — no user action needed
   to START the connect; source-structural, PairActivity.kt:144)

POST_PAIR_STATE = READY | CONNECT_FAILED | USER_ACTION_REQUIRED |
                  ENDPOINT_NOT_FOUND   (record the OBSERVED value on device)

POST_PAIR_ON_DEMAND_CONNECT = PASS
  (if POST_PAIR_STATE != READY, a normal New Session / ADB command /
   Retry — i.e. execution-triggered ensureReady — reaches READY without
   re-pairing; no special recovery path needed)

MANUAL_RETRY_REQUIRED_AFTER_PAIR = NO
  (in the normal case the flow's own attempt reaches READY; a manual retry
   is ONLY a recovery path for the transient-failure case, not a required
   part of the normal pairing flow)
```

`WIRELESS_READY_AFTER_PAIR` is **NOT** a code guarantee and must NOT be
recorded as PASS on code-semantic grounds alone. If the clean device run
observes READY immediately after pair success with no manual tap, it may be
recorded as an OBSERVED outcome (`POST_PAIR_STATE = READY`), which is the
happy path of Model B — but the release gate itself is the
CONNECT_ATTEMPT + ON_DEMAND_CONNECT pair above.

This SUPERSEDES the 393b2a1 recommendation (which retained
`PAIR_AUTO_RECONNECT = PASS` / `WIRELESS_READY_AFTER_PAIR = PASS` as
code-guaranteed gates). The historical wording is retained as a
READY_OBSERVED record (§16), not as a semantic guarantee.

---

## 20. Q24 — No-Device Decision

```text
IS_DEVICE_TEST_NEEDED_TO_UNDERSTAND_CODE_SEMANTICS = NO
```

The source fully determines the semantics (Model B). A device run remains
useful LATER to confirm the runtime implementation matches this expectation
(i.e. that the happy path actually reaches READY after pair on the target
device) — that is a RESUME-AT.0.4.1-RELEASE-V2 activity, not a semantic
question. No fresh install is required or authorized by THIS round.

---

## 21. Final Report

```text
STEP = AT.PAIR-POST-SUCCESS-RUNTIME-SEMANTICS-1
VERDICT = PASS (Model B: BOUNDED_AUTO_CONNECT_ATTEMPT)
BASE_HEAD = 393b2a1
FINAL_HEAD = <this docs-only commit>

PAIR_CONTROL_ENSURE_READY_CHAIN =
  PairActivity → AppRuntime.adbControl (ATAndroidAdbControl)
  → ATAndroidAdbControl.ensureReady (Boolean = (runtime state == READY))
  → AdbRuntime.ensureReady (AdbRuntimeState; fast path; WD gate;
     3× connectAuto bounded loop, 2 s sleeps)
  → connectAuto (lock.withLock; rediscover; tryConnectCandidates;
     AdbEngine.connect JNI; setState READY/CONNECT_FAILED)

CONTROL_ENSURE_READY_RETURN_TYPE = Boolean
RUNTIME_ENSURE_READY_RETURN_TYPE = AdbRuntimeState

ENSURE_READY_BLOCKING = YES
ENSURE_READY_HAS_BOUNDED_RETRIES = YES
ENSURE_READY_HAS_TIMEOUT = PARTIAL (8 s auto-WD wait explicit;
  connect pass duration JNI-level, STATICALLY_UNRESOLVED)

ENSURE_READY_RETRY_COUNT = 3
ENSURE_READY_TOTAL_BOUND = 3×(rediscover + candidate connects) + 2×2 s
  [+ 8 s auto-WD wait if WD off]; exact wall-clock STATICALLY_UNRESOLVED,
  structural bound exact

PAIR_READY_RETURN_VALUE_USED = NO
PAIR_READY_RETURN_VALUE_SEMANTICS = (AdbRuntime final state == READY);
  assigned at PairActivity.kt:144 but never read; READY decision made from
  independent AppRuntime.adbState() read at line 145 (equivalent, same
  thread, same state)

ENSURE_READY_TRUE_REQUIRES_READY_STATE = YES
ENSURE_READY_FALSE_CAN_HAVE_CONNECT_IN_PROGRESS = NO

POST_ENSURE_READY_BACKGROUND_CONNECT = NO
  (only background thread = read-only transport-loss observer; requires
   ONLINE→!ONLINE edge + state==READY; cannot fire post-pair failure)

CONNECT_AUTO_SYNCHRONOUS = YES
CONNECT_AUTO_RETURNS_ONLY_AFTER_CNXN_READY = YES

READY_STATE_WRITE_SITE = AdbRuntime.kt:559 (connectAuto; also L588 explicit,
  L676 recovery — the complete set)
READY_STATE_OWNER = AdbRuntime

PAIR_CONNECTING_BRANCH_REACHABLE = YES
PAIR_CONNECTING_BRANCH_REASON = A (ensureReady does not guarantee READY;
  bounded attempt may end non-READY; branch text "连接建立中…" is slightly
  optimistic since the attempt has already finished by the time it shows)

PAIR_ACTIVITY_FINISH_CANCELS_CONNECT = NOT_APPLICABLE (connect finished
  synchronously before finish())
RUNTIME_CONNECT_SURVIVES_ACTIVITY_FINISH = YES (trivially; AdbRuntime is
  app-scoped singleton)

MAIN_ACTIVITY_PASSIVE_AFTER_PAIR = YES
MAIN_ACTIVITY_INDIRECT_CONNECT_TRIGGER = NO
  (AdbCliStatusHub listener is read-only; no onResume connect)

RECOVERY_COORDINATOR_AUTO_RECOVERS_POST_PAIR = NO
RECOVERY_REQUIRES_ACTIVE_CONNECTION_FAILURE = YES
  (requires wasReady && intent != None; ingress only from observed loss
   edge while READY, or AdbBackend operation failure)

POST_PAIR_VALID_STATE_SEQUENCE =
  PAIRING_REQUIRED → CONNECTING → READY
  | PAIRING_REQUIRED → CONNECTING → CONNECT_FAILED | ENDPOINT_NOT_FOUND
  | PAIRING_REQUIRED → USER_ACTION_REQUIRED
  (then, on next execution trigger: → CONNECTING → READY | ...)

TEST_ASSERTS_CONNECT_ATTEMPT = NO
TEST_ASSERTS_READY_BEFORE_PAIR_FLOW_RETURNS = NO
TEST_ASSERTS_EVENTUAL_READY = NO
TEST_ASSERTS_NO_MANUAL_ACTION = NO
  (ConformancePairingTest: asserts pair success only; ensureReady printed
   not asserted. ConformanceTest: asserts ensureReady==true in steady state.
   TrustLossRecoveryTest T6: asserts metadata validity, not a connect.)

HISTORICAL_GATE_PROVES = READY_OBSERVED
  (AT.REPAIRING-UX-1 Gate C proves READY was observed after pairing; does
   NOT prove strict no-action auto-READY)

PAIR_POST_SUCCESS_SEMANTICS = BOUNDED_AUTO_CONNECT_ATTEMPT

PAIR_SUCCESS_GUARANTEES_CONNECT_ATTEMPT = YES
PAIR_SUCCESS_GUARANTEES_READY = NO
PAIR_SUCCESS_GUARANTEES_EVENTUAL_READY = NO
PAIR_SUCCESS_REQUIRES_USER_ACTION_TO_START_CONNECT = NO

MANDATORY_RELEASE_GATE_SHOULD_BE =
  PAIR_RESULT=PASS
  PAIRING_REGENERATED_KEY=NO
  PAIR_POST_SUCCESS_CONNECT_ATTEMPT=PASS
  POST_PAIR_STATE=<observed: READY|CONNECT_FAILED|USER_ACTION_REQUIRED|
                  ENDPOINT_NOT_FOUND>
  POST_PAIR_ON_DEMAND_CONNECT=PASS
  MANUAL_RETRY_REQUIRED_AFTER_PAIR=NO
  (WIRELESS_READY_AFTER_PAIR is NOT a code guarantee; if observed READY with
   no manual tap, record as POST_PAIR_STATE=READY, the happy path of Model B)

IS_DEVICE_TEST_NEEDED_TO_UNDERSTAND_CODE_SEMANTICS = NO

PRODUCT_CODE_CHANGED = NO
TEST_CODE_CHANGED = NO
VERSION_BUMP_TOUCHED = NO
TAG_CREATED = NO
PUSHED = NO
RELEASED = NO

NEXT = RESUME AT.0.4.1-RELEASE-V2 with the Model-B gate set above; one clean
       strict fresh-install pairing run (no manual tap) to record
       POST_PAIR_STATE and POST_PAIR_ON_DEMAND_CONNECT, then continue
       release (commit / tag v0.4.1 / push / GitHub Release).
STOP = YES
```
