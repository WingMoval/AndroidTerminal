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
EXPECTED_RELEASE_CERT_SHA256 = 7BDCBA7781E6FECFE078EB9741E7BFEB380F0A7AA64530007991250B07835CDE
```

---

## 2. V2 Identity

```text
JKS_FILENAME   = androidterminal-release-v2.jks
JKS_PATH       = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2.jks
JKS_SHA256     = 1B3566E297421B1E2641873BC0C9F3156D6B5713C9BFF93871E2DAEFA69802AF
JKS_STORETYPE  = PKCS12

ALIAS          = androidterminal
ENTRY_TYPE     = PrivateKeyEntry
KEY_ALGORITHM  = RSA
KEY_SIZE       = 4096
SIGNATURE      = SHA384withRSA
VALIDITY       = 2026-10-30 .. 2051-10-30 (25 years)

CERT_SHA256    = 7BDCBA7781E6FECFE078EB9741E7BFEB380F0A7AA64530007991250B07835CDE
CERT_SHA1      = EA2A43C65D72B8B3549C9FEE12172A8486983D0A
CERT_SUBJECT   = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US

PUBLIC_CERT    = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v2-cert.pem
PUBLIC_CERT_SHA256 = 22188021000C1797E732BB3F6A2A9CA242E72115B589B2A39F7F1C2AEC77B4D3
```

> `NEW_CERT_UNIQUE = YES` — distinct from the debug cert, the Local-TCP-0.4.1 RC
> cert, the historical v0.4.0 cert, **and** the prior (superseded) V2 cert
> `B7CCBD15...` (see §3 / §4).
>
> PKCS12 requires `store password == key password`; a single 32-char password is
> used for both.

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

### 3a. Prior (superseded) V2 JKS — PRESERVED, NOT the active key

During this round an earlier V2 JKS (JKS_SHA256 `5674068D...`, cert SHA256
`B7CCBD15...`) had been created, but its password was **not** durably saved to a
recoverable location (the `.androidterminal-secrets` file was not in place). To
make the lineage genuinely recoverable, the V2 JKS was **regenerated** with a
fresh strong password that **is** durably saved (Desktop file, §5). The prior
unrecoverable JKS was preserved, not deleted:

```text
PRIOR_V2_JKS        = D:\codebank\AndCode\signing\AndroidTerminal\
                      androidterminal-release-v2.previous-20261030-184056.jks
PRIOR_V2_JKS_SHA256 = 5674068DA741BB77A2CB3D61BB9E0B5EBE39A4B4742B86B5B7C1197567F3D094
PRIOR_V2_CERT_SHA256 = B7CCBD1528E6D6B6B6D94FF5777336A2C6ED0AAFDFFE608B3DC363D24FE0D8CC
PRIOR_V2_STATUS     = PRESERVED_READ_ONLY (superseded; password not durably saved)
```

```text
DO NOT DELETE the prior V2 JKS.
DO NOT REUSE the prior V2 key (its credential is not durably recoverable).
The ACTIVE V2 key is the one in §2 (cert 7BDCBA77...).
```

---

## 4. Prohibited Keys (never use for a release)

```text
56586d91...  = Android Debug keystore (debug builds only)
9c9898b1...  = Local-TCP 0.4.1 RC temporary signing identity (release-keystore.jks)
9c252f31...  = historical V1 lineage (credential unavailable)
b7ccbd15...  = prior (superseded) V2 key (credential not durably saved)
```

---

## 5. Storage & Backup

```text
PRIVATE_KEY_STORAGE  = outside git (D:\codebank\AndCode\signing\AndroidTerminal\)
                      (*.jks / *.keystore are gitignored)
PASSWORD_STORAGE     = local plaintext credential file (Controller-chosen durable
                       record), on the Windows Desktop, user-owned:
                        C:\Users\Wing\Desktop\AndroidTerminal-release-signing-password.txt
                      (NOT in git, NOT in repo, NOT in docs/本轮交付)
OFFLINE_BACKUP       = LOCAL second copy created this round:
                        D:\codebank\AndCode\signing\AndroidTerminal\backup\
                        (androidterminal-release-v2.jks + -cert.pem, SHA256-verified
                         equal to primary). Controller will additionally copy
                        JKS + public cert + the Desktop password file to an
                        independent offline medium.
```

> **Controller action (offline):** copy the primary JKS, the public cert, **and**
> the Desktop password file to offline storage. The local `backup/` copy is a
> convenience second copy on the same machine, **not** a substitute for offline
> backup. The Desktop password file is the single source of truth for the
> credential and MUST be backed up offline.

---

## 6. How to Recover the Signing Environment

```text
1. Obtain androidterminal-release-v2.jks (primary or offline backup).
2. Retrieve the Desktop password file
   AndroidTerminal-release-signing-password.txt (it holds STORE_PASSWORD, which
   for this PKCS12 store also IS the key password).
3. Set the four environment variables (do NOT hardcode in Gradle or commit):
     AT_RELEASE_STORE_FILE    = <path to androidterminal-release-v2.jks>
     AT_RELEASE_STORE_PASSWORD = <STORE_PASSWORD from the Desktop file>
     AT_RELEASE_KEY_ALIAS     = androidterminal
     AT_RELEASE_KEY_PASSWORD   = <STORE_PASSWORD from the Desktop file>
4. Verify before signing (read-only):
     keytool -list -v -keystore <jks> -storepass <storepass>
   Confirm the cert SHA-256 == EXPECTED_RELEASE_CERT_SHA256 (7BDCBA77...).
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