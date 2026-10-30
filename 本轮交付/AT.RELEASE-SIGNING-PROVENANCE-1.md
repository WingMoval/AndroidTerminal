# AT.RELEASE-SIGNING-PROVENANCE-1 — Historical Release Signing Chain Audit

**Step:** AT.RELEASE-SIGNING-PROVENANCE-1
**Date:** 2026-10-30
**Nature:** READ-ONLY SIGNING PROVENANCE AUDIT (no release, no signing, no key recovery)
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master @ 74a1c47, clean)

> 本轮**只建立签名 provenance 证据**，不发布、不签名、不找/猜密码。
> PRODUCTION_CODE_CHANGED = NO / VERSION_CHANGED = NO / TAG = NO / PUSH = NO / RELEASE = NO。

---

## 1. Starting State

```text
REPO     = D:\codebank\AndCode\AndroidTerminal-V040-FIX
BRANCH   = master
HEAD     = 74a1c47 (expected 74a1c47 → MATCH)
WORKTREE = CLEAN
```

---

## 2. Historical v0.4.0 Artifact (Root of Truth)

```text
V040_APK_PATH   = D:\codebank\AndCode\AndroidTerminal-V4\dist\AndroidTerminal-v0.4.0-arm64-v8a.apk
V040_APK_SIZE   = 4416412 bytes
V040_APK_SHA256 = 3388F93B8181E7EDCA0D462B424B027EA136482468F6228F215B06BFE8DB60ED
EXPECTED_SHA    = 3388F93B8181E7EDCA0D462B424B027EA136482468F6228F215B06BFE8DB60ED
MATCH           = YES  (artifact provenance intact, not broken)
```

```text
V040_PACKAGE     = com.androidterminal.app
V040_VERSION_NAME = 0.4.0
V040_VERSION_CODE = 2
SIGNATURE_SCHEMES = v2 only (APK Signature Scheme v2)
```

---

## 3. Actual Signer Extracted Directly From APK

`apksigner verify --verbose --print-certs` (NOT guessed from docs):

```text
APK_SIGNER_SUBJECT = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US
APK_SIGNER_SHA256  = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93
APK_SIGNER_SHA1    = 4b313cfaad6339fd7f507407f3639e40e3e2b95a
APK_SIGNER_MD5     = aa46ba2a654cd0fc02c3ad3178901162
KEY_ALG            = RSA 4096
PUBLIC_KEY_SHA256  = e15bd7adf3f8d498f5ab2c268c2bed1e93a9c058a1099268ed9c81ceb2dc8dce

HISTORICAL_V040_ACTUAL_CERT_SHA256 = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93
```

---

## 4. Cert Conflict Resolution

```text
HISTORICAL_V040_ACTUAL_CERT_SHA256 = 9c252f31...   (proven directly from v0.4.0 APK)
```

| Candidate | Classification |
|---|---|
| `9c252f31...` | **HISTORICAL_V040_RELEASE_CERT** (actual signer of the v0.4.0 APK) |
| `9c9898b1...` | FEATURE_BRANCH_LOCAL_TCP_041_CERT (signed the 0.4.1 Local TCP RC; subject `L=Beijing, C=CN`) |
| `56586d91...` | **DEBUG** (CN=Android Debug; default debug keystore fallback) |

`9c9898b1` is **NOT** the v0.4.0 release cert — it belongs to the later Local TCP 0.4.1
build (`AT-v0.4.1-RELEASE-AUDIT.md` Gate C/D, cert subject `L=Beijing, C=CN` vs v0.4.0's
`L=NA, ST=NA, C=US`).

---

## 5. Candidate Keystore Inventory (read-only)

```text
CANDIDATE_KEYSTORE_COUNT = 2
```

| # | Path | SHA256 | Size | MTIME |
|---|---|---|---|---|
| 1 | `D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-041\release-keystore.jks` | `2CE7B05A86C1238372E41655F6E75A4A3E463C7DB014A20C3CA08C09FE399D86` | 2239 | 2026-10-28 |
| 2 | `D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v1.jks` | `7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303` | 4442 | 2026-10-24 |

> No keystores copied into repo. No mtime/content mutated.

---

## 6. Candidate Certification

### Candidate 1 — `release-keystore.jks`

```text
CANDIDATE_1_PATH   = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-041\release-keystore.jks
CANDIDATE_1_SHA256 = 2CE7B05A...
CANDIDATE_1_CERT_SHA256 = 9c9898b10468cdbb11fbf8f84d91c87da05866b1318816e6e0063a7a8dd00fbb
  (subject CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=Beijing, C=CN)
  (proven by AT-v0.4.1-RELEASE-AUDIT.md Gate C/D: this keystore signed the 0.4.1 Local TCP RC)
CANDIDATE_1_MATCHES_V040 = NO  (cert != 9c252f31)
```

→ **NOT_V040_RELEASE_KEY**. (Not deleted — it is the Local TCP 0.4.1 signing key.)

### Candidate 2 — `androidterminal-release-v1.jks`

```text
CANDIDATE_2_PATH   = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v1.jks
CANDIDATE_2_SHA256 = 7F7B734A...
CANDIDATE_2_CERT_SHA256 = UNREADABLE (password unavailable)
CANDIDATE_2_ENTRY_TYPE  = UNRESOLVED (cannot inspect without credential)
CANDIDATE_2_MATCHES_V040 = UNRESOLVED
INSPECTION_STATUS = LOCKED_CREDENTIAL_UNAVAILABLE
```

**Provenance signals (NOT cert proof):**
- MTIME **2026-10-24** = same day as the v0.4.0 release (`AT.APP-RELEASE-2.2`, 2026-10-24).
- File size 4442 bytes is consistent with an **RSA 4096** PrivateKeyEntry (v0.4.0 key is RSA 4096).
- Stored in the designated signing location (`D:\codebank\AndCode\signing\AndroidTerminal\`) per `AT.APP-RELEASE-1` policy ("Keystore 保存在 Git 仓库外（如 D:\codebank\AndCode\signing\）").
- No other keystore matches the v0.4.0 cert; this is the **most probable** candidate.

```text
MATCH_CONFIRMED      = NO (cert unreadable → cannot prove cert == 9c252f31)
MATCH_UNCONFIRMED_LOCKED = YES
```

> Per hard rules, **no password guessing / brute-force** was attempted.

---

## 7. Gradle Signing Configuration (variable NAMES only)

`app/build.gradle.kts` (master):

```text
RELEASE_STORE_PATH_SOURCE    = System.getenv("AT_RELEASE_STORE_FILE")
RELEASE_ALIAS_SOURCE         = System.getenv("AT_RELEASE_KEY_ALIAS") ?: "androidterminal"
RELEASE_STORE_PASSWORD_SOURCE = System.getenv("AT_RELEASE_STORE_PASSWORD")
RELEASE_KEY_PASSWORD_SOURCE  = System.getenv("AT_RELEASE_KEY_PASSWORD")
FALLBACK (no store file)     = debug signing config
```

```text
AT_RELEASE_STORE_FILE    = UNSET
AT_RELEASE_STORE_PASSWORD = UNSET
AT_RELEASE_KEY_ALIAS     = UNSET
AT_RELEASE_KEY_PASSWORD  = UNSET
```

> Only variable NAMES recorded. No secret values read or printed.

---

## 8. Credential Source Provenance

```text
CREDENTIAL_SOURCE_PROVENANCE =
  No committed credential source. The v0.4.0 release was signed locally with the
  AT_RELEASE_* env vars set at build time (commit 0f77369 "AT.APP-RELEASE-2: v0.4.0
  release signing"). The keystore + password live OUTSIDE git by design
  (AT.APP-RELEASE-1: "Keystore 保存在 Git 仓库外 ... Gradle 从环境变量读取密码 ...
  .gitignore 排除 .keystore"). No CI (.github) exists to supply the secrets.
  => The credential source is a local env-var setup that is not currently present.
```

---

## 9. Git Secret-Exposure Audit

```text
POTENTIAL_HISTORICAL_SECRET_EXPOSURE = NO
  - 0 commits ever added a .jks / .keystore (gitignored via .gitignore L28-29).
  - The single commit touching the literal "AT_RELEASE_STORE_PASSWORD" (0f77369)
    added only the System.getenv(...) reference, NOT a secret value.
  - No keystore filename / alias / env-var NAME recovery was performed for secrets.
```

---

## 10. Public Cert Sidecar Search

```text
PUBLIC_CERT_FOR_9C252F31 = NOT_FOUND
  (No *.cer/*.crt/*.pem with 9c252f31 fingerprint found. The .pem files present
   are ADB pairing certs (com.android.adbd, v1-credential-recovery), NOT the
   release signing cert.)
```

---

## 11. Provenance Matrix

| Candidate | File SHA256 | Cert SHA256 | PrivateKeyEntry | Credential available | Matches v0.4.0 |
|---|---|---|---|---|---|
| release-keystore.jks | 2CE7B05A... | 9c9898b1... | unreadable | password needed | **NO** |
| androidterminal-release-v1.jks | 7F7B734A... | UNREADABLE | UNRESOLVED | **NO (locked)** | **UNRESOLVED** |

```text
MATCHING_KEYSTORE_PATH     = UNRESOLVED (androidterminal-release-v1.jks is the most probable, but cert not proven)
MATCHING_KEYSTORE_SHA256   = UNRESOLVED
MATCHING_ALIAS             = UNRESOLVED (expected "androidterminal" per Gradle default)
MATCHING_ENTRY_TYPE        = UNRESOLVED
MATCHING_CERT_SHA256       = 9c252f31... (target, but no readable keystore cert to confirm)
```

---

## 12. Classification (per §19)

```text
PRIVATE_KEY_FILE_FOUND      = UNRESOLVED
  (a candidate file exists and is highly probable, but its cert/private-key
   entry cannot be confirmed without the password)
PRIVATE_KEY_ENTRY_CONFIRMED = UNRESOLVED
PRIVATE_KEY_LOST            = NOT_PROVEN
  ("password currently unavailable" != "private key lost")

CREDENTIAL_CLASSIFICATION   = B (KEYSTORE_FOUND_CREDENTIAL_UNAVAILABLE)
  — if androidterminal-release-v1.jks is confirmed to hold 9c252f31;
    otherwise D (MATCHING_KEYSTORE_NOT_FOUND)
  Current state: PARTIAL / KEYSTORE_NOT_PROVEN (cert unreadable)
```

---

## 13. Verdict

```text
STEP = AT.RELEASE-SIGNING-PROVENANCE-1
VERDICT = PARTIAL — KEYSTORE_NOT_PROVEN

CAN_SIGN_UPGRADE_COMPATIBLE_V041 = BLOCKED_BY_CREDENTIAL
  (the v0.4.0 release cert 9c252f31 is known and intact in the APK oracle;
   the most probable matching keystore androidterminal-release-v1.jks exists on
   disk but is locked — its password is not available in this environment.
   Signing an upgrade-compatible v0.4.1 is BLOCKED until the credential is
   legitimately restored.)

PRIVATE_KEY_FILE_FOUND = UNRESOLVED
PRIVATE_KEY_LOST       = NOT_PROVEN
```

---

## 14. Next

```text
NEXT = RESTORE_CREDENTIAL_SOURCE
  → user sets AT_RELEASE_STORE_FILE / AT_RELEASE_STORE_PASSWORD /
    AT_RELEASE_KEY_ALIAS / AT_RELEASE_KEY_PASSWORD on this machine
    (pointing at androidterminal-release-v1.jks), then re-run this audit to
    confirm cert == 9c252f31 + PrivateKeyEntry, then RESUME AT.0.4.1-RELEASE.

If credential cannot be restored:
  → AT.RELEASE-SIGNING-KEY-RECOVERY-DECISION-1
    (decide: continue searching backups / break signing lineage intentionally)
```

---

## 15. Compliance

```text
PASSWORD_BRUTE_FORCE_ATTEMPTED = NO
SECRET_VALUE_PRINTED           = NO
VERSION_CHANGED                = NO
PRODUCT_CODE_CHANGED           = NO
TAG_CREATED                    = NO
PUSHED                         = NO
RELEASED                       = NO
STOP                           = YES
```
