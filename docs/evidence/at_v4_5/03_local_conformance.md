AT.V4-5 — Conformance Device Evidence
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
Device IP: 192.168.137.44
Conformance host: com.androidterminal.conformance (UID u0_a4xx)
Identity profile: conformance-identity (self-generated, independent)

## Installation
Manual `adb install -r` (NOT Gradle UTP — preserves identity/permission/trust):
- at-consumer-conformance-host-debug.apk
- at-consumer-conformance-host-debug-androidTest.apk

## Conformance host first-use pairing (authorized, ONE)
The conformance host owns an INDEPENDENT app-private identity
(files/conformance-identity), distinct from Product (adb_identity) and
ConsumerHost (at-cli-identity). Its first device trust was established via the
canonical public composition facade (adbControl.pairDiscovered) — NOT by copying
the Product key, NOT via PC adb.

```
CONFORMANCE_HOST_FIRST_USE_PAIRING_COUNT = 1
CONFORMANCE_HOST_NORMAL_REPAIRING_COUNT  = 0
Pairing: ConformancePairingTest#firstUsePairing (paircode 884860, port 37535)
  -> OK (1 test)
Post-pair identity fingerprint:
  1e7ee331878ae547319b31991d1e3fa932b4b1edf4b6d18c08145db4b3cade73
```
This pairing does NOT count toward PRODUCT_FIRST_USE_PAIRING_COUNT.

## Conformance gates (direct am instrument, all 8 PASS)

Command:
  am instrument -w -e class 'com.androidterminal.conformance.ConformanceTest#<A..H>' \
    com.androidterminal.conformance.test/androidx.test.runner.AndroidJUnitRunner

Result: OK (8 tests), Time: 13.549

| Gate | Test | Result |
|------|------|--------|
| A | a_localOneShot | PASS (id -> uid=, exitCode 0) |
| B | b_localPersistent | PASS (cwd continuity /data/local/tmp) |
| C | c_adbOneShot | PASS (id -> uid=2000(shell)) |
| D | d_adbPersistent | PASS (session + id -> uid=2000) |
| E | e_readiness | PASS (READY via public facade, no AdbRuntime) |
| F | f_bootstrap | PASS (bootstrapState READY + completeFirstUseBootstrap) |
| G | g_identity | PASS (hasIdentity + own conformance-identity dir + adbkey.pub) |
| H | h_cliShellVisibleAdb | PASS (Local session + `adb shell id` -> uid=2000) |

## Frozen conformance results
```
LOCAL_ONE_SHOT_CONFORMANCE    = PASS
LOCAL_PERSISTENT_CONFORMANCE  = PASS
ADB_ONE_SHOT_CONFORMANCE      = PASS
ADB_PERSISTENT_CONFORMANCE    = PASS
READINESS_CONFORMANCE         = PASS
BOOTSTRAP_CONFORMANCE         = PASS
IDENTITY_CONFORMANCE          = PASS
CLI_CONFORMANCE               = PASS
DEVICE_CONFORMANCE            = PASS
```

## Identity independence (Gate G)
- Conformance host identity dir: files/conformance-identity (own, self-generated)
- fingerprint 1e7ee331... is NEITHER Product (a026370d...) NOR ConsumerHost (df365556...)
- No Product/ConsumerHost/PC key copied.
- ONE consumer -> ONE canonical identity.

## No Product / ConsumerHost impact
- Product (com.androidterminal.app) NOT uninstalled/cleared/re-paired.
- ConsumerHost (com.androidterminal.atclihost) untouched.
- Conformance pairing is a separate consumer identity (validation/reference).
