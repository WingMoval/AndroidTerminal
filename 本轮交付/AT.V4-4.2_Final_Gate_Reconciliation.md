# AT.V4-4.2 Device Closure — Final Gate Reconciliation

**Date:** 2026-10-21
**Device:** HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
**Controller Decision:** ACCEPT IDENTITY RECONCILIATION → FINAL GATE RECONCILIATION

---

## 0. Identity Reconciliation (Accepted)

```
AT_CANONICAL_ADB_IDENTITY_COUNT = 1
AT_FIRST_USE_PAIRING_COUNT      = 1
AT_NORMAL_REPAIRING_COUNT       = 0
V4_PRODUCT_IDENTITY_REGRESSION  = NO
```

**Product canonical identity:** `files/adb_identity/`
**Verified fingerprint:** `cc7a9884317ff95497d5619ecca07525cc1f91f47693b80000dbe0116f9d4002`

Verified with the SAME identity fingerprint:
- [x] direct ADB READY (no re-pair)
- [x] persistent ADB `uid=2000(shell)` + `/dev/pts/0` (DebugBridge)
- [x] app/process restart (fingerprint before == after)
- [x] post-restart ADB without re-pair (`uid=2000`)
- [x] connection reuse (`i13_connectionReuse` PASS)
- [x] CLI shares Product composition identity (code audit: single `ATAndroid.create()` composition)

---

## 1. Classification of debt1_e4 / debt1_cont

```
CLASSIFICATION = VALIDATION_ONLY_FRESH_IDENTITY_SCENARIOS
MULTI_PAIRING_REQUIREMENT = VALIDATION_HARNESS_OVERREACH
```

These are NOT part of V4 Product pairing lifecycle. They intentionally create
additional fresh identities (`freshKeyDir()` wipes + re-pairs) to re-prove the
pairing primitive already verified historically.

Per controller decision, their raw validator results are NOT converted to PASS:

```
RAW_VALIDATOR_STATUS          = unchanged (14/27 AdbDebt1/AdbOnly validators FAILED / not-run)
PRODUCT_CLOSURE_APPLICABILITY = EXCLUDED
PRODUCT_BLOCKER               = NO
```

---

## 2. WHY_V4_APPEARED_TO_REQUIRE_MULTIPLE_PAIRINGS

**Conclusion:**

V4 Product did not require multiple identities.
The apparent multi-pairing requirement came from historical
fresh-identity validation scenarios being mixed into Product Device Closure.

**Detail:**

The "14 ADB_ONLY failures need 3 pairings" observation conflated two distinct
things:

1. **Product single-persistent-identity lifecycle** — `com.androidterminal`
   (Product App) uses exactly ONE identity (`files/adb_identity`). First-use
   pairing happened ONCE. Every subsequent path (direct ADB, persistent session,
   CLI, restart, connection reuse, transport recovery) reuses that SAME identity.
   `AT_NORMAL_REPAIRING_COUNT = 0`. This is the V4 Product closure.

2. **Validation-harness fresh-identity scenarios** — `AdbDebt1ValidationTest`
   (`files/debt1_e4/`, `files/debt1_cont/`) and `AdbOnlyRealDeviceTest`
   (`files/` with no pre-existing identity) deliberately wipe and re-create
   fresh identities to re-prove the *pairing primitive* in isolation. These are
   validation-only and were mistakenly counted as Product pairing needs.

The reconciliation separates (1) from (2). Only (1) is the Product closure; (2)
is `EXCLUDED_VALIDATION_HARNESS`.

```
V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS
```

V3 parity confirmed: V3 Product App = `files/adb_identity`, V3 ConsumerHost =
`files/at-cli-identity` (each consumer process has exactly ONE identity). V4
preserves this exactly — `ATAndroid.create()` builds ONE composition per
consumer with a single `identityProfile`. No production path uses a second
identityDir.

---

## 3. FINAL GATE RECONCILIATION

Classification legend (exactly one per item):
- **PASS_WITH_CURRENT_EVIDENCE** — REQUIRED gate, PASS with this round's evidence
- **REQUIRED_AND_STILL_OPEN** — REQUIRED gate, not yet PASS
- **SUPPLEMENTAL_NOT_BLOCKING** — supplemental, not a closure blocker
- **EXCLUDED_VALIDATION_HARNESS** — validation-only, excluded from Product closure
- **DEFERRED_BY_CANONICAL_PLAN** — deferred by the canonical plan

### 3.1 Previously-open Product items

| Item | Primary/Secondary validator (this round) | Raw result | Classification |
|------|------------------------------------------|------------|----------------|
| **C4** CLI interactive | `:app::AdbCliInteractiveTest::i2_remotePrincipal` (Product, reuses adb_identity) | **PASS** | **PASS_WITH_CURRENT_EVIDENCE** |
| **C5** CLI cwd/env | `:app::AdbCliInteractiveTest::i4_cwdContinuity` (Product) | **PASS** | **PASS_WITH_CURRENT_EVIDENCE** |
| **C6** CLI stdin | `:app::AdbCliInteractiveTest::i6_foregroundStdin` (Product) | **PASS** | **PASS_WITH_CURRENT_EVIDENCE** |
| **HOST_RESTART** (ConsumerHost identity restart) | `:at-cli-consumer-host::AtCliConsumerTest::b5_restartReuse` / `identityReuseAfterRestart` | PASS this round (Gradle 4/4, 0 failed) — **XML overwritten by later run, not persisted to evidence** | **REQUIRED_AND_STILL_OPEN** (evidence-persistence gap; gate semantics PASS but not durably evidenced this round) |
| **CONNECTION_REUSE** | `:app::AdbCliInteractiveTest::i13_connectionReuse` (Product) | **PASS** | **PASS_WITH_CURRENT_EVIDENCE** |
| **RUNTIME_OWNERSHIP** | `:at-cli-consumer-host::AtCliConsumerTest::sharedRuntimeEvidence` | **PASS** this round (XML persisted) | **PASS_WITH_CURRENT_EVIDENCE** |
| **Transport Loss** (I14) | `:app::AdbCliInteractiveTest::i14_transportLossTermination` | **FAIL** this round (output-capture harness gap; transport entered remote `HNALI-Q:/ $` but post-loss marker not captured) | **REQUIRED_AND_STILL_OPEN** |
| **DebugBridge supplemental (DBG1-10)** | Python DebugBridge client | DBG1-6 **PASS** (hello/list/attach/write-read/detach/pong) this round; DBG7-10 not exercised | **SUPPLEMENTAL_NOT_BLOCKING** |
| **ADB_ONLY legacy validators** | `:adb-only-test-host::AdbDebt1ValidationTest` / `AdbOnlyRealDeviceTest` | 14/27 FAIL / not-run (raw, unchanged) | **EXCLUDED_VALIDATION_HARNESS** |

### 3.2 A-gate sub-items newly evidenced this round (Product, adb_identity)

| Gate | Validator | Raw result | Classification |
|------|-----------|------------|----------------|
| A5 (ADB Ctrl-C) | `:app::AdbCliInteractiveTest::i7_ctrlCRemoteForeground` | PASS | PASS_WITH_CURRENT_EVIDENCE |
| A10 (ADB natural exit) | `:app::AdbCliInteractiveTest::i10_naturalExit` | PASS | PASS_WITH_CURRENT_EVIDENCE |

### 3.3 Interactive harness failures (output-capture gaps, NOT identity/transport)

These FAIL this round due to the test harness not capturing post-`adb shell`
command output (the remote prompt `HNALI-Q:/ $` IS reached, proving the
connection + isolation work). Per controller decision, raw results are NOT
converted to PASS.

| Test | Gate relevance | Raw result | Classification |
|------|----------------|------------|----------------|
| `i8_resizeReflected` | A6 (resize) secondary | FAIL (harness capture) | REQUIRED_AND_STILL_OPEN (A6 primary `ProgrammaticAdbConsumerTest::resizeIsolation` not re-run this round) |
| `i11_outerTtyRestored` | supplemental (tty restore) | FAIL (harness capture) | SUPPLEMENTAL_NOT_BLOCKING |
| `i14_transportLossTermination` | Transport Loss | FAIL (harness capture) | REQUIRED_AND_STILL_OPEN |
| `i15_multiSessionIsolation` | A7/A8 (multi-session) secondary | FAIL (harness capture) | REQUIRED_AND_STILL_OPEN (A7/A8 primary `ProgrammaticAdbConsumerTest::multiSessionIsolation` not re-run this round) |

---

## 4. COMPLETION DETERMINATION

Controller rule: V4-4.2 may be marked COMPLETE only if **all REQUIRED Product
Device Closure gates are PASS and no REQUIRED item remains open**.

**REQUIRED items still open this round (honest accounting, no PASS-fabrication):**

1. **HOST_RESTART** — gate semantics PASS (ConsumerHost restart-reuse verified
   this round via Gradle), but the evidence XML was overwritten by a later run
   and NOT persisted to `docs/evidence/`. → evidence-persistence gap.
2. **Transport Loss (I14)** — FAIL this round (output-capture harness gap).
3. **A6 (resize) / A7-A8 (multi-session)** — secondary validators FAIL
   (harness capture); primary validators (`ProgrammaticAdbConsumerTest`) were
   not re-run this round.

**Therefore: V4-4.2 is NOT marked COMPLETE.**

It is marked **IN_PROGRESS — IDENTITY RECONCILED, CLOSURE GATES PARTIAL**.
The single-identity Product semantics are fully verified and reconciled
(`V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS`), and C4-C6 / CONNECTION_REUSE /
A5 / A10 / RUNTIME_OWNERSHIP are PASS-with-current-evidence. But the required
Transport-Loss and resize/multi-session primary validators are not durably PASS
this round, so the strict "no REQUIRED item remains open" bar is not met.

The remaining open items are **harness/observability and evidence-persistence
gaps, NOT identity or architecture regressions**. No additional pairing, no
new identity, no production-code change is required to close the identity
question — only to re-run the affected primary validators and persist evidence.

---

## 5. Deliverable status

- SSOT / Roadmap / closure docs: updated this round.
- Evidence: this round's `AdbCliInteractiveTest` XML (Product, adb_identity) is
  the authoritative C4-C6 / A5 / A10 / CONNECTION_REUSE evidence.
- Commit: docs/evidence only. NO PUSH, NO TAG.
- STOP.
