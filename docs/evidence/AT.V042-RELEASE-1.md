# AT.V042-RELEASE-1

**Date:** 2026-11-03

---

## Release Identity

```
VERSION_NAME = 0.4.2
VERSION_CODE = 4
PACKAGE = com.androidterminal.app
```

## Commit Chain

```
PREFLIGHT_COMMIT = 04f9c30
RELEASE_COMMIT = 925e7ac
TAG_TARGET = 925e7ac
```

## Branch

```
BRANCH = feature/local-tcp-v0.4.2
REMOTE = github (https://github.com/WingMoval/AndroidTerminal.git)
REMOTE_BRANCH_HEAD = 925e7ac
BRANCH_PUSHED = YES
```

## Tag

```
TAG = v0.4.2
TAG_TYPE = annotated
TAG_CREATED = YES
TAG_PUSHED = YES
REMOTE_TAG_OBJECT = fd8fb7c1b472f9bd4e04f207d520c030a15e5d8d
REMOTE_TAG_TARGET_COMMIT = 925e7ac
```

## GitHub Release

```
GITHUB_RELEASE_CREATED = YES
GITHUB_RELEASE_PUBLISHED = YES
URL = https://github.com/WingMoval/AndroidTerminal/releases/tag/v0.4.2
ASSET = AndroidTerminal-v0.4.2-arm64-v8a.apk
ASSET_SIZE = 4465528
ASSET_SHA256 = 90cd5e5b37186dc971126698a895f86a4f2992a38952c64b38b8919ebe019b2c
DOWNLOADED_ASSET_SHA256 = 90CD5E5B37186DC971126698A895F86A4F2992A38952C64B38B8919EBE019B2C
RELEASE_ASSET_VERIFIED = YES
```

## Product Source Guard

```
PRODUCT_CODE_CHANGED_SINCE_ACCEPTED_RC = NO
```

Last 2 commits (bbce5a6, 04f9c30) are docs/evidence only.
All product code changes were in prior feature rounds (UX-POLISH, STALE-GUARD, version bump),
all tested and accepted in v0.4.2 Final Acceptance.

## Release Artifact

```
RELEASE_ARTIFACT_SOURCE = ACCEPTED_RC
APK_NAME = AndroidTerminal-v0.4.2-arm64-v8a.apk
APK_SIZE = 4465528 bytes
APK_SHA256 = 90CD5E5B37186DC971126698A895F86A4F2992A38952C64B38B8919EBE019B2C
APK_SHA_MATCHES_ACCEPTED_RC = YES
```

## Signing

```
APK_SIGNED = YES
SIGNER_CERT_SHA256 = 7bdcba7781e6fecfe078eb9741e7bfeb380f0a7aa64530007991250b07835cde
V2_SIGNER_MATCH = YES
SIGNER_DN = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
```

## Known Finding

```
SEVERITY = MINOR
CATEGORY = COSMETIC_UI_STATE_REFRESH
DESCRIPTION = After same-key re-pair, status banner may remain stale until next
  ADB readiness trigger. Backend functionality is already restored.
BLOCKING = NO
```

## Tag Convention

```
CONVENTION = annotated tag (matching v0.4.1)
TAG_NAME = v0.4.2
```
