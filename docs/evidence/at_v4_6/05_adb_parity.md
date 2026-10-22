AT.V4-6 — ADB Parity (P3/P4)
Date: 2026-10-22

## V3 Oracle
V3 V3.3 §1 (one-shot o1-o5 ADB) + §3 (persistent p1-p8 ADB) — all PASS.

## V4 Validators
- Product: CommonConsumerPathTest#C5-C10 (ADB one-shot + persistent)
- Product: ProgrammaticAdbConsumerTest#persistentCwd (ADB persistent cwd)
- ConformanceHost: ConformanceTest#c_adbOneShot, #d_adbPersistent

## V4 Results
- CommonConsumerPathTest: OK (3 tests)
- ProgrammaticAdbConsumerTest: OK (7 tests)
- ConformanceTest: OK (8 tests)

## Parity
- ADB one-shot: uid=2000(shell)/COMPLETED — matches V3
- ADB persistent: session open/uid=2000/close — matches V3
- REMOTE_PRINCIPAL = uid=2000(shell)
- SEMANTIC_DELTA = NONE

```
ADB_ONE_SHOT_PARITY = PASS
ADB_PERSISTENT_PARITY = PASS
REMOTE_PRINCIPAL = uid=2000(shell)
```
