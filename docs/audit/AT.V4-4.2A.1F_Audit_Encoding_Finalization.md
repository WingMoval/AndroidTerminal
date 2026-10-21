# AT.V4-4.2A.1F: Audit Encoding Finalization

> **Date**: 2026-10-21
> **Type**: DOCS / EVIDENCE ONLY / ENCODING HYGIENE
> **Verdict**: PASS

---

## 1. Baseline

- START_HEAD: `45e831a` (AT.V4-4.2A.1)
- WORKTREE: CLEAN
- V3_MODE: READ_ONLY
- NO production/test/gradle source modified

## 2. Scope

Restore mojibake in formal audit deliverables. No re-audit of test inventory,
classification, blocker, device gate, production, or architecture.

## 3. Files Scanned (FINAL_NORMALIZED_ARTIFACT_SET)

1. `docs/audit/AT.V4-4.2A_Device_Validation_Readiness_Audit.md`
2. `docs/audit/AT.V4-4.2A.1_Evidence_Normalization.md`
3. `docs/audit/AT.V4-4.2A_Test_Harness_Matrix.csv`
4. `docs/audit/AT.V4-4.2A_Device_Gate_Coverage_Matrix.csv`
5. `docs/audit/AT.V4-4.2A_Blocker_Matrix.csv`
6. `docs/AT.V4_MASTER_SSOT.md`
7. `docs/roadmap/AT.V4_ROADMAP.md`

## 4. Detection Method

Strict UTF-8 decode + character-level scan for:
- U+FFFD (replacement character)
- U+922B / U+9225 (mojibake of → / —)
- Known mojibake tokens (锟斤拷, 娴嬭瘯, etc.)

## 5. Mojibake Findings (BEFORE)

**1 file affected**: `AT.V4-4.2A_Device_Validation_Readiness_Audit.md`

- U+922B (mojibake of →): 14 occurrences
- U+9225 (mojibake of —): 9 occurrences
- Total: 23 occurrences (each followed by a spurious `?` from encoding loss)
- U+FFFD: 0
- Other 6 files: 0 issues

## 6. Replacement Manifest Summary

All 23 replacements in one file:
- U+922B + `?` → `→` (14 times)
- U+9225 + `?` → `—` (9 times)
- Semantic change: NO for all (pure encoding restoration)

Full manifest: `docs/evidence/at_v4_4_2a_encoding_finalization/02_replacement_manifest.csv`

## 7. UTF-8 Strict Decode Result

All 7 files: UTF-8 strict decode PASS.
UTF8_STRICT_DECODE_FAIL_COUNT = 0

## 8. U+FFFD Scan

U_FFFD_COUNT = 0 (before and after)

## 9. Suspicious Token Scan

After fix: KNOWN_MOJIBAKE_OCCURRENCE_COUNT = 0
No false positives encountered.

## 10. CSV Strict Parser Result

| CSV | Rows | Cols | Bad Rows |
|---|---|---|---|
| Test_Harness_Matrix | 251 | 32 | 0 |
| Device_Gate_Coverage_Matrix | 36 | 13 | 0 |
| Blocker_Matrix | 8 | 16 | 0 |

CSV_PARSE_ERROR_COUNT = 0
CSV_BAD_ROW_COUNT = 0

## 11. CSV Structural Integrity

CSV_HASH_CHANGED = NO (no CSV files were modified)

## 12. Semantic Diff Audit

Only change: mojibake character restoration in audit MD.
No count changes, no classification changes, no blocker changes.
SEMANTIC_CHANGE_COUNT = 0

## 13. Frozen Count Verification

| Count | Value | Changed? |
|---|---|---|
| EXACT_TEST_METHOD_TOTAL | 251 | NO |
| SUM_PRIMARY_CLASSIFICATIONS | 251 | NO |
| UNKNOWN_COUNT | 0 | NO |
| DEVICE_GATE_TOTAL | 36 | NO |
| COVERED_GATE_COUNT | 36 | NO |
| UNCOVERED_GATE_COUNT | 0 | NO |
| BLOCKER_TOTAL | 8 | NO |
| P0/P1/P2/P3 | 0/3/3/2 | NO |

FROZEN_COUNT_MISMATCH_COUNT = 0

## 14. Source/Test/Gradle No-Change Proof

git diff --name-only 45e831a..HEAD:
- Only docs/ files modified
- No .kt, .java, .cpp, .h, build.gradle*, AndroidManifest.xml
PRODUCTION_CHANGED = NO
TEST_SOURCE_CHANGED = NO
GRADLE_CHANGED = NO
MANIFEST_CHANGED = NO

## 15. Final Verdict

**VERDICT = PASS**

All hard PASS criteria met.

## 16. Handoff to V4-4.2B

V4-4.2B scope (frozen, unchanged):
1. B1: Add `AppRuntime.initialize(ctx)` to AppDebugInputTest @Before (1 line, test-only)
2. B2: Fix FQN at AdbTransportLossAuditTest.kt:77 (1 line, test-only)
3. B3: Restore CJK UTF-8 strings in ShelllessRealDeviceTest.kt (3 lines, test-only)

NEXT_OPEN_AUTHORIZED = NO
