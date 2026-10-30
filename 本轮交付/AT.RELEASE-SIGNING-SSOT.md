# AndroidTerminal — Release Signing SSOT

> **Canonical signing identity for AndroidTerminal releases.**
> This document contains **no passwords** and **no private key material** — only
> fingerprints, paths (outside git), and the recovery procedure.

---

## 1. Active Signing Lineage

```text
ACTIVE_SIGNING_LINEAGE  = V2
ACTIVE_FROM_VERSION     = v0.4.1
```

From **v0.4.1 onward**, every release APK MUST be signed with the V2 identity
below. A release whose APK signer cert ≠ `EXPECTED_RELEASE_CERT_SHA256` is
**RELEASE_BLOCKED**.

```text
EXPECTED_RELEASE_CERT_SHA256 = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
```

---

## 2. V2 Identity

```text
JKS_FILENAME   = androidterminal-release-v2.jks
JKS_PATH       = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2.jks
JKS_SHA256     = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094

ALIAS          = androidterminal
ENTRY_TYPE     = PrivateKeyEntry
KEY_ALGORITHM  = RSA
KEY_SIZE       = 4096
SIGNATURE      = SHA384withRSA
VALIDITY       = 2026-10-30 .. 2051-10-30 (25 years)

CERT_SHA256    = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
CERT_SHA1      = C3FF3A093DA0D4DD4B957791B10C8BEBEAF27986
CERT_SUBJECT   = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US

PUBLIC_CERT    = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2-cert.pem
PUBLIC_CERT_SHA256 = 955E30088F109732E8D04DF9EBC4FD4FEF5833C9FCFA52B6F438827743E01CCC
```

> `NEW_CERT_UNIQUE = YES` — distinct from the debug cert, the Local-TCP-0.4.1 RC
> cert, and the historical v0.4.0 cert (see §3).

---

## 3. Historical (V1) Lineage — ABANDONED

```text
V1_STATUS          = ABANDONED_FOR_FUTURE_RELEASES
V1_HISTORICAL_RELEASE = v0.4.0
V1_CERT_SHA256     = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93
V1_REASON          = CREDENTIAL_UNAVAILABLE
                     (store/key password not recoverable from this machine: no env,
                      no history/script/config source, and the creating session is
                      not retained in the current OpenCode DB)
V1_PRIVATE_KEY_LOST = NOT_PROVEN
V1_JKS             = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v1.jks
V1_JKS_SHA256      = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
V1_JKS_STATUS      = PRESERVED_READ_ONLY
```

```text
DO NOT DELETE the V1 JKS.
DO NOT REUSE the V1 key unless its credential is legitimately recovered.
```

---

## 4. Prohibited Keys (never use for a release)

```text
56586d91...  = Android Debug keystore (debug builds only)
9c9898b1...  = Local-TCP 0.4.1 RC temporary signing identity (release-keystore.jks)
9c252f31...  = historical V1 lineage (credential unavailable)
```

---

## 5. Storage & Backup

```text
PRIVATE_KEY_STORAGE  = outside git (D:\codebank\AndCode\signing\AndroidTerminal\)
                      (*.jks / *.keystore are gitignored)
PASSWORD_STORAGE     = local plaintext credential file, user-owned:
                       C:\Users\Wing\.androidterminal-secrets\release-v2.env
                       (NOT in git, NOT in repo, NOT in docs/本轮交付)
OFFLINE_BACKUP       = LOCAL second copy created this round:
                       D:\codebank\AndCode\signing\AndroidTerminal\backup\
                       (androidterminal-release-v2.jks + -cert.pem, SHA256-verified
                        equal to primary). Controller will additionally copy
                       JKS + public cert + credential backup to an independent
                       offline medium.
```

> **Controller action (offline):** copy the primary JKS, the public cert, and
> `release-v2.env` to offline storage. The local `backup/` copy is a convenience
> second copy on the same machine, **not** a substitute for offline backup.

---

## 6. How to Recover the Signing Environment

```text
1. Obtain androidterminal-release-v2.jks (primary or offline backup).
2. Retrieve the credential file release-v2.env (or the password from your records).
3. Set the four environment variables (do NOT hardcode in Gradle or commit):
     AT_RELEASE_STORE_FILE    = <path to androidterminal-release-v2.jks>
     AT_RELEASE_STORE_PASSWORD = <store password>
     AT_RELEASE_KEY_ALIAS     = androidterminal
     AT_RELEASE_KEY_PASSWORD   = <key password>
4. Verify before signing (read-only):
     keytool -list -v -keystore <jks> -storepass <storepass>
   Confirm the cert SHA-256 == EXPECTED_RELEASE_CERT_SHA256 (B7CCBD15...).
5. Build:  .\gradlew.bat :app:assembleRelease
6. Verify the APK signer:
     apksigner verify --print-certs app-release.apk
   The signer cert SHA-256 MUST equal EXPECTED_RELEASE_CERT_SHA256, else RELEASE_BLOCKED.
```

> This environment is currently restored on this machine (user-scoped
> `AT_RELEASE_*` set). On a new machine, repeat steps 1–4.

---

## 7. Gradle Integration

`app/build.gradle.kts` reads signing from `System.getenv("AT_RELEASE_*")` and
falls back to the debug keystore only when `AT_RELEASE_STORE_FILE` is unset.
**No** JKS path or password is hardcoded in Gradle. No production Gradle logic
was changed for this lineage.

---

## 8. Decision Record

```text
OLD_SIGNING_LINEAGE_STATUS = ABANDONED_FOR_FUTURE_RELEASES
REASON                     = CREDENTIAL_UNAVAILABLE
PRIVATE_KEY_LOST           = NOT_PROVEN
NEW_SIGNING_LINEAGE_STATUS = ACTIVE_FROM_V0.4.1
```

This is a release-lineage decision only. Historical v0.4.0 signing evidence is
retained (not deleted).
