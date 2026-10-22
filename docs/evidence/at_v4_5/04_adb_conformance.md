AT.V4-5 — ADB Conformance Evidence (Gates C/D)
Date: 2026-10-22

## Gate C: ADB one-shot
```
c_adbOneShot = PASS
Path: Consumer -> adbControl.ensureReady (public facade) -> adbEngine.execute("id")
  -> AdbRuntime (internal) -> adbd -> uid=2000(shell)
REMOTE_PRINCIPAL = uid=2000(shell)
Consumer does NOT import AdbRuntime.
```

## Gate D: ADB persistent
```
d_adbPersistent = PASS
Path: Consumer -> adbControl.ensureReady -> adbEngine.openSession(ATSessionLaunch("sh"))
  -> ATTerminalSession -> write("id") -> uid=2000(shell) -> close
PUBLIC_CONSUMER_PATH_CAN_USE_ADB_PERSISTENT = YES
```

## ADB_ONE_SHOT_CONFORMANCE = PASS
## ADB_PERSISTENT_CONFORMANCE = PASS

## Not re-verified (frozen from V4-4.2)
pairing / identity / Auto-WD / recovery / connection reuse / transport loss /
resize / multi-session — all CLOSED by V4-4.2. This round only proves the public
consumer path can use ADB one-shot + persistent.
