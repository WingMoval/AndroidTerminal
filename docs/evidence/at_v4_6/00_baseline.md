AT.V4-6 — Baseline
Date: 2026-10-22

## Preflight
```
BASELINE_HEAD = 532f816 (AT.V4-5: close consumer conformance and architecture enforcement)
WORKTREE = CLEAN
AT.V4-CONSUMER-FREEZE-V1 = NOT_PRESENT
```

## Frozen premises (NOT reopened)
```
AT.V4-4.2 = COMPLETE / DEVICE_CLOSURE = CLOSED
AT.V4-4.3 = COMPLETE / POST_BOOTSTRAP_AUTO_WD = PASS
AT.V4-5 = COMPLETE / CONSUMER_CONFORMANCE = CLOSED / ARCHITECTURE_ENFORCEMENT = CLOSED
CONSUMER_CONTRACT_GAP = NO
AT_ENGINE_API_PUBLIC_LEAK_COUNT = 0
FORBIDDEN_DIRECT_DEPENDENCY_COUNT = 0
FORBIDDEN_CONSUMER_IMPORT_COUNT = 0
MODULE_DAG_CYCLE_COUNT = 0
AT_ENGINE_PUBLIC_API_CHANGE = NO
```

## V3 Parity Oracle
V3 frozen source @ 75d5832 + V3 V3.3 closure evidence (docs/evidence/at_v3_3/EVIDENCE.md)
serve as the parity oracle. V3 verified semantics for each capability are cited in
02_v3_v4_parity_matrix.csv.

V4 must be re-verified on the current final version (532f816) via direct am
instrument (no UTP — preserves persistent identity).
