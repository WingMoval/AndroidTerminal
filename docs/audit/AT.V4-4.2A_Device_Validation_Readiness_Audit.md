# AT.V4-4.2A: Device Validation Readiness Audit

> **Date**: 2026-10-21
> **Type**: DOCS / EVIDENCE ONLY / TEST-HARNESS AUDIT / DEVICE-PRECONDITION AUDIT
> **HEAD**: 81d630a (AT.V4-4.1A)
> **Verdict**: PASS (audit complete; all blockers identified; zero unknowns)

---

## 0. Summary

This audit identifies ALL blockers that V4-4.2 device closure will encounter,
in a single pass, before any code modification. It covers:

- Build matrix (all 5 validation carriers)
- Test inventory (all test files/methods, V3 vs V4 parity)
- Runtime initialization contract (AppRuntime.initialize reachability)
- Identity ownership matrix (all ADB consumers)
- First-use pairing paths
- Destructive test identification
- Device preconditions
- Observability audit (connection reuse + runtime ownership)
- V3/V4 diff evidence (introduction commit attribution)

**No code was modified. No tests were run on device (device disconnected
during audit). All findings are from static analysis + build compilation.**

---

## 1. Build Matrix

| Module | Main Build | AndroidTest Build |
|---|---|---|
| :app | PASS | PASS |
| :test-host | PASS | **FAIL** |
| :adb-only-test-host | PASS | PASS |
| :at-shellless-test-host | PASS | **FAIL** |
| :at-cli-consumer-host | PASS | PASS |

### FAIL-1: :test-host (AdbTransportLossAuditTest.kt:77)

- **Error**: `Unresolved reference: DefaultATEngine` at line 77
- **Root cause**: V4-1 (09f2ac9) split `ATEngine` class into interface
  (`com.at.engine.ATEngine`) + impl (`com.at.engine.impl.DefaultATEngine`).
  The import at line 32 was added correctly, but the call site at line 77
  uses the WRONG fully-qualified name `com.at.engine.DefaultATEngine`
  (missing `.impl`).
- **Classification**: V4_API_RELOCATION_ADAPTATION_DEBT
- **First bad commit**: 09f2ac9 (AT.V4-1)
- **Severity**: P1 (blocks test-host androidTest compilation)
- **Minimum fix**: Change `com.at.engine.DefaultATEngine` →
  `com.at.engine.impl.DefaultATEngine` at line 77 (1 line, test-only)

### FAIL-2: :at-shellless-test-host (ShelllessRealDeviceTest.kt:204-206)

- **Error**: `Expecting '"'` — CJK UTF-8 string literals corrupted
- **Root cause**: CJK characters in `g7_utf8RoundTrip` were corrupted during
  repo creation (UTF-8 → GBK/CP936 → UTF-8 double-encoding). The SAME
  corruption exists at V3 baseline 75d5832 — V3 was ALSO non-buildable.
  V4-1 (09f2ac9) touched the file (ATSessionLaunch rename) but did not
  repair the pre-existing corruption.
- **Classification**: PREEXISTING_V3_TEST_DEBT
- **First bad commit**: pre-75d5832 (repo copy)
- **Severity**: P2 (blocks shellless host; no equivalent V3 test executable parity)
- **Minimum fix**: Restore correct CJK UTF-8 string literals (3 lines, test-only)

---

## 2. Test Inventory

- **Total test files**: 31 (30 migrated from V3 + 1 V4-new)
- **Total test classes**: ~25 (excluding inner helper classes)
- **Total unique test methods**: ~200 (excluding inner class duplicates)
- **V3 test file parity**: PASS (0 missing, 0 unauthorized drop)
- **V4-new test file**: `at-composition-android/ATAndroidTest.kt` (5 methods,
  V4-2 composition conformance)

See `AT.V4-4.2A_Test_Harness_Matrix.csv` for the full method-level matrix.

---

## 3. AppDebugInputTest Root Classification

**APPDEBUGINPUT_ROOT_CLASSIFICATION = V4_TEST_HARNESS_REGRESSION**

- **NOT** a production regression (no production call site uses the fallback)
- **NOT** a production semantic change (user-visible behavior unchanged)
- **IS** a test-convenience contract break: V3 `AppTerminal(engine=null)`
  self-constructed a Local-only engine; V4 delegates to
  `AppRuntime.localEngine()` which requires `AppRuntime.initialize(context)`.
- The test does NOT launch an Activity and does NOT call initialize.
- All other 13 :app androidTest files use ActivityScenario OR explicit
  initialize — only AppDebugInputTest is broken.

**APPDEBUGINPUT_FIRST_BAD_COMMIT = 84936f8 (AT.V4-4)**

**APPDEBUGINPUT_RECOMMENDED_OPTION = A** (test-only fix: add
`AppRuntime.initialize(ctx)` to `@Before`)

Rationale:
- Option A: 1-line test change; consistent with all other :app tests;
  zero production risk; preserves V4-4 architectural intent.
- Option B (production fallback restore): contradicts V4-4 decision;
  creates dual engine construction paths.
- Option C (alternate validator): DynamicSessionPagesTest covers L2-L7
  via Activity, but does NOT cover byte-stream semantics (partial write,
  Ctrl-C, stdin) that AppDebugInputTest uniquely validates.

---

## 4. Runtime Init Contract

**PRODUCT_RUNTIME_INIT_CONTRACT**:
`AppRuntime.initialize(context)` is called from exactly 2 production sites:
- `MainActivity.onCreate()` (line 68)
- `PairActivity.onCreate()` (line 52)

No Application class. No BroadcastReceiver/Service entrypoint.
All production `AppTerminal()` call sites pass explicit `engine` —
the `engine=null` fallback is TEST-ONLY reachable.

**TEST_RUNTIME_INIT_CONTRACT**:
Two valid patterns:
1. ActivityScenario.launch(MainActivity) → initialize via Activity
2. Explicit `AppRuntime.initialize(ctx)` in @Before/setup

AppDebugInputTest uses NEITHER → broken.

**No other test has the same pre-init access problem.**

---

## 5. Identity Ownership Matrix

| Package | Identity | Exists | First-Use Path | Pairing Entrypoint | Ready |
|---|---|---|---|---|---|
| com.androidterminal.app | adb_identity | YES (trusted) | N/A (already paired) | AppAdbProvisionTest / PairActivity | YES |
| com.androidterminal.atclihost | at-cli-identity | **NO** | discoverPairing → user 6-digit → AdbConsumerBootstrap.pair → AdbEngine.pair → ensureReady → READY | AtCliConsumerTest b3_pair / firstUsePairing | NO |
| com.androidterminal.adbonlyhost | debt1_<tag> (per-gate) | N/A (not installed) | connectAuto (creates) → discoverPairing → AdbEngine.pair → connectAuto → READY | AdbDebt1ValidationTest g2_e4_pairingParity | NO (not installed) |
| com.androidterminal.testhost | (explicit keyDir) | N/A (not installed) | N/A (test supplies keyDir; needs pre-trusted identity) | N/A | NO (not installed + compile blocker) |
| com.androidterminal.shelllesshost | N/A (no ADB) | N/A | N/A | N/A | NO (not installed + compile blocker) |

**KEY_COPY = NO** for all packages. Each consumer generates its own identity.

**ConsumerHost first-use state (empty identity) is EXPECTED, not a regression.**

---

## 6. Destructive Tests

| Test | Destructive Action | Safe if Identity Trusted? |
|---|---|---|
| AppAdbProvisionTest.provisionAppIdentity | AdbEngine.pair (mutates trust state) | NO — do not run if already trusted |
| AtCliConsumerTest.b3_pair / firstUsePairing / p1_cliPair | AdbEngine.pair via composition (creates identity) | NO — BLOCKED (no code staged); would mutate trust |
| AdbDebt1ValidationTest.g2_e4_pairingParity | AdbEngine.pair (fresh identity) | NO — requires pairing dialog + code |
| AdbDebt1ValidationTest.g4_appProcessWifiEnabledControl | Settings.Global.putInt (WRITE_SECURE_SETTINGS) | NO — mutates WD state |
| AdbCliLiveStatusTest (setWd) | Settings.Global.putInt (WRITE_SECURE_SETTINGS) | NO — mutates WD state |

**SAFE_TEST_SET**: All tests that do NOT call pair() or WRITE_SECURE_SETTINGS.
**UNSAFE_TEST_SET**: The 5 tests above.

---

## 7. Device Gate Coverage

All 34 gates (L1-L7, A1-A10, C1-C6, H1-H7, APP_RESTART, HOST_RESTART,
CONNECTION_REUSE, RUNTIME_OWNERSHIP, SHELLLESS, ADB_ONLY) have validator
mappings. See `AT.V4-4.2A_Device_Gate_Coverage_Matrix.csv`.

**Coverage status**:
- L1-L7: READY (via DynamicSessionPagesTest + AppDebugInputTest [B1 fix])
- A1-A10: READY_WITH_PRECONDITION (Product identity exists; WD needed)
- C1-C6: READY_WITH_PRECONDITION (Product identity; or ConsumerHost after H4)
- H1-H7: MIXED (H1/H2/H3 READY_WITH_PRECONDITION; H4-H7 BLOCKED by pairing)
- APP_RESTART: READY_WITH_PRECONDITION (manual)
- HOST_RESTART: BLOCKED (H4 not done)
- CONNECTION_REUSE: READY_WITH_PRECONDITION (observability = PRACTICAL)
- RUNTIME_OWNERSHIP: READY_WITH_PRECONDITION (source invariant + logcat)
- SHELLLESS: BLOCKED (compile B3 + not installed)
- ADB_ONLY: BLOCKED (not installed; lifecycle tests READY once installed)

**COVERAGE_GAP = 0** (all gates have at least one validator)

---

## 8. Observability

**CONNECTION_REUSE_OBSERVABILITY = SUFFICIENT** (practical level)
- Observable: "connectAuto READY" log (1x = initial connect; absence for
  subsequent commands = reuse implied)
- NOT observable: per-command TCP/STLS/CNXN counts (native layer opaque)
- Same evidence standard as V3

**RUNTIME_OWNERSHIP_OBSERVABILITY = SUFFICIENT**
- Observable: "initialize" log (1x per process = single owner)
- Source invariant: AdbRuntime is a process-scoped singleton (companion object)
- No duplicate ownership possible by construction

**OBSERVABILITY_GAP_COUNT = 0** (P0/P1). 3 P3 gaps documented (native opacity).

---

## 9. Blocker Summary

| ID | Component | Classification | Severity | Fix Type |
|---|---|---|---|---|
| B1 | AppDebugInputTest | V4_TEST_HARNESS_REGRESSION | P1 | TEST_ONLY_FIX |
| B2 | AdbTransportLossAuditTest | V4_API_RELOCATION_ADAPTATION_DEBT | P1 | TEST_ONLY_FIX |
| B3 | ShelllessRealDeviceTest | PREEXISTING_V3_TEST_DEBT | P2 | TEST_ONLY_FIX |
| B4 | ConsumerHost identity | FIRST_USE_BOOTSTRAP_REQUIRED | P1 | FIRST_USE_PAIRING_STEP |
| B5 | adb-only-test-host | ENVIRONMENT_PRECONDITION | P2 | ENVIRONMENT_PREP_ONLY |
| B6 | test-host | ENVIRONMENT_PRECONDITION | P2 | ENVIRONMENT_PREP_ONLY |
| B7 | at-shellless-test-host | ENVIRONMENT_PRECONDITION | P3 | ENVIRONMENT_PREP_ONLY |
| B8 | Connection reuse observability | OBSERVABILITY_GAP | P3 | NO_FIX_USE_ALTERNATE_VALIDATOR |

**P0 = 0, P1 = 3, P2 = 4, P3 = 1**

All P1/P2 blockers are TEST_ONLY_FIX or ENVIRONMENT_PREP_ONLY or
FIRST_USE_PAIRING_STEP. **NO production code change is required for any blocker.**

---

## 10. Recommended Next

**RECOMMENDED_NEXT = AT.V4-4.2B Validation Infrastructure Repair**

A single unified task to fix ALL test-only blockers before device retry:

1. **B1**: Add `AppRuntime.initialize(ctx)` to `AppDebugInputTest` @Before (1 line)
2. **B2**: Fix FQN at `AdbTransportLossAuditTest.kt:77` (1 line)
3. **B3**: Restore CJK UTF-8 strings in `ShelllessRealDeviceTest.kt` g7 (3 lines)

Then:
4. **B5/B6/B7**: Install test host APKs on device (adb install -r)
5. **B4**: User completes ConsumerHost first-use pairing (6-digit code)
6. **V4-4.2 RETRY**: Run device closure gates

**NO production regression fix is needed.** No SCOPED_PRODUCTION_REGRESSION_FIX required.

---

## 11. Hard PASS Criteria Check

| Criterion | Status |
|---|---|
| ALL_VALIDATION_CARRIERS_INVENTORIED | YES (5 carriers) |
| ALL_TESTS_CLASSIFIED | YES |
| UNKNOWN_COUNT | 0 |
| ALL_BUILD_BLOCKERS_IDENTIFIED | YES (2 compile blockers) |
| ALL_IDENTITY_PRECONDITIONS_IDENTIFIED | YES |
| ALL_FIRST_USE_PATHS_IDENTIFIED | YES |
| ALL_DESTRUCTIVE_TESTS_IDENTIFIED | YES (5 tests) |
| ALL_V4_4_2_GATES_HAVE_VALIDATOR_MAPPING | YES (34 gates; 0 coverage gaps) |
| CONNECTION_REUSE_OBSERVABILITY_CLASSIFIED | YES (SUFFICIENT) |
| RUNTIME_OWNERSHIP_OBSERVABILITY_CLASSIFIED | YES (SUFFICIENT) |
| ALL_BLOCKERS_HAVE_MINIMUM_FIX | YES (8 blockers) |

**AUDIT PASS = YES**

---

## 12. Deliverables

- `docs/audit/AT.V4-4.2A_Device_Validation_Readiness_Audit.md` (this file)
- `docs/audit/AT.V4-4.2A_Test_Harness_Matrix.csv`
- `docs/audit/AT.V4-4.2A_Device_Gate_Coverage_Matrix.csv`
- `docs/audit/AT.V4-4.2A_Blocker_Matrix.csv`
- `docs/evidence/at_v4_4_2a/` (8 evidence files)
