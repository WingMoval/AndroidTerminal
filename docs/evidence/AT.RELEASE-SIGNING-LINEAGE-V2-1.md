# AT.RELEASE-SIGNING-LINEAGE-V2-1 — Evidence

**Step:** AT.RELEASE-SIGNING-LINEAGE-V2-1
**Date:** 2026-10-30
**Nature:** Establish a new, permanent, recoverable release signing lineage (V2) from v0.4.1
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master, base f896340)

> Controller decision: **ABANDON_OLD_SIGNING_LINEAGE** (V1 credential unavailable).
> This round creates V2; it does **not** release v0.4.1.

---

## 1. Starting State

```text
REPO     = D:\codebank\AndCode\AndroidTerminal-V040-FIX
BRANCH   = master
HEAD     = f896340 (expected f896340 → MATCH)
WORKTREE = CLEAN
```

---

## 2. Historical Lineage Preservation

```text
OLD_CERT_SHA256      = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93
OLD_JKS_PRESERVED    = YES (not deleted / overwritten / modified / re-keyed)
OLD_JKS_SHA256_BEFORE = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
OLD_JKS_SHA256_AFTER  = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
PRIVATE_KEY_LOST     = NOT_PROVEN
```

---

## 3. New V2 Signing Identity

Created with `keytool -genkeypair` (non-interactive; store/key passwords supplied to the
keytool process only — never written to repo, docs, 本轮交付, or echoed in this report).

```text
NEW_JKS_PATH      = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2.jks
NEW_JKS_SHA256    = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
NEW_JKS_SIZE      = 3927 bytes
NEW_ALIAS         = androidterminal
NEW_ENTRY_TYPE    = PrivateKeyEntry
NEW_KEY_ALGORITHM = RSA
NEW_KEY_SIZE      = 4096
NEW_SIGNATURE     = SHA384withRSA
NEW_VALIDITY      = 2026-10-30 .. 2051-10-30 (25 years)

NEW_CERT_SHA256    = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
NEW_CERT_SHA1      = C3FF3A093DA0D4DD4B957791B10C8BEBEAF27986
NEW_CERT_SUBJECT   = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
```

> Public-key SHA-256: not separately hashed this round (no openssl in toolchain); the
> certificate fingerprint (NEW_CERT_SHA256) is the authoritative release identity and is
> what future release gates compare against. The SubjectKeyIdentifier (from keytool) is
> `2DE250B0D98F4FBF5338ABAE6C90EBEE108DB7F1`.

### NEW_CERT_UNIQUE

```text
vs Android Debug (56586d91...)        -> different
vs Local-TCP 0.4.1 RC (9c9898b1...)   -> different
vs historical v0.4.0 (9c252f31...)    -> different
NEW_CERT_UNIQUE = YES
```

---

## 4. Public Certificate Export

```text
PUBLIC_CERT_PATH      = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2-cert.pem
PUBLIC_CERT_EXPORTED  = YES (public cert only; NO private key exported)
PUBLIC_CERT_SIZE      = 1996 bytes
PUBLIC_CERT_SHA256    = 955E30088F109732E8D04DF9EBC4FD4FEF5833C9FCFA52B6F438827743E01CCC
```

Cross-check: the exported public cert's certificate fingerprint (read back via keytool)
= `B7CCBD15...` = NEW_CERT_SHA256 (MATCH).

---

## 5. Credential Persistence

Per Controller decision: local plaintext credential file (user-owned, outside git),
store + key passwords (key password is 6 chars due to the JKS key-password limit;
store password is 28 chars).

```text
PASSWORD_MANAGER_SAVED = NO  (Controller chose local plaintext file instead of a password manager)
CREDENTIAL_FILE        = C:\Users\Wing\.androidterminal-secrets\release-v2.env
CREDENTIAL_PERSISTED   = YES
PASSWORD_IN_GIT        = NO
PASSWORD_IN_EVIDENCE   = NO
```

> The credential file is under the user profile (user-owned by default). An explicit
> user-only NTFS ACL could not be applied (requires SeSecurityPrivilege / admin), but the
> location is outside any repo and is not shared.

---

## 6. Independent-Process Recovery Test (P0 gate)

A **separate** PowerShell process (fresh, not the one that created the JKS) loaded the
credentials from `release-v2.env` and:

```text
INDEPENDENT_PROCESS_RECOVERY = PASS
  - keystore opened with the recovered store password
  - alias "androidterminal" resolved
  - ENTRY_TYPE = PrivateKeyEntry (private key accessible)
  - cert SHA256 = B7CCBD15... = NEW_CERT_SHA256 (MATCH)
PRIVATE_KEY_ACCESSIBLE_AFTER_RECOVERY = YES
RECOVERED_CERT_MATCH = YES
```

This proves the credential is not only in the creating process's memory.

---

## 7. Environment Restore

User-scoped environment variables set (values NOT echoed):

```text
AT_RELEASE_STORE_FILE     = SET
AT_RELEASE_STORE_PASSWORD = SET
AT_RELEASE_KEY_ALIAS      = SET
AT_RELEASE_KEY_PASSWORD   = SET
```

---

## 8. Backup Gate (local second copy)

```text
BACKUP_JKS_PATH  = D:\codebank\AndCode\signing\AndroidTerminal\backup\androidterminal-release-v2.jks
PRIMARY_JKS_SHA256 = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
BACKUP_JKS_SHA256  = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
BACKUP_HASH_MATCH (JKS)  = YES

BACKUP_CERT_PATH   = D:\codebank\AndCode\signing\AndroidTerminal\backup\androidterminal-release-v2-cert.pem
CERT_SHA256_FROM_PRIMARY = 955E30088F109732E8D04DF9EBC4FD4FEF5833C9FCFA52B6F438827743E01CCC
CERT_SHA256_FROM_BACKUP  = 955E30088F109732E8D04DF9EBC4FD4FEF5833C9FCFA52B6F438827743E01CCC
BACKUP_HASH_MATCH (CERT) = YES
```

> Local same-machine second copy only. Controller will add an **offline** copy of
> JKS + public cert + credential backup (see SSOT §5).

---

## 9. Signing Smoke (throwaway, NOT a release)

```text
SIGNING_SMOKE_BUILD = PASS  (:app:assembleRelease with AT_RELEASE_* resolved to the new JKS)
SIGNING_SMOKE_APK   = app\build\outputs\apk\release\app-release.apk (4,416,412 b)
SIGNING_SMOKE_APK_CERT_SHA256 = b7ccbd1528e6d6b6b6d94ff5777336a2c6ed0aafdffe608b3dc363d24fe0d8cc
SIGNING_SMOKE_CERT_MATCH = YES  (= NEW_CERT_SHA256)
```

> The smoke APK is a throwaway validation artifact. It is NOT a release and NOT published.

---

## 10. Security Audit

```text
JKS_IN_GIT          = NO  (signing/ is outside the repo; *.jks gitignored)
PASSWORD_IN_GIT     = NO
PASSWORD_IN_EVIDENCE = NO
PRIVATE_KEY_EXPORTED = NO (only the public cert was exported)
OLD_JKS_MUTATED     = NO
PASSWORD_IN_COMMAND_HISTORY = NOT_OBSERVED (passwords passed to keytool process only;
                              not written to the shell transcript / env file beyond release-v2.env)
PUBLIC_CERT_EXPORTED = YES
```

---

## 11. Recovery Gate

```text
CREDENTIAL_RECOVERY_PATH = VERIFIED
  (independent process recovered the private key + cert from the persisted credential file;
   SSOT §6 documents the repeatable 6-step recovery procedure)
```

---

## 12. Verdict

All PASS criteria met:

```text
new JKS created + PrivateKeyEntry + RSA-4096        = PASS
public cert exported + cert SHA256 recorded          = PASS
credential persisted (recoverable) + JKS backed up   = PASS
independent-process recovery                          = PASS
new cert unique                                       = YES
no secret leakage                                     = PASS
old JKS preserved                                     = YES
Gradle signing smoke                                  = PASS

SIGNING_LINEAGE_V2_STATUS = READY
ACTIVE_FROM_VERSION = v0.4.1
```

---

## 13. Final Report

```text
STEP = AT.RELEASE-SIGNING-LINEAGE-V2-1
VERDICT = PASS
MASTER_BASE_HEAD = f896340
MASTER_FINAL_HEAD = <docs-only commit, SELF>
WORKTREE = CLEAN

OLD_SIGNING_LINEAGE_STATUS = ABANDONED_FOR_FUTURE_RELEASES
OLD_CERT_SHA256 = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93
OLD_JKS_PRESERVED = YES
OLD_JKS_SHA256_BEFORE = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
OLD_JKS_SHA256_AFTER  = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
PRIVATE_KEY_LOST = NOT_PROVEN

NEW_JKS_PATH = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2.jks
NEW_JKS_SHA256 = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
NEW_ALIAS = androidterminal
NEW_ENTRY_TYPE = PrivateKeyEntry
NEW_KEY_ALGORITHM = RSA
NEW_KEY_SIZE = 4096
NEW_CERT_SHA256 = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
NEW_CERT_SHA1 = C3FF3A093DA0D4DD4B957791B10C8BEBEAF27986
NEW_PUBLIC_KEY_SHA256 = (not separately hashed; cert SHA256 is the release identity)
NEW_CERT_SUBJECT = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
NEW_CERT_UNIQUE = YES

PUBLIC_CERT_PATH = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2-cert.pem
PUBLIC_CERT_EXPORTED = YES
PASSWORD_MANAGER_SAVED = NO (local plaintext credential file per Controller decision)
OFFLINE_JKS_BACKUP = NO (local second copy done; offline copy is Controller follow-up)
BACKUP_JKS_SHA256 = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
BACKUP_HASH_MATCH = YES

AT_RELEASE_STORE_FILE = SET
AT_RELEASE_STORE_PASSWORD = SET
AT_RELEASE_KEY_ALIAS = SET
AT_RELEASE_KEY_PASSWORD = SET

INDEPENDENT_PROCESS_RECOVERY = PASS
PRIVATE_KEY_ACCESSIBLE_AFTER_RECOVERY = YES
RECOVERED_CERT_SHA256 = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
RECOVERED_CERT_MATCH = YES

SIGNING_SMOKE_BUILD = PASS
SIGNING_SMOKE_APK_CERT_SHA256 = b7ccbd1528e6d6b6b6d94ff5777336a2c6ed0aafdffe608b3dc363d24fe0d8cc
SIGNING_SMOKE_CERT_MATCH = YES

JKS_IN_GIT = NO
PASSWORD_IN_GIT = NO
PASSWORD_IN_EVIDENCE = NO
PRIVATE_KEY_EXPORTED = NO
OLD_JKS_MUTATED = NO
VERSION_CHANGED = NO
PRODUCT_CODE_CHANGED = NO
TAG_CREATED = NO
PUSHED = NO
RELEASED = NO

SIGNING_LINEAGE_V2_STATUS = READY
ACTIVE_FROM_VERSION = v0.4.1
NEXT = RESUME AT.0.4.1-RELEASE
STOP = YES
```
