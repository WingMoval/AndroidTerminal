# AT.RELEASE-SIGNING-CREDENTIAL-RECOVERY-1 — Evidence

**Step:** AT.RELEASE-SIGNING-CREDENTIAL-RECOVERY-1
**Date:** 2026-10-30
**Nature:** READ-ONLY credential-source discovery (no JKS cracking, no password guessing, no secret echo)
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master @ 007f911, clean)

> 目标：在本机查找用户**曾经合法保存/配置过**的 AT release credential 来源，恢复
> `AT_RELEASE_*`。本轮**不破解 JKS、不猜密码、不回显 secret**。

---

## 1. Starting State

```text
REPO         = D:\codebank\AndCode\AndroidTerminal-V040-FIX
BRANCH       = master
HEAD         = 007f911 (expected 007f911 → MATCH)
WORKTREE     = CLEAN
```

---

## 2. Target Keystore Integrity

```text
TARGET_KEYSTORE_PATH   = D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v1.jks
TARGET_KEYSTORE_SHA256 = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303
INTEGRITY              = INTACT (before AND after this audit; not mutated)
TARGET_CERT_SHA256     = 9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93 (expected)
```

---

## 3. Search Locations & Results

| # | Source | Location(s) searched | Reference found? |
|---|---|---|---|
| 1 | Process env | `AT_RELEASE_*` (Process scope) | NO (UNSET) |
| 2 | User env | `AT_RELEASE_*` (User scope) | NO (UNSET) |
| 3 | System env | `AT_RELEASE_*` (Machine scope) | NO (UNSET) |
| 4 | PowerShell history | `ConsoleHost_history.txt` | NOT FOUND (no file) |
| 5 | PowerShell profiles | `Documents\PowerShell\...\Microsoft.PowerShell_profile.ps1`, `Documents\WindowsPowerShell\...` | ABSENT (both) |
| 6 | `.ps1/.bat/.cmd` scripts | `D:\codebank\AndCode\**` (excl. build/.cxx) | NO (0 hits) |
| 7 | local `.env`/`*.properties` | `D:\codebank\AndCode\**` (excl. build/.cxx) | NO |
| 8 | user-profile shallow config | `~/.env`, `~/gradle.properties`, `~/.gradle/gradle.properties`, `~/.androidterminal/release.env`, `~/Documents/AndroidTerminal/release.env` | ABSENT (all) |
| 9 | project `.env`/notes/backup | `AndroidTerminal-V040-FIX`, `AndroidTerminal-V4`, `AndroidTerminal-V4-LOCAL-TCP-041`, `signing/` | NO credential source (only release docs + a V1 ADB-pairing-cert doc, unrelated to release keystore) |
| 10 | `signing/` dir | full recursive listing | ONLY the `.jks` (no password note, no sidecar, no `.env`) |
| 11 | password manager vaults | `~/Documents/KeePassFiles`, `~/OnePassword`, `~/Bitwarden` | ABSENT (all) |
| 12 | text/doc refs to `androidterminal-release-v1` | all `*.md/*.txt/*.ps1/*.log` | ONLY my own prior provenance doc (AT.RELEASE-SIGNING-PROVENANCE-1.md) — no independent credential note |
| 13 | release-prep / V3 releases | `AndroidTerminal-Public/analysis/release-prep`, `AndroidTerminal-V3/docs/releases` | NO release-keystore credential |

```text
PROCESS_ENV_REFERENCE_FOUND      = NO
USER_ENV_REFERENCE_FOUND         = NO
SYSTEM_ENV_REFERENCE_FOUND       = NO
POWERSHELL_HISTORY_REFERENCE_FOUND = NO
PROFILE_SCRIPT_REFERENCE_FOUND   = NO
LOCAL_CONFIG_REFERENCE_FOUND     = NO
IDE_CONFIG_REFERENCE_FOUND       = NO
OTHER_CREDENTIAL_SOURCE_FOUND    = NO
```

> No password value was ever read, echoed, or recorded. The only doc referencing the
> v1 jks filename is the prior provenance evidence (this round's predecessor).

---

## 4. Credential Recovery Result

```text
CREDENTIAL_RECOVERED = NO
AT_RELEASE_STORE_FILE     = UNSET
AT_RELEASE_STORE_PASSWORD = UNSET
AT_RELEASE_KEY_ALIAS      = UNSET
AT_RELEASE_KEY_PASSWORD   = UNSET

KEYSTORE_OPENED           = NO   (no credential → not opened; no guessing performed)
MATCHING_ALIAS            = UNRESOLVED
ENTRY_TYPE                = UNRESOLVED
KEYSTORE_CERT_SHA256      = UNRESOLVED (cannot read without credential)
KEYSTORE_CERT_MATCHES_V040 = UNRESOLVED
PRIVATE_KEY_ACCESSIBLE    = UNRESOLVED
SIGNING_CHAIN_RECOVERED   = NO
```

---

## 5. Verdict

```text
VERDICT = PARTIAL — NOT FOUND

PRIVATE_KEY_LOST = NOT_PROVEN
  (the keystore file is intact on disk; its password is simply not available in
   this environment. "credential not found" != "private key lost")

CAN_SIGN_UPGRADE_COMPATIBLE_V041 = BLOCKED
```

---

## 6. Next

```text
NEXT = AT.RELEASE-SIGNING-KEY-RECOVERY-DECISION-1

Per spec §19: the Controller is the sole user of this App, so the decision space is:
  - abandon the old signing lineage (9c252f31 / v0.4.0 release cert)
  - generate a NEW permanent release key
  - ship v0.4.1 as a FRESH-install release signed by the new key
  - permanently use the new key from v0.4.1 onward
  - (existing v0.4.0 release users, if any, would need to uninstall first)

This decision is NOT made in this round.
```

If the Controller can supply the store/key password (or restore the `AT_RELEASE_*`
env setup), re-run this round to confirm `cert == 9c252f31` + `PrivateKeyEntry`, then
`RESUME AT.0.4.1-RELEASE`.

---

## 7. Security Gate

```text
PASSWORD_BRUTE_FORCE_ATTEMPTED = NO
SECRET_VALUE_PRINTED           = NO
SECRET_COMMITTED               = NO
PRIVATE_KEY_EXPORTED           = NO
KEYSTORE_MUTATED               = NO
```

---

## 8. Compliance

```text
VERSION_CHANGED        = NO
PRODUCT_CODE_CHANGED   = NO
TAG_CREATED            = NO
PUSHED                 = NO
RELEASED                 = NO
STOP                   = YES
```
