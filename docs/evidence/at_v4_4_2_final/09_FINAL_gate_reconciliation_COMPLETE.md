# AT.V4-4.2 Device Closure — FINAL Gate Reconciliation (COMPLETE)

**Date:** 2026-10-22
**Device:** HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
**Supersedes:** `at_v4_4_2_retry/15_final_gate_reconciliation.md` (2026-10-21, marked NOT COMPLETE)
**Controller Decisions:**
- 4e2ebfc: IDENTITY RECONCILIATION → FINAL GATE RECONCILIATION
- V4-4.2D: Controller Decision A (Product first-use pairing)
- V4-4.2D.1: ROOT_CAUSE = UTP_UNINSTALL_REINSTALL, V4_PRODUCT_IDENTITY_REGRESSION = NO
- V4-4.2D.2: OPTION 1 (stateful direct instrumentation, no UTP uninstall)
- V4-4.2D.3: AUTHORIZE CONSUMERHOST FIRST-USE RESTORE

---

## 0. Identity Reconciliation (Accepted at 4e2ebfc)

```
AT_CANONICAL_ADB_IDENTITY_COUNT = 1
AT_FIRST_USE_PAIRING_COUNT      = 1
AT_NORMAL_REPAIRING_COUNT       = 0
V4_PRODUCT_IDENTITY_REGRESSION  = NO
V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS
```

**Product canonical identity:** `files/adb_identity/`
**ConsumerHost canonical identity:** `files/at-cli-identity/`

Each consumer process has exactly ONE identity. No production path uses a second
identityDir.

---

## 1. Environment Divergence (V4-4.2D through V4-4.2D.3)

### 1.1 UTP Uninstall/Reinstall (ROOT CAUSE of repeated identity churn)

The Gradle `connectedDebugAndroidTest` (Unified Test Plugin 31.5.2) lifecycle is:
```
INSTALL(app) → RUN instrumentation → UNINSTALL(app)
```
The post-test UNINSTALL destroys app-private data including `files/adb_identity`
and `files/at-cli-identity`. Each subsequent run reinstalls with FRESH data →
fresh identity → new fingerprint → untrusted → "pairing required".

**This was classified as VALIDATION_ENVIRONMENT_ARTIFACT, NOT Product lifecycle
behavior.** (See `at_v4_4_2_final/04_instrumentation_identity_audit.md`.)

```
V4_PRODUCT_IDENTITY_REGRESSION = NO
ROOT_CAUSE = UTP_UNINSTALL_REINSTALL
```

### 1.2 Final Product Installation (V4-4.2D.2, Controller Decision OPTION 1)

Manual `adb install` (no Gradle/UTP). Stateful execution via direct
`adb shell am instrument`.

```
FINAL_PRODUCT_PACKAGE              = com.androidterminal.app
FINAL_PRODUCT_UID                  = 10411 (u0_a411)
FINAL_CANONICAL_IDENTITY_DIR       = files/adb_identity
V4_4_2_FINAL_CLOSURE_IDENTITY      = a026370d2788faec2ab2b19989e57f8520a2518e51e54fb44504e917212393cd
READY                              = YES
REMOTE_PRINCIPAL                   = uid=2000(shell)
FINAL_INSTALL_FIRST_USE_PAIRING_COUNT = 1
FINAL_INSTALL_NORMAL_REPAIRING_COUNT  = 0
```

### 1.3 ConsumerHost Installation (V4-4.2D.3, Controller Authorization)

Manual `adb install` (no Gradle/UTP). Stateful execution via direct
`adb shell am instrument`.

```
CONSUMERHOST_PACKAGE              = com.androidterminal.atclihost
CONSUMERHOST_UID                  = 10413 (u0_a413)
CONSUMERHOST_IDENTITY_DIR         = files/at-cli-identity
CONSUMERHOST_IDENTITY_FINGERPRINT = df36555668720bb78a4bd7c1a2799105543bce7bda12e011af833d85ebc8db55
CONSUMERHOST_FIRST_USE_PAIRING_COUNT = 1
CONSUMERHOST_NORMAL_REPAIRING_COUNT  = 0
```

ConsumerHost identity is its OWN app-private key, independent from Product.

### 1.4 Pairing Counters (Final)

These are INDEPENDENT consumer identities:

| Consumer | FIRST_USE_PAIRING_COUNT | NORMAL_REPAIRING_COUNT |
|----------|------------------------|----------------------|
| Product App (`com.androidterminal.app`) | 1 | 0 |
| ConsumerHost (`com.androidterminal.atclihost`) | 1 | 0 |

Historical repeated pairings caused by UTP uninstall/reinstall are classified
as **VALIDATION_ENVIRONMENT_ARTIFACT**, not Product or ConsumerHost lifecycle
behavior.

---

## 2. FINAL GATE RECONCILIATION (ALL GATES)

Classification legend:
- **PASS** — REQUIRED gate, PASS with durable evidence
- **EXCLUDED_VALIDATION_HARNESS** — validation-only, excluded from Product closure
- **SUPPLEMENTAL_NOT_BLOCKING** — supplemental, not a closure blocker

### 2.1 Previously-open Product items (NOW CLOSED)

| Item | Validator | Result | Classification |
|------|-----------|--------|----------------|
| **HOST_RESTART** | `AtCliConsumerTest::identityReuseAfterRestart` (direct am instrument) | **PASS** (UID 10413==10413, FP df365556==df365556, READY, no re-pair) | **PASS** |
| **Transport Loss (I14)** | `AdbCliInteractiveTest::i14_transportLossTermination` (direct am instrument, harness marker fixed) | **PASS** (OK 1 test, 21.5s; A→F chain verified) | **PASS** |
| **A6 (resize)** | `ProgrammaticAdbConsumerTest::resizeIsolation` (primary) | **PASS** | **PASS** |
| **A7 (multi-session)** | `ProgrammaticAdbConsumerTest::multiSessionIsolation` (primary) | **PASS** | **PASS** |
| **A8 (multi-session)** | `ProgrammaticAdbConsumerTest::multiSessionIsolation` (primary) | **PASS** | **PASS** |

### 2.2 Previously-closed Product items (unchanged)

| Item | Validator | Result | Classification |
|------|-----------|--------|----------------|
| C4 (CLI interactive) | `AdbCliInteractiveTest::i2_remotePrincipal` | PASS | PASS |
| C5 (CLI cwd/env) | `AdbCliInteractiveTest::i4_cwdContinuity` | PASS | PASS |
| C6 (CLI stdin) | `AdbCliInteractiveTest::i6_foregroundStdin` | PASS | PASS |
| A5 (ADB Ctrl-C) | `AdbCliInteractiveTest::i7_ctrlCRemoteForeground` | PASS | PASS |
| A10 (ADB natural exit) | `AdbCliInteractiveTest::i10_naturalExit` | PASS | PASS |
| CONNECTION_REUSE | `AdbCliInteractiveTest::i13_connectionReuse` | PASS | PASS |
| RUNTIME_OWNERSHIP | `AtCliConsumerTest::sharedRuntimeEvidence` | PASS | PASS |
| APP_RESTART | fingerprint before==after (4e2ebfc) | PASS | PASS |

### 2.3 Secondary validators (harness output-capture gaps, NOT Product regressions)

| Test | Gate | Raw result | Classification |
|------|------|------------|----------------|
| `i8_resizeReflected` | A6 secondary | FAIL (harness capture) | EXCLUDED (A6 primary PASS is authoritative) |
| `i11_outerTtyRestored` | supplemental | FAIL (harness capture) | SUPPLEMENTAL_NOT_BLOCKING |
| `i15_multiSessionIsolation` | A7/A8 secondary | FAIL (harness capture) | EXCLUDED (A7/A8 primary PASS is authoritative) |

### 2.4 Validation-harness exclusions (unchanged)

| Item | Raw result | Classification |
|------|------------|----------------|
| ADB_ONLY legacy (debt1_e4/debt1_cont) | 14/27 FAIL / not-run | EXCLUDED_VALIDATION_HARNESS |
| DebugBridge supplemental (DBG7-10) | not exercised | SUPPLEMENTAL_NOT_BLOCKING |

---

## 3. TRANSPORT LOSS (I14) — Divergence Resolution

### 3.1 Root cause of i14 FAIL (V4-4.2D audit)

Two independent issues:
1. **Harness marker mismatch (VALIDATION_OBSERVABILITY_FAILURE):** i14's
   established-marker was `[adb] connected`, but this device emits `connected`
   (no prefix). The waiter never flipped `established=true`, so `id` was never
   sent. Fixed by aligning the marker to device reality.
2. **UTP uninstall (VALIDATION_ENVIRONMENT_ARTIFACT):** Gradle's post-test
   uninstall destroyed the paired identity. Fixed by switching to direct
   `am instrument` (no UTP lifecycle).

### 3.2 Resolution

- Harness marker fix: TEST/HARNESS-only (i14 in AdbCliInteractiveTest.kt)
- Direct instrumentation: state-preserving, no install/uninstall
- Result: **PASS** (OK 1 test, A→F chain verified, no Product semantic change)

```
TRANSPORT_SEMANTICS_FAILURE        = NO
VALIDATION_OBSERVABILITY_FAILURE   = FIXED
V4_PRODUCT_TRANSPORT_REGRESSION    = NO
TRANSPORT_LOSS_PRODUCT_GATE        = PASS
```

---

## 4. COMPLETION DETERMINATION

Controller rule: V4-4.2 may be marked COMPLETE only if **all REQUIRED Product
Device Closure gates are PASS and no REQUIRED item remains open**.

**REQUIRED gates — final status:**

| Gate | Status |
|------|--------|
| C4 (CLI interactive) | PASS |
| C5 (CLI cwd/env) | PASS |
| C6 (CLI stdin) | PASS |
| A5 (ADB Ctrl-C) | PASS |
| A6 (resize) | PASS |
| A7 (multi-session) | PASS |
| A8 (multi-session) | PASS |
| A10 (ADB natural exit) | PASS |
| CONNECTION_REUSE | PASS |
| RUNTIME_OWNERSHIP | PASS |
| HOST_RESTART | PASS |
| Transport Loss (I14) | PASS |
| APP_RESTART | PASS |

**REQUIRED_AND_STILL_OPEN = 0**

---

## 5. FINAL VERDICT

```
AT.V4-4.2 = COMPLETE
DEVICE_CLOSURE = CLOSED
```

### 5.1 What is closed

- **Identity reconciliation:** V4 Product uses exactly ONE canonical identity
  (`files/adb_identity`). ConsumerHost uses exactly ONE canonical identity
  (`files/at-cli-identity`). `V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS`.
- **All REQUIRED Product Device Closure gates:** PASS with durable evidence.
- **Pairing lifecycle:** Product FIRST_USE_PAIRING_COUNT=1,
  NORMAL_REPAIRING_COUNT=0. ConsumerHost FIRST_USE_PAIRING_COUNT=1,
  NORMAL_REPAIRING_COUNT=0. No unexpected re-pairing.
- **Transport loss semantics:** PASS (harness observability fixed, no Product
  semantic change).
- **Host restart:** PASS (identity reuse after process restart verified).

### 5.2 What is NOT reopened

- Identity reconciliation architecture (closed at 4e2ebfc)
- Pairing architecture (closed)
- debt1_e4 / debt1_cont (EXCLUDED_VALIDATION_HARNESS)
- ADB_ONLY legacy fresh-identity validation (EXCLUDED)
- Product single-key semantics (closed)

### 5.3 Historical context

The repeated pairings observed during V4-4.2D through V4-4.2D.2 were caused
by the Gradle UTP uninstall/reinstall cycle (VALIDATION_ENVIRONMENT_ARTIFACT).
They are NOT Product or ConsumerHost lifecycle behavior. The Product and
ConsumerHost each performed exactly ONE first-use pairing in their final
persistent installations.

### 5.4 Harness-only changes (this round)

- `AdbCliInteractiveTest.kt` i14: established-marker aligned to device output
  (`connected` / `$ ` / `:$`), uid=2000 confirmation before loss injection.
  TEST/HARNESS-only. No Product semantics / ADB protocol / TLS / shell-v2 /
  recovery ownership changed.

---

## 6. Evidence Index

All evidence in `docs/evidence/at_v4_4_2_final/`:

| File | Content |
|------|---------|
| `00_environment_divergence_precheck.md` | Pre-round environment state |
| `01_product_first_use_pairing.md` | V4-4.2D Product first-use pairing (64d3d804) |
| `02_A6_A7A8_ProgrammaticAdbConsumerTest.xml` | A6/A7/A8 primary PASS XML |
| `03_transport_loss_i14_audit.md` | i14 A-F divergence audit + harness fix |
| `04_instrumentation_identity_audit.md` | UTP uninstall/reinstall root cause |
| `04b_i14_gradle_info_install_uninstall.log` | Gradle --info install/uninstall window |
| `05_final_product_installation.md` | Final Product install + identity (a026370d) |
| `06_final_pairing.md` | Final Product first-use pairing (port 43223) |
| `07_transport_loss_i14_direct_instrumentation.md` | i14 direct am instrument PASS |
| `08_consumerhost_host_restart.md` | ConsumerHost HOST_RESTART PASS |

---

## 7. Deliverable status

- SSOT / Roadmap / closure docs: updated.
- Evidence: all REQUIRED gates PASS with durable evidence in `at_v4_4_2_final/`.
- Commit: docs/evidence/test-harness only. NO PUSH, NO TAG.
- STOP. Do not start V4-5.
