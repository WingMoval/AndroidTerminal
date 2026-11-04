# AT.V042-RELEASED-ASSET-SMOKE-1

**Date:** 2026-11-04
**Device:** HONOR APLMUT4312005935, Android 15
**Branch:** feature/local-tcp-v0.4.2 @ b9d188b (read-only)

---

## Purpose

Verify that the APK actually downloadable from the GitHub Release is bit-for-bit
identical to the accepted v0.4.2 RC, and that it installs and runs correctly.

This is a **distribution smoke**, not a re-acceptance.

---

## Download

```
DOWNLOAD_SOURCE = https://github.com/WingMoval/AndroidTerminal/releases/download/v0.4.2/AndroidTerminal-v0.4.2-arm64-v8a.apk
DOWNLOADED_APK = D:\temp\AndroidTerminal-v0.4.2-release-smoke\AndroidTerminal-v0.4.2-arm64-v8a.apk
DOWNLOAD_SUCCESS = YES
```

## Artifact Identity

```
SIZE = 4465528 (expected 4465528) ✓
SHA256 = 90CD5E5B37186DC971126698A895F86A4F2992A38952C64B38B8919EBE019B2C ✓
RELEASE_BINARY_EQUALS_ACCEPTED_RC = YES (bit-for-bit identical)
```

## APK Metadata (aapt2)

```
package: com.androidterminal.app
versionCode: 4
versionName: 0.4.2
native-code: arm64-v8a (plus armeabi-v7a, x86, x86_64)
minSdk: 26, targetSdk: 34
APK_METADATA_MATCH = YES
```

## Signing (apksigner)

```
V2 scheme: verified
Signer DN: CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
Signer cert SHA-256: 7bdcba7781e6fecfe078eb9741e7bfeb380f0a7aa64530007991250b07835cde
V2_SIGNER_MATCH = YES
```

## Installation

```
MODE = overwrite (adb install -r)
RESULT = Success
Post-install: versionCode=4, versionName=0.4.2 ✓
OVERWRITE_INSTALL = PASS
```

## Launch Smoke

```
APP_LAUNCH = PASS (PID 15617)
IMMEDIATE_CRASH = NO
```

## Local Smoke

```
+ → Local
uid=10443(u0_a443), context=u:r:untrusted_app:s0
/dev/pts/0
State: ACTIVE
RELEASE_LOCAL_SMOKE = PASS
```

## ADB Smoke

```
+ → ADB (user-created session)
uid=2000(shell), context=u:r:shell:s0
/dev/pts/2
State: ACTIVE
38800: LISTENING (Local TCP)
RELEASE_ADB_SMOKE = PASS
```

## Constraints Met

```
FRESH_INSTALL_VERIFIED = NO (overwrite install only)
FULL_RELEASE_ACCEPTANCE_RERUN = NO
REPO_CHANGED = NO
PRODUCT_CODE_CHANGED = NO
COMMIT = NO
TAG = NO
PUSH = NO
GITHUB_RELEASE_CHANGED = NO
```

## Verdict

```
RELEASE_DISTRIBUTION_SMOKE = PASS
RELEASE_IDENTITY_INTACT = YES
```

The GitHub Release APK is binary-identical to the accepted RC. All prior
functional acceptance evidence remains valid for this published artifact.
