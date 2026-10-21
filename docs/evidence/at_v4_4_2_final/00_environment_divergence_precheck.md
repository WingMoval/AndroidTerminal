AT.V4-4.2D — ENVIRONMENT DIVERGENCE PRECHECK (BLOCKER)
Date: 2026-10-22
Baseline: HEAD = 4e2ebfc, worktree CLEAN (confirmed)

## Finding

The device state has diverged from the 4e2ebfc baseline. The V4 Product App
identity verified at 4e2ebfc is NO LONGER on the device.

### Evidence

1. Device packages (2026-10-22 00:28):
   - com.androidterminal        (OLD, versionName 0.1.0, lastUpdate 2026-10-08, uid u0_a291)
       identity at files/adbkey.pub  (NOT files/adb_identity)
       fingerprint = ca415b87...  (a different/legacy key)
   - com.androidterminal.app    (V4 :app, freshly reinstalled 2026-10-22 00:28, uid u0_a403)
   - com.androidterminal.atsh / .testhost / .adbonlyhost

2. The V4 Product App (com.androidterminal.app) was NOT present on the device at
   00:28. It was reinstalled this session (fresh app data, new uid u0_a403).

3. After launch + ensureReady, the V4 app generated a FRESH canonical identity:
   - files/adb_identity/adbkey.pub
   - fingerprint = 64d3d80475d4ae07c4080050e73d695301a1aeaec7289884c3d22dd04aff891d
   - This is DIFFERENT from the 4e2ebfc-verified fingerprint cc7a9884...

4. connectAuto result: ADB: CONNECT_FAILED
   (the fresh key 64d3d804... is not in the device's trusted-key list)

### Why this blocks AT.V4-4.2D

All four REQUIRED_AND_STILL_OPEN items require a READY ADB connection on the
V4 Product App / ConsumerHost:
  - HOST_RESTART   (ConsumerHost restart/reuse -> needs ADB READY)
  - A6 RESIZE      (ProgrammaticAdbConsumerTest::resizeIsolation -> needs ADB READY)
  - A7-A8          (ProgrammaticAdbConsumerTest::multiSessionIsolation -> needs ADB READY)
  - TRANSPORT_LOSS (i14 -> needs an established remote session first)

Reaching ADB READY for the freshly-installed V4 app requires pairing its fresh
key (64d3d804...) to the device. That is exactly what AT.V4-4.2D forbids:
  - "不得请求新的 pairing code"
  - "不得创建新的 adb identity"
  - "不得重新开启 pairing architecture"

### Not a Product regression

This is an ENVIRONMENT divergence (the trusted identity from the 4e2ebfc
session is gone because the V4 app was uninstalled / the device was reset
between sessions), NOT a Product identity/architecture regression. The frozen
facts remain valid:
  AT_CANONICAL_ADB_IDENTITY_COUNT = 1
  V4_PRODUCT_IDENTITY_REGRESSION  = NO
  V3_TO_V4_SINGLE_IDENTITY_PARITY = PASS

The single-identity SEMANTICS are unchanged; only the specific trusted key
instance on this device is gone.

### STOP — awaiting Controller decision

Options for the Controller:
  A. Authorize ONE first-use pairing for the freshly-installed V4 app
     (64d3d804...) to restore the READY precondition, then run the 4 items.
     (This is a re-establishment of the SAME single-identity lifecycle, not a
      second identity — but it DOES require a pairing code, which the task
      currently forbids.)
  B. Restore the prior device state / reinstall the exact 4e2ebfc V4 build
     with its preserved app data (fingerprint cc7a9884...), then run the 4
     items with NO pairing.
  C. Defer AT.V4-4.2D until the device returns to a state where the verified
     single identity (cc7a9884...) is present and trusted.

No Product code was modified. No new identity was intentionally created beyond
the V4 app's own canonical loadOrCreate on fresh install. No re-pair performed.
