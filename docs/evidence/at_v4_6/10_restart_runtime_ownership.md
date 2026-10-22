AT.V4-6 — Restart / Runtime Ownership (P17/P18)
Date: 2026-10-22

## P17: Runtime Ownership
V3 Oracle: V4-4.2 + V4-5 architecture enforcement
V4 Validator: Product AdbCliLifecycleTest + V4-5 architecture gate
V4 Result:
- AdbCliLifecycleTest: OK (11 tests), 139.6s
- Architecture gate: 13/13 PASS
```
RUNTIME_OWNERSHIP_PARITY = PASS
RECOVERY_OWNERSHIP_PARITY = PASS
RX_OWNER_COUNT = 1
AdbRuntime = UNIQUE lifecycle owner
AdbRecoveryCoordinator = UNIQUE recovery owner
```

## P18: Restart
V3 Oracle: V4-4.2 + V4-4.3
V4 Validator: Product AutoWdBootstrapTest#e + ConsumerHost#identityReuseAfterRestart
V4 Result:
- e_restartPersistence: PASS (3 tests)
- identityReuseAfterRestart: PASS (1 test), 36.5s
```
APP_RESTART_PARITY = PASS
HOST_RESTART_PARITY = PASS
same identity / no re-pair / reconnect / auto-WD still available
```
