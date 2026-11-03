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
RELEASE_COMMIT = (this commit)
TAG_TARGET = RELEASE_COMMIT
```

## Branch

```
BRANCH = feature/local-tcp-v0.4.2
REMOTE = github (https://github.com/WingMoval/AndroidTerminal.git)
REMOTE_BRANCH_HEAD = 04f9c30 (before release commit)
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
