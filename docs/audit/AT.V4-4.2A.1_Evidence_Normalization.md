# AT.V4-4.2A.1: Validation Audit Evidence Normalization

> **Date**: 2026-10-21
> **Type**: DOCS / EVIDENCE ONLY / AUDIT NORMALIZATION
> **Verdict**: PASS

---

## 1. Baseline

- START_HEAD: `4220736` (AT.V4-4.2A)
- WORKTREE: CLEAN
- V3_MODE: READ_ONLY
- NO production/test/gradle source modified

## 2. Why Normalization Was Required

AT.V4-4.2A reported approximate counts (~200 methods, ~25 classes) and
used aggregate rows in the CSV (e.g., "all", "g1-g4"). This round:
1. Re-enumerated all @Test methods from source (exact)
2. Rewrote Test Harness Matrix to one-method-per-row
3. Rewrote Gate Coverage Matrix with verifiable validator references
4. Mechanically recounted all classifications from the final CSV
5. Validated all CSVs with strict parsing
6. Normalized SSOT/Roadmap to reflect current phase

## 3. Counting Rules

- **TEST METHOD**: A `fun` annotated with `@Test` in a JUnit4/AndroidJUnit class
- **NOT counted**: @Before, @After, helper functions, private methods, data providers
- **UNIQUE ID**: `:module::full.package.ClassName::methodName`
- **CLASS**: Top-level class containing the @Test method (enclosing class by line position)
- **PARAMETERIZED**: None in this codebase (all plain @Test)

## 4. Test Discovery

Source: `git tracked tree` at HEAD 4220736 (V4) and 75d5832 (V3 baseline).
Method: regex `@Test\s*\n\s*fun\s+(\w+)\s*\(` on each .kt file.
Class detection: line-position based enclosing class.
Package: from `package` declaration in source file.

## 5. Exact Inventory Totals

| Metric | Value |
|---|---|
| EXACT_TEST_FILE_TOTAL | 31 |
| EXACT_TEST_CLASS_TOTAL | 26 |
| EXACT_TEST_METHOD_TOTAL | **251** |
| V3_TEST_METHOD_TOTAL | 246 |
| MIGRATED | 246 |
| V4_NEW | 5 |
| V3_MISSING | 0 |

## 6. Classification Rules (per spec)

- `primary_classification` = the test method's OWN readiness/debt nature
- Module compile failures do NOT change primary_classification
  (recorded in `compile_status` + `secondary_notes`)
- Environment preconditions (APK not installed, identity not created)
  do NOT change primary_classification (recorded in `device_precondition`)
- Only the method's inherent nature determines classification

## 7. Mechanical Recount

Recounted from final CSV using strict CSV parsing:

| Classification | Count |
|---|---|
| READY_UNCHANGED | 87 |
| READY_WITH_PRECONDITION | 134 |
| V4_TEST_HARNESS_REGRESSION | 7 |
| V4_API_RELOCATION_ADAPTATION_DEBT | 2 |
| PREEXISTING_V3_TEST_DEBT | 15 |
| DESTRUCTIVE_NOT_SAFE | 8 |
| UNKNOWN | 0 |
| **SUM** | **251** |

SUM = EXACT_TEST_METHOD_TOTAL = 251. PASS.

## 8. V3/V4 Method Parity

- V3: 246 methods
- V4: 251 methods
- Migrated (same unique_test_id): 246
- V4-new (at-composition-android ATAndroidTest): 5
- V3-missing: 0
- RENAMED_OR_RELOCATED: 0

V3_TEST_FILE_PARITY = PASS
V3_TEST_METHOD_PARITY = PASS
V3_TEST_EXECUTABLE_PARITY = FAIL (B2 compile + B3 compile + B1 runtime)

## 9. CSV Parser Validation

All 3 CSVs parsed with strict character-level CSV parser:

| CSV | Rows | Bad Rows | Parse Errors |
|---|---|---|---|
| Test_Harness_Matrix | 251 | 0 | 0 |
| Device_Gate_Coverage_Matrix | 36 | 0 | 0 |
| Blocker_Matrix | 8 | 0 | 0 |

CSV_PARSE_ERROR_COUNT = 0
CSV_BAD_ROW_COUNT = 0

## 10. CSV Column Validation

| CSV | Header Cols | All Rows Match |
|---|---|---|
| Test_Harness_Matrix | 32 | YES |
| Device_Gate_Coverage_Matrix | 13 | YES |
| Blocker_Matrix | 16 | YES |

## 11. Encoding Validation

All CSVs written as UTF-8 without BOM.
No replacement characters (U+FFFD) detected.
ENCODING_ERROR_COUNT = 0

## 12. Gate Total Validation

DEVICE_GATE_TOTAL = 36 (programmatically counted from gate matrix)
- L1-L7: 7
- A1-A10: 10
- C1-C6: 6
- H1-H7: 7
- APP_RESTART: 1
- HOST_RESTART: 1
- CONNECTION_REUSE: 1
- RUNTIME_OWNERSHIP: 1
- SHELLLESS: 1
- ADB_ONLY: 1
Total: 7+10+6+7+1+1+1+1+1+1 = 36

OLD report said "34 gates" — this was incorrect (missing HOST_RESTART
and ADB_ONLY as separate gates, or miscounting). Corrected to 36.

## 13. Validator Reference Integrity

Every PRIMARY_VALIDATOR and SECONDARY_VALIDATOR in the gate matrix
was checked against the test matrix unique_test_id set:

BROKEN_VALIDATOR_REFERENCE_COUNT = 0

Manual procedures (MANUAL:*) are defined in the audit doc.

## 14. Coverage Recount

COVERED_GATE_COUNT = 36
UNCOVERED_GATE_COUNT = 0
COVERAGE_GAP_COUNT = 0

## 15. Safe/Unsafe Recount

Method-level (not file-level):
- SAFE_TEST_COUNT = 243 (destructive = NO)
- UNSAFE_TEST_COUNT = 8 (destructive = YES / DESTRUCTIVE_NOT_SAFE)

## 16. Blocker Recount

BLOCKER_TOTAL = 8
P0 = 0, P1 = 3 (B1, B2, B4), P2 = 3 (B3, B5, B6), P3 = 2 (B7, B8)
SUM = 8 = BLOCKER_TOTAL
ORPHAN_BLOCKER_COUNT = 0 (all blockers reference test methods or gates)

## 17. SSOT/Roadmap Normalization

SSOT Current Phase updated to reflect:
- V4-4.2A = COMPLETE
- V4-4.2A.1 = COMPLETE
- V4-4.2 = OPEN / BLOCKED_BY_VALIDATION_READINESS
- V4-4.2B = OPEN / WAITING_CONTROLLER_AUTHORIZATION

Roadmap: V4-4.2A.1 phase added between V4-4.2A and V4-4.2B.

## 18. Findings Changed vs Unchanged

### Unchanged (confirmed):
- B1: V4_TEST_HARNESS_REGRESSION (first bad: 84936f8) — 7 methods
- B2: V4_API_RELOCATION_ADAPTATION_DEBT (first bad: 09f2ac9) — 2 methods
- B3: PREEXISTING_V3_TEST_DEBT (first bad: pre-75d5832) — 15 methods
- B4: FIRST_USE_BOOTSTRAP_REQUIRED (ConsumerHost)
- P0_PRODUCTION_REGRESSION = 0
- CONNECTION_REUSE_OBSERVABILITY = SUFFICIENT
- RUNTIME_OWNERSHIP_OBSERVABILITY = SUFFICIENT

### Changed (count corrections only, no semantic change):
- TEST_METHOD_TOTAL: ~200 → 251
- TEST_CLASS_TOTAL: ~25 → 26
- DEVICE_GATE_TOTAL: 34 → 36
- READY_WITH_PRECONDITION: ~60 → 134
- ENVIRONMENT_PRECONDITION: 3 → 0 (moved to device_precondition field)
- FIRST_USE_BOOTSTRAP_REQUIRED: 4 → 0 (moved to secondary_notes)
- SAFE_TEST_COUNT: ~190 → 243
- UNSAFE_TEST_COUNT: ~10 → 8

### New:
- NO new blockers discovered
- NO P0 production regression candidates

## 19. Final Verdict

**VERDICT = PASS**

All hard PASS criteria met:
- EXACT_TEST_METHOD_TOTAL = MATRIX_DATA_ROW_COUNT = 251
- SUM_PRIMARY_CLASSIFICATIONS = 251
- UNKNOWN_COUNT = 0
- CSV_PARSE_ERROR_COUNT = 0
- CSV_BAD_ROW_COUNT = 0
- INVALID_CLASSIFICATION_COUNT = 0
- DUPLICATE_UNIQUE_TEST_ID_COUNT = 0
- BROKEN_VALIDATOR_REFERENCE_COUNT = 0
- UNCOVERED_GATE_COUNT = 0
- ORPHAN_BLOCKER_COUNT = 0
- ENCODING_ERROR_COUNT = 0
- ALL_COUNTS_EXACT = YES
- SSOT_CURRENT_PHASE_CORRECT = YES
- ROADMAP_PHASE_ORDER_CORRECT = YES
- PRODUCTION_CHANGED = NO
- TEST_SOURCE_CHANGED = NO
- GRADLE_CHANGED = NO
- V3_WRITES = 0

## 20. Handoff to V4-4.2B

V4-4.2B scope (confirmed, unchanged):
1. B1: Add `AppRuntime.initialize(ctx)` to AppDebugInputTest @Before (1 line)
2. B2: Fix FQN at AdbTransportLossAuditTest.kt:77 (1 line)
3. B3: Restore CJK UTF-8 strings in ShelllessRealDeviceTest.kt (3 lines)

Total: 5 lines of test-only changes.
NO production code changes.
After fix: all 5 modules × androidTest should compile PASS.

NEXT_OPEN_AUTHORIZED = NO (awaiting Controller authorization for V4-4.2B).


---

## 21. Encoding Finalization Note (AT.V4-4.2A.1F)

- Formal artifact set scanned (7 files)
- Mojibake found and restored: 23 occurrences in AT.V4-4.2A_Device_Validation_Readiness_Audit.md
- U+FFFD: 0
- CSV parse: 0 errors
- All counts unchanged
