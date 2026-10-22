AT.V4-6 — Local Parity (P1/P2)
Date: 2026-10-22

## V3 Oracle
V3 V3.3 §1 (one-shot o1-o5) + §3 (persistent p1-p8) — all PASS on V3 device.

## V4 Validators
- Product: CommonConsumerPathTest#C1-C4 (Local one-shot + persistent)
- ConformanceHost: ConformanceTest#a_localOneShot, #b_localPersistent

## V4 Results
- CommonConsumerPathTest: OK (3 tests), 11.2s
- ConformanceTest: OK (8 tests), 13.6s

## Parity
- Local one-shot: COMPLETED/stdout=uid=/exit 0 — matches V3 o1-o5 semantics
- Local persistent: session open/cwd continuity /data/local/tmp/close — matches V3 p1-p8
- SEMANTIC_DELTA = NONE

```
LOCAL_ONE_SHOT_PARITY = PASS
LOCAL_PERSISTENT_PARITY = PASS
```
