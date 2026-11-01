# AndroidTerminal v0.4.1 — Release Provenance

**Date:** 2026-11-01
**Status:** RELEASED

## Release Commit

```
RELEASE_COMMIT = cbc2098
TAG            = v0.4.1 (annotated, object 004af1e)
BRANCH         = master
```

## Version

```
versionName = 0.4.1
versionCode = 3
package     = com.androidterminal.app
```

## Signing

```
ACTIVE_SIGNING_LINEAGE = V2 (permanent)
JKS                    = androidterminal-release-v2.jks (PKCS12, RSA-4096, PrivateKeyEntry, 25y)
alias                  = androidterminal
cert SHA256            = 7BDCBA7781E6FECFE078EB9741E7BFEB380F0A7AA64530007991250B07835CDE
cert SHA1              = EA2A43C6...
DN                     = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
```

V1 lineage ABANDONED_FOR_FUTURE_RELEASES (credential unavailable). V1 JKS preserved read-only.

## Release APK

```
PATH    = app/build/outputs/apk/release/app-release.apk
SIZE    = 4,416,416 bytes
ABI     = arm64-v8a
SHA256  = 92DF7499A155D4DC3556119C12A9A4C5680B8B444D46D3611969689A6E044D6A
scheme  = v2 (APK Signature Scheme v2)
```

## Test Totals

```
TEST_TOTAL = 143
TEST_PASS  = 143
TEST_FAIL  = 0
```

Covers: pairing, trust-loss, readiness, ADB runtime, architecture, consumer, CLI.

## Architecture Gates

```
ARCHITECTURE_GATE = PASS
AdbRuntime = readiness/lifecycle owner (unchanged)
PairActivity/execution = readiness triggers (unchanged)
UI = projection/action surface (unchanged)
```

## Lint

```
LINT = PRE_EXISTING_NON_BLOCKING
LINT_NEW_FINDINGS = 0
(1 pre-existing CoarseFineLocation error in AndroidManifest.xml, unchanged since a92eab5)
```

## Device Gates (HONOR ALI-AN00, Android 15, APLMUT4312005935)

```
FRESH_INSTALL                    = PASS
FRESH_INSTALL_NEEDS_PAIRING      = PASS
PAIR_RESULT                      = PASS
PAIRING_REGENERATED_KEY          = NO
PAIR_POST_SUCCESS_CONNECT_ATTEMPT = PASS
POST_PAIR_STATE                  = READY (happy path of Model B)
POST_PAIR_ON_DEMAND_CONNECT      = PASS
POST_PAIR_SESSION_IO             = PASS (uid=2000(shell) context=u:r:shell:s0)
MANUAL_RETRY_REQUIRED_AFTER_PAIR = NO

APP_RESTART_IDENTITY_PERSISTENCE = PASS
APP_RESTART_PAIRING_PROMPT       = NO
POST_RELAUNCH_STATE              = DISCONNECTED (lazy, legal)
APP_RESTART_ON_DEMAND_CONNECT    = PASS
APP_RESTART_SESSION_IO           = PASS (uid=2000(shell))
MANUAL_RETRY_USED_AFTER_RESTART  = NO

DEVICE_REBOOT_IDENTITY_PERSISTENCE = PASS
DEVICE_REBOOT_PAIRING_PROMPT       = NO
POST_REBOOT_STATE                  = DISCONNECTED (lazy, legal)
DEVICE_REBOOT_ON_DEMAND_CONNECT    = PASS
DEVICE_REBOOT_SESSION_IO           = PASS (uid=2000(shell))
MANUAL_RETRY_USED_AFTER_REBOOT     = NO
```

## Pair Semantics (Model B)

```
PAIR_POST_SUCCESS_SEMANTICS = BOUNDED_AUTO_CONNECT_ATTEMPT
PAIR_SUCCESS_GUARANTEES_CONNECT_ATTEMPT = YES
PAIR_SUCCESS_GUARANTEES_READY           = NO
PAIR_SUCCESS_GUARANTEES_EVENTUAL_READY  = NO
PAIR_SUCCESS_REQUIRES_USER_ACTION_TO_START_CONNECT = NO
```

See: `docs/evidence/AT.PAIR-POST-SUCCESS-RUNTIME-SEMANTICS-1.md`

## Trust-Loss

```
TRUST_LOSS_REAL_DEVICE_RETEST = NOT_REQUIRED (existing evidence + regression tests)
TRUST_LOSS_TESTS              = PASS (TrustLossRecoveryTest 11/11 in 143/143)
```

## Scope

```
LOCAL_TCP_INCLUDED = NO (no tcpip:38800 / requestTcpip productization)
WIRELESS_BEHAVIOR_CHANGED = NO (only identity lifecycle + on-demand readiness)
```

## GitHub Release

```
URL          = https://github.com/WingMoval/AndroidTerminal/releases/tag/v0.4.1
ASSET        = AndroidTerminal-v0.4.1-arm64-v8a.apk
REMOTE_SHA256 = 92DF7499A155D4DC3556119C12A9A4C5680B8B444D46D3611969689A6E044D6A
VERIFIED     = PASS (matches local release APK)
```

## Agent Session IO (DebugBridge)

Terminal session IO verified via DebugBridge (JSONL over loopback TCP, debug-only):
- `{"type":"list"}` → session enumeration
- `{"type":"attach","sessionId":"1"}` → attach
- `{"type":"write","sessionId":1,"text":"id\n"}` → write command
- `{"type":"output","sessionId":1,"data":"<base64>"}` → read output (base64 decode)

Output confirmed: `uid=2000(shell) context=u:r:shell:s0`

## Signing Reset Disclosure

v0.4.1 starts the permanent V2 signing lineage. Existing v0.4.0 installs (V1 cert)
require a one-time uninstall/reinstall. This is disclosed in the GitHub Release notes.

## Prohibited (confirmed absent)

```
JKS_COMMITTED    = NO
PASSWORD_EXPOSED = NO
LOCAL_TCP        = NO
```
