AT.V4-4.2D.1 — Instrumentation Identity Preservation Audit
Date: 2026-10-22
Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35)
Test: :app:connectedDebugAndroidTest -P...class=...AdbCliInteractiveTest#i14_transportLossTermination --info

## PRE-TEST STATE (recorded before run)
```
PACKAGE                    = com.androidterminal.app
PACKAGE_UID                = 10409 (u0_a409)
PACKAGE_PATH               = /data/app/~~YZEun5ze4jvEBFaKcsb-XQ==/com.androidterminal.app-boA_9lsd5E1eIZ5mzz3E7Q==
versionName                = 0.3.0
firstInstallTime           = 2026-10-22 01:03:20
lastUpdateTime             = 2026-10-22 01:04:49
PRODUCT_IDENTITY_DIR       = files/adb_identity  (EXISTS)
PRODUCT_IDENTITY_FINGERPRINT = a7431290281cc4e1d1ce700c19767a44b729ccf9d488b5451233a20987d31363
adbkey.pub INODE           = 2726142
adbkey.pub MTIME           = 2026-10-22 01:03
adb_identity DIR INODE     = 2651281  (MTIME 01:03:48)
```

## Gradle / adb destructive actions captured (from --info log, line refs)

```
3510: No installables found in test fixture. Nothing to install.
3511: Installing [app-debug.apk] on device APLMUT4312005935.
3512: Installing [app-debug-androidTest.apk] on device APLMUT4312005935.
3513: Start logcat streaming.
3514: Running Android Instrumentation driver.
      ... test runs (i14 FAILED) ...
      Stop logcat streaming.
3528: Uninstalling com.androidterminal.app for device APLMUT4312005935.
3529: Uninstalling com.androidterminal.app.test for device APLMUT4312005935.
```

The destructive action is the **POST-test `Uninstalling com.androidterminal.app`**
(line 3528). This is the Android Gradle Plugin / Unified Test Plugin (UTP)
connected-test **cleanup step**: after the instrumentation run it UNINSTALLS the
target app (and the androidTest package) from the device.

## POST-TEST STATE
```
PACKAGE present?            = NO  (com.androidterminal.app not in `pm list packages`)
dumpsys package             = (empty — package gone)
PRODUCT_IDENTITY_DIR_EXISTS = NO  (app data removed with the uninstall)
```

## Conclusion

```
IDENTITY_CHANGED        = YES
FIRST_DESTRUCTIVE_ACTION = Gradle UTP post-test `Uninstalling com.androidterminal.app`
                           (teardown after the instrumentation run)
ROOT_CAUSE              = UNINSTALL_REINSTALL
```

Mechanism (deterministic, not device-specific):
1. Gradle `connectedDebugAndroidTest` (Unified Test Plugin 31.5.2) runs the
   instrumented test, then in its TEARDOWN it **uninstalls the target app**
   (`Uninstalling com.androidterminal.app`). Uninstall removes ALL app data,
   including `files/adb_identity`.
2. The NEXT `connectedDebugAndroidTest` run reinstalls the app. Because the
   previous data was wiped by the uninstall, the reinstall yields a FRESH app
   identity (new `loadOrCreate` key) with a NEW fingerprint.
3. Hence the fingerprint changes run-to-run, and each fresh key is untrusted,
   so `adb shell` reports "pairing required / no ADB identity found" or
   "not connected (identity present)".

This is NOT:
  - pm clear / clearPackageData (no such action in the log)
  - Orchestrator clear (no test orchestrator configured)
  - test setup/teardown deleting adb_identity (the app's own test code does not
    delete it; the uninstall does)
  - applicationId/signing/package replacement (package + versionName unchanged)

The single destructive primitive is the **UTP post-test uninstall + next-run
reinstall** cycle.

## Why a single pairing cannot survive under the default toolchain

The default Gradle connected-test lifecycle is:
   INSTALL(app) -> RUN test -> UNINSTALL(app)
Repeating it:
   INSTALL(fresh) -> RUN -> UNINSTALL -> INSTALL(fresh) -> RUN -> UNINSTALL ...
Every INSTALL starts from NO data (previous UNINSTALL wiped it), so every run's
identity is fresh and untrusted. A one-time pairing is destroyed by the
uninstall that follows the very run it enabled.

## Minimal TEST/HARNESS-only fix (NOT to be executed now — STOP for Controller)

Goal: INSTALL_ONCE -> PAIR_ONCE -> RUN MULTIPLE INSTRUMENTATION TESTS -> SAME
IDENTITY FINGERPRINT.

The identity lives in app private data. The UTP uninstall is the destroyer. The
minimal, harness-only way to stop the destroy-and-recreate cycle WITHOUT changing
Product and WITHOUT changing the verified install model is to make the connected
test run PRESERVE app data across runs by:

  OPTION 1 (recommended, no code change to app, no new pairing):
    Disable the UTP post-test uninstall for the :app connected test so the app
    (and its files/adb_identity) persists between runs. Concretely, for the
    :app module, configure the connected androidTest to NOT uninstall the target
    package after the run. In AGP/UTP terms this means the test run must reuse an
    already-installed, already-paired app instead of install->uninstall each time.
    Practical form: run the instrumented test with an explicit "do not
    reinstall/uninstall" mode — i.e. install the app ONCE (manually, adb install
    -r), pair ONCE, then invoke the instrumentation WITHOUT letting Gradle
    re-install/uninstall (e.g. a Gradle invocation that targets an already
    installed APK and suppresses the teardown uninstall). The app data (and thus
    the paired identity fingerprint) then stays constant across multiple runs.

  OPTION 2 (harness-level, still no Product change):
    Add a test-only guard in the androidTest that, on @Before, checks whether
    files/adb_identity already exists and, if so, does NOT allow the runtime to
    rotate it; combined with running the test in a data-preserving install mode.
    (This is belt-and-suspenders; the primary fix is stopping the uninstall.)

Both options keep:
  - INSTALL_ONCE  (app installed a single time, data preserved)
  - PAIR_ONCE     (the one authorized first-use pairing; no re-pair)
  - SAME IDENTITY FINGERPRINT across multiple instrumentation runs

No option requires: Product change, re-pairing, new identity, app-data clearing,
uninstall, or touching debt1_e4 / debt1_cont.

## STOP — awaiting Controller review
No fix applied. No pairing executed. No Product change. No uninstall performed
beyond what the audited (already-completed) Gradle run did on its own.
