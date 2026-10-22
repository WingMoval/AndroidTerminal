AT.V4-6 — Recovery / Connection Reuse (P11/P12)
Date: 2026-10-22

## P11: Connection Reuse
V3 Oracle: V3 V3.3 §6 (x2_adbCrossMode)
V4 Validator: Product AdbCliOneShotTest#o14 + AdbCliInteractiveTest#i13
V4 Result:
- o14_connectionReuse: PASS (15 tests)
- i13_connectionReuse: PASS (5 tests)
```
ADB_CONNECTION_REUSE_PARITY = PASS
```

## P12: Recovery / Transport Loss
V3 Oracle: V3 V3.3 §8 (t1 transport loss)
V4 Validator: Product AdbCliInteractiveTest#i14 + AdbCliLifecycleTest
V4 Result:
- i14_transportLossTermination: PASS (5 tests)
- AdbCliLifecycleTest: OK (11 tests), 139.6s
```
RECOVERY_PARITY = PASS
TRANSPORT_LOSS_PARITY = PASS
NO transparent command replay
ONE recovery owner
ONE Runtime owner
```
