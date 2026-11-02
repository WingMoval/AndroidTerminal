# AT.0.4.2-BRANCH-REALIGN — Evidence

**Step:** AT.0.4.2-BRANCH-REALIGN
**Date:** 2026-11-02
**Nature:** Branch realignment — migrate validated Local TCP productization onto the released v0.4.1 baseline.
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042` (branch `feature/local-tcp-v0.4.2`)

---

## 1. Starting State

```
BRANCH = master
HEAD   = 536fbb7
WORKTREE = CLEAN (AndroidTerminal-V040-FIX)
```

## 2. Branch / Worktree Inventory

```
master                    536fbb7  (D:\codebank\AndCode\AndroidTerminal-V040-FIX)
feature/local-tcp-v0.4.1  f88501a  (D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-041)  [PRESERVED]
research/local-tcp-1      ec07fdf  (D:\codebank\AndCode\AndroidTerminal-V4)                 [UNTOUCHED]
feature/local-tcp-v0.4.2  <new>    (D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042)   [NEW]
```

## 3. New Branch Base Gate

```
V042_BRANCH = feature/local-tcp-v0.4.2
V042_BASE_HEAD = 536fbb7
V042_MERGE_BASE_WITH_MASTER = 536fbb7
BASE_MATCHES_MASTER = YES
```

## 4. Commit Classification (25 feature-only commits)

| Commit | Class | Action | Reason |
|---|---|---|---|
| 166c22b | A (Local TCP) | MIGRATE | Transport selection (AdbTransportMode, AdbRuntime) |
| 63d4daa | A (Local TCP) | MIGRATE | Backend routing (AdbBackend) |
| aaa2199 | B (Local TCP test) | MIGRATE | Routing/wiring/sanity tests + docs |
| eb6d9b6 | A (Local TCP) | MIGRATE | Native core (plain_tcp_connection, JNI) |
| ec237a3 | A (Local TCP) | MIGRATE | PRECHECK-A/B (requestTcpip, auto-promotion) |
| 503ba7b | E (MIXED) | SELECTIVE | Release hardening — extracted Local TCP hunks, excluded version/manifest |
| e97bfc8 | E (MIXED) | SELECTIVE | Normalize adbd before local tcp — extracted Local TCP hunks |
| df95601 | F (Obsolete) | SKIP | 本轮交付 refresh (release docs) |
| c0bcf0b | D (Wireless) | SKIP | Manifest permission audit (already in master) |
| a2040a2 | F (Obsolete) | SKIP | Release-signed candidate (obsolete) |
| b71a388 | F (Obsolete) | SKIP | 本轮交付 for release prep |
| d69abbc | D (Wireless) | SKIP | Release artifact audit (already in master) |
| 684aab5 | D (Wireless) | SKIP | Reboot recovery fix (already in master) |
| b7db214 | D (Wireless) | SKIP | Architecture audit docs (already in master) |
| 53fccbd | D (Wireless) | SKIP | mDNS lifecycle audit (already in master) |
| 2a27e9d | F (Obsolete) | SKIP | Direct tcpip promotion audit (superseded) |
| cc16853 | D (Wireless) | SKIP | mDNS endpoint lifecycle (already in master) |
| 69293e8 | D (Wireless) | SKIP | V1 TLS credential recovery (already in master) |
| ff5de07 | D (Wireless) | SKIP | V1 TLS fix (already in master) |
| 533ded8 | D (Wireless) | SKIP | V1 vs 0.4.0 differential (already in master) |
| c92f7e2 | D (Wireless) | SKIP | Cert structure diff (already in master) |
| a6f7a25 | D (Wireless) | SKIP | Key-registration-flow diag (already in master) |
| 9f986b0 | D (Wireless) | SKIP | Identity lifecycle fix (already in master) |
| a44f673 | D (Wireless) | SKIP | Pairing metadata tests (already in master) |
| f88501a | D (Wireless) | SKIP | Trust-loss recovery (already in master) |

## 5. Replay Strategy

**Method:** Semantic patch reconstruction (NOT cherry-pick by hash).

1. Generated `git diff a3e0e74..feature/local-tcp-v0.4.1` filtered to Local TCP paths only (20 files, 2470 lines).
2. Applied with `git apply -3` (3-way merge) to the new v0.4.2 worktree.
3. Resolved 1 conflict in `adb_connection.cpp` (comment-only: master's trust-loss comment wording vs feature's — kept master's, then restored file to master since it has NO Local TCP changes).
4. Fixed 2 patch-introduced duplicates:
   - `adb_connection.h`: removed duplicate `last_connect_trust_rejected()` (already in master from AT.TRUST-LOSS-RECOVERY-1).
   - `adb_engine_jni.cpp`: removed duplicate `lastConnectTrustRejected` JNI function (already in master).

## 6. Migrated Local TCP Content

### New files (7)
| File | Purpose |
|---|---|
| `adb-engine/.../cpp/adb/connection/plain_tcp_connection.cpp` | Native plain TCP connection (293 lines) |
| `adb-engine/.../cpp/adb/connection/plain_tcp_connection.h` | Header |
| `adb-engine/.../cpp/adb/connection/adb_packet_transport.h` | Packet transport abstraction (62 lines) |
| `adb-engine/.../java/com/adb/engine/AdbTransportMode.kt` | WIRELESS / LOCAL enum |
| `test-host/.../AdbBackendRoutingTest.kt` | Backend transport routing test |
| `test-host/.../LocalTcpCoreSanityTest.kt` | Local TCP core sanity test |
| `test-host/.../LocalTcpProductWiringTest.kt` | Local TCP product wiring test |

### Modified files (10)
| File | Change | WHY_REQUIRED_FOR_LOCAL_TCP |
|---|---|---|
| `CMakeLists.txt` | +3 | Link plain_tcp_connection.cpp |
| `adb_connection.h` | +26/-1 | Add plain TCP connection support to connection class |
| `adb_stream_hub.cpp` | +34/-1 | Route streams to Local or Wireless connection |
| `adb_stream_hub.h` | +23/-1 | Local TCP stream registration |
| `adb_engine_jni.cpp` | +479/-4 | Local TCP JNI bindings (connectLocalhost, openInteractiveLocalhost, etc.) |
| `AdbRuntime.kt` | +364/-8 | Transport selection (Gate B/C), local-first probe, auto-promotion (PRECHECK-A) |
| `AdbRecovery.kt` | +8 | Local intent type (no Wireless recovery for Local loss — Gate F) |
| `AdbRecoveryCoordinator.kt` | +8 | Handle Local intent (skip Wireless rediscovery) |
| `AdbBackend.kt` | +30/-6 | Route execution by active transport (Gate E) |
| `AdbRuntimeLifecycleTest.kt` | +133 | Local TCP transport selection tests |

### Unchanged (Wireless-protected)
```
MainActivity.kt           — UNCHANGED (lazy readiness semantics preserved)
PairActivity.kt           — UNCHANGED (Model B pair semantics preserved)
AdbConsumerBootstrap.kt   — UNCHANGED
AdbPairingMetadata.kt     — UNCHANGED
AdbEngine.kt              — UNCHANGED (requestTcpip/requestUsb already in master)
adb_connection.cpp        — UNCHANGED (trust-loss recovery already in master)
TrustLossRecoveryTest.kt  — UNCHANGED
AdbPairingMetadataTest.kt — UNCHANGED
app/build.gradle.kts      — UNCHANGED (version 0.4.1/3 preserved)
AndroidManifest.xml       — UNCHANGED
```

## 7. Skipped Wireless Commits

All 18 D/F-class commits were skipped. Their semantic content is already in master
via the independent v0.4.1 release path. No cherry-pick was performed.

## 8. Shared-File Audit

All shared-file changes are `WHY_REQUIRED_FOR_LOCAL_TCP`:

- **AdbRuntime.kt**: The 8 "removed" lines are refactored to use `activeTransportOnline()`
  (covers both Wireless and Local). All other changes are additive Local TCP transport
  selection logic (Gate B/C, local-first probe, auto-promotion).
- **AdbBackend.kt**: Added `activeTransport == LOCAL` checks to route execution through
  the Local TCP path (Gate E). No Wireless routing changed.
- **AdbRecovery.kt / AdbRecoveryCoordinator.kt**: Added `Local` intent type so Local
  transport loss does NOT trigger Wireless rediscovery (Gate F). No existing Wireless
  recovery path changed.
- **adb_connection.h**: Added plain TCP connection support. The trust-loss member
  was already in master; the patch-introduced duplicate was removed.
- **adb_stream_hub.cpp/.h**: Added Local TCP stream registration alongside existing
  Wireless streams. No Wireless stream path changed.
- **adb_engine_jni.cpp**: Added Local TCP JNI functions. The duplicate
  `lastConnectTrustRejected` (already in master) was removed.

**WIRELESS_ONLY_DIFF = NONE**

## 9. Test Results

```
TEST_TOTAL = 143
TEST_PASS  = 143
TEST_FAIL  = 0
```

Breakdown (key suites):
```
AdbPairingMetadataTest:    16 tests, 0 failures  (Wireless)
TrustLossRecoveryTest:     11 tests, 0 failures  (Wireless)
ProductionArchitectureGateTest: 7 tests, 0 failures  (Architecture)
ProgrammaticAdbConsumerTest:  2 tests, 0 failures  (Consumer)
AdbCliArgsTest:            27 tests, 0 failures  (CLI)
+ all other ADB engine / AT engine / composition tests
```

Device-only tests (androidTest, NOT run on JVM):
```
AdbBackendRoutingTest.kt         (Local TCP — compiled, ready for device)
LocalTcpCoreSanityTest.kt        (Local TCP — compiled, ready for device)
LocalTcpProductWiringTest.kt     (Local TCP — compiled, ready for device)
AdbRuntimeLifecycleTest.kt       (Local TCP transport — compiled, ready for device)
```

## 10. Build Results

```
BUILD_ADB_ENGINE = PASS
BUILD_APP_DEBUG  = PASS
BUILD_APP_RELEASE = NOT_RUN (signing config not modified)
```

## 11. Architecture Gate

```
ARCHITECTURE_GATE = PASS (7/7 ProductionArchitectureGateTest)
```

Verified:
- AdbRuntime = unique ADB lifecycle owner (transport selection added within existing owner)
- AdbRecoveryCoordinator = unique recovery owner (Local intent handled, no new owner)
- Backend = adaptation only (routes by active transport, does not decide)
- No second readiness/connection/recovery/RX owner introduced

## 12. Wireless Semantic Equivalence

```
V041_WIRELESS_REGRESSION = PASS
```

- Pairing metadata: UNCHANGED
- Trust-loss recovery: UNCHANGED (test + implementation)
- Lazy readiness (MainActivity): UNCHANGED
- Model B pair semantics (PairActivity): UNCHANGED
- AdbConsumerBootstrap: UNCHANGED
- All 143 JVM tests pass (including all Wireless regression tests)

## 13. Local TCP Regression

```
EXISTING_LOCAL_TCP_TESTS = COMPILED_NOT_RUN
LOCAL_TCP_DEVICE_TESTS_EXECUTED = NO
```

The Local TCP androidTest files compile successfully. They have NOT been
executed on device (AT.LOCAL-TCP-DIRECT-PROMOTION-1 will execute them).
Compile success ≠ device execution pass.

## 14. Old vs New Semantic Comparison

```
LOCAL_TCP_CAPABILITY_PRESERVED = YES
WIRELESS_PARALLEL_FIXES_DROPPED = YES
OBSOLETE_V041_VERSIONING_DROPPED = YES
```

Old feature (f88501a) had 3028 insertions across 32 production files (including
Wireless fixes + versioning + docs). New v0.4.2 has 1780 insertions across 17 files
(Local TCP only). The difference (1248 lines, 15 files) is the Wireless fixes and
obsolete release work that are already in master.

## 15. Canonical Runtime Semantics Preserved

```
APP_LAUNCH_AUTO_CONNECT = NO          (MainActivity UNCHANGED)
PAIR_SUCCESS = BOUNDED_ATTEMPT       (PairActivity UNCHANGED)
PAIR_SUCCESS_GUARANTEES_READY = NO   (Model B preserved)
NORMAL_EXECUTION = LAZY ENSURE_READY (unchanged)
ADB_BUTTON = OPTIONAL TRIGGER        (unchanged)
```

## 16. Non-Goals Verified

```
DIRECT_PROMOTION_CHANGED = NO
REQUEST_USB_NORMALIZATION_CHANGED = NO
TRANSPORT_SELECTION_REDIGNED = NO
UX1_IMPLEMENTED = NO
UX2_IMPLEMENTED = NO
UX3_IMPLEMENTED = NO
VERSION_BUMP_THIS_ROUND = NO (still 0.4.1/3)
```

## 17. Final Branch State

```
BRANCH = feature/local-tcp-v0.4.2
BASE   = 536fbb7 (master HEAD)
FILES  = 17 changed (7 new, 10 modified)
LINES  = +1780 / -52
STATUS = CLEAN (all committed)
```

## 18. Next Step

```
NEXT = AT.LOCAL-TCP-DIRECT-PROMOTION-1
```

Device tests (androidTest) are compiled and ready. The next round will:
1. Execute Local TCP device tests
2. Design/verify direct promotion (Wireless READY → requestTcpip → Local)
3. Decide requestUsb normalization
4. Implement UX-1/2/3 (wording-only, from v0.4.2 backlog)

---

## Final Report

```
STEP = AT.0.4.2-BRANCH-REALIGN
VERDICT = PASS

MASTER_HEAD = 536fbb7
OLD_FEATURE_BRANCH = feature/local-tcp-v0.4.1
OLD_FEATURE_HEAD = f88501a
RESEARCH_BRANCH = research/local-tcp-1
RESEARCH_WORKTREE_UNTOUCHED = YES

NEW_BRANCH = feature/local-tcp-v0.4.2
NEW_WORKTREE = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
V042_BASE_HEAD = 536fbb7
V042_MERGE_BASE_WITH_MASTER = 536fbb7

OLD_FEATURE_BRANCH_PRESERVED = YES

COMMIT_CLASSIFICATION = 7 MIGRATE (A/B), 2 SELECTIVE (E), 18 SKIP (D/F)
LOCAL_TCP_COMMITS_REPLAYED = [166c22b, 63d4daa, aaa2199, eb6d9b6, ec237a3, 503ba7b*, e97bfc8*]
  (* = selective hunk extraction, not full commit)
LOCAL_TCP_HUNKS_RECONSTRUCTED = [503ba7b version/manifest hunks excluded, e97bfc8 docs hunks excluded]
WIRELESS_COMMITS_SKIPPED = [df95601, c0bcf0b, a2040a2, b71a388, d69abbc, 684aab5, b7db214, 53fccbd, 2a27e9d, cc16853, 69293e8, ff5de07, 533ded8, c92f7e2, a6f7a25, 9f986b0, a44f673, f88501a]
OBSOLETE_COMMITS_SKIPPED = [df95601, a2040a2, b71a388, 2a27e9d]

MIXED_COMMITS_FOUND = YES
MIXED_COMMIT_HANDLING = [503ba7b: extracted Local TCP hunks, excluded version/manifest/docs; e97bfc8: extracted Local TCP hunks, excluded docs]

LOCAL_TCP_PRODUCT_DIFF = 1780 insertions / 52 deletions across 17 files
LOCAL_TCP_TEST_DIFF = 442 lines (3 new test files + 133 lines in lifecycle test)
DOC_DIFF = 0 (no docs migrated; old docs remain in old branch)
WIRELESS_ONLY_DIFF = NONE

LOCAL_TCP_CAPABILITY_PRESERVED = YES
WIRELESS_PARALLEL_FIXES_DROPPED = YES
OBSOLETE_V041_VERSIONING_DROPPED = YES

APP_LAUNCH_LAZY_SEMANTICS_PRESERVED = YES
PAIR_MODEL_B_PRESERVED = YES
EXECUTION_TRIGGERED_READINESS_PRESERVED = YES
ADB_BUTTON_OPTIONAL_TRIGGER_PRESERVED = YES

TEST_TOTAL = 143
TEST_PASS = 143
TEST_FAIL = 0

V041_WIRELESS_REGRESSION = PASS
EXISTING_LOCAL_TCP_TESTS = COMPILED_NOT_RUN
LOCAL_TCP_DEVICE_TESTS_EXECUTED = NO
ARCHITECTURE_GATE = PASS

BUILD_ADB_ENGINE = PASS
BUILD_APP_DEBUG = PASS
BUILD_APP_RELEASE = NOT_RUN

DIRECT_PROMOTION_CHANGED = NO
REQUEST_USB_NORMALIZATION_CHANGED = NO
TRANSPORT_SELECTION_CHANGED = NO

UX1_IMPLEMENTED = NO
UX2_IMPLEMENTED = NO
UX3_IMPLEMENTED = NO
UX_BACKLOG_PRESERVED = YES

VERSION_BUMP_THIS_ROUND = NO

PRODUCT_CODE_CHANGED = YES (expected: Local TCP migration)
NEW_BEHAVIOR_INTRODUCED = NO

OLD_FEATURE_DELETED = NO
V041_TAG_CHANGED = NO
V041_RELEASE_CHANGED = NO

NEW_BRANCH_PUSHED = YES (github/feature/local-tcp-v0.4.2 @ 2a64acd)
FINAL_HEAD = <this commit>

NEXT = AT.LOCAL-TCP-DIRECT-PROMOTION-1
STOP = YES
```
