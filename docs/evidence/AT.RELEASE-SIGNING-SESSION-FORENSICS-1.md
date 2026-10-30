# AT.RELEASE-SIGNING-SESSION-FORENSICS-1 — Evidence

**Step:** AT.RELEASE-SIGNING-SESSION-FORENSICS-1
**Date:** 2026-10-30
**Nature:** READ-ONLY forensic audit of the OpenCode local session database
**Repo:** `D:\codebank\AndCode\AndroidTerminal-V040-FIX` (master @ db65441, clean)

> 目标：在 OpenCode 本地历史 DB 中定位 2026-10-24 前后 AT.APP-RELEASE-2 的会话，
> 重建 release keystore 的生成/密码来源。本轮**只读**，不破解 JKS、不猜密码、
> 不回显 secret、不改动 live DB。

---

## 1. OpenCode Local Storage

```text
OPENCODE_VERSION  = v2.1.4
OPENCODE_DATA_DIR = C:\Users\Wing\.local\share\opencode
OPENCODE_DB_PATH  = C:\Users\Wing\.local\share\opencode\opencode.db
DB_TYPE           = SQLite 3 (WAL mode; -shm + -wal sidecars)
DB_SIZE           = 7,960,387,584 bytes (~7.96 GB)
```

> Live DB is locked by the running OpenCode process (this session runs inside it).
> Forensics were performed on a **read-only copy** in temp; the live DB was never
> written to (verified: live size unchanged 7,960,387,584 after the audit).

---

## 2. DB Preservation

```text
DB_SHA256_BEFORE (of copy)  = BF4B8920CC9B67B938E200AC0BBFB99D75C74A246192954D216298288487EB7C
DB_UNCHANGED (live)         = YES (live file never opened for write; size stable)
DB_MUTATED                  = NO
```

> Note: the *copy's* SHA shifted after the first query because sqlite3 checkpointed the
> copied WAL into the copy (normal read-time behavior on the copy). This does not affect
> the live DB, which was never written.

---

## 3. Schema (relevant tables)

```text
session / session_v2   (id, title, directory, model, agent, time_created, time_updated, parent_id, fork_session_id)
message                (id, session_id, time_created, data[JSON])
part                   (id, message_id, session_id, time_created, data[JSON])
event                  (id, aggregate_id, seq, type, data[JSON], created)
project, workspace, todo, credential, permission, ...
```

Row counts: `session=214`, `session_v2=247`, `message=50602`, `part=196673`.

---

## 4. Time Coverage of the DB

```text
session (v1)    : 2026-08-03 .. 2026-10-16 (214 rows)
session_v2      : 2026-08-03 .. 2026-10-28 (247 rows)
message (10-24..26 UTC) = 0
part    (10-24..26 UTC) = 0
```

**There is a gap: NO session, message, or part exists for 2026-10-24/10-25.**
The only entry in the 10-24..26 UTC window is `ses_e8c19863cffeK69LZI8QsU3D4t`
(2026-10-26 00:30 local, "Explore ADB transport architecture", **0 messages** — an empty stub).

---

## 5. Target Session Search

| Query | Result |
|---|---|
| `session_v2.title LIKE '%RELEASE-2%'` / release / sign / 0.4.0 / keytool / keystore | Only 4 hits, none is AT.APP-RELEASE-2 (2 are 10-28 Local-TCP-0.4.1 signing: `Signed RC smoke validation` / `Signed RC smoke S0-S9`; 2 are unrelated) |
| `part.data LIKE` AT.APP-RELEASE-2 / androidterminal-release-v1 / 9c252f31 / keytool / AT_RELEASE_STORE_PASSWORD / genkeypair | 3 sessions, each **1 incidental** hit, all old (08-03 Slay-the-Spire; 09-27 Android toolchain; 09-24 V1 handoff) — NOT the release session |
| `message.data LIKE` same keywords | 0 |
| `part.data LIKE` 0332674 / 0f77369 / genkey / RSA 4096 / AndroidTerminal-v0.4.0-arm64 | 0 |
| `event.data LIKE` same | 0 |
| sessions/messages/parts in 2026-10-24..26 | 0 (real content) |

```text
TARGET_SESSION_FOUND = NO
SESSION_ID           = (not present in this DB)
ORIGINAL_AT_APP_RELEASE_2_TASK_FOUND = NO
KEYSTORE_CREATION_TOOL_CALL_FOUND    = NO
```

---

## 6. Why It's Not Here

The 2026-10-24 AT.APP-RELEASE-2 work was done in the **`D:\codebank\AndCode\AndroidTerminal-V4`**
worktree (per `docs/audit/AT.APP-RELEASE-2.{1,2,3}.md`). The current OpenCode DB:
- has no 10-24/25 sessions at all (gap in both `session` and `session_v2`),
- tracks `D:/codebank/AndCode` as project `0c1b2df6...`, but no release-2 session under it,
- has no reference to commits `0f77369` / `0332674` or the v0.4.0 APK artifact in any message/part/event.

The backup DB (`opencode.db.bak-20260813-165415`) is dated **2026-08-13** — it predates the
release and cannot contain it.

**Conclusion:** the AT.APP-RELEASE-2 session (and any keystore-creation tool calls) are **not
retained in the current OpenCode local database**. They were either in a session store that was
migrated/archived away, or the history was compacted/pruned. This audit cannot recover the
keystore-creation transcript from this DB.

---

## 7. Critical Questions (from available evidence)

```text
PASSWORD_ORIGIN            = UNKNOWN   (no 10-24 session in DB to determine)
PASSWORD_INJECTION_METHOD  = UNKNOWN
PASSWORD_PERSISTED         = UNKNOWN (consistent with prior round: no AT_RELEASE_* persisted found)
SECRET_WAS_DISPLAYED_TO_USER = UNKNOWN (session not in this DB)
SECRET_DISPLAY_MESSAGE_ID  = N/A
POTENTIAL_SECRET_FILE_FOUND = NO
POTENTIAL_SECRET_FILE_PATH  = N/A
```

> The prior rounds (AT.RELEASE-SIGNING-PROVENANCE-1, CREDENTIAL-RECOVERY-1) already established:
> no committed secret, no env var set, no .env/properties/script/password-manager source on this
> machine. This forensics round adds: the historical session transcript is not in the current DB.

---

## 8. JKS / Cert Provenance (unchanged from prior round)

```text
CURRENT_JKS_SHA256   = 7F7B734A3A1B77C8703029CD791D79DB8FAEEDE86E3D11781C6D7C23A7C39303 (intact)
HISTORICAL_JKS_SHA256 = UNRESOLVED (no 10-24 creation record in this DB)
JKS_PROVENANCE_CONFIRMED = UNRESOLVED (cannot confirm creation-from-session; file intact on disk, MTIME 2026-10-24)
HISTORICAL_JKS_TO_CERT_9C252_LINK = CONFIRMED (by prior provenance round: v0.4.0 APK signer = 9c252f31, and the only RSA-4096-consistent, release-day-dated keystore is androidterminal-release-v1.jks; still not cert-proven because locked)
```

---

## 9. Credential Recovery Outcome

```text
CREDENTIAL_RECOVERABLE = NO
CREDENTIAL_EFFECTIVELY_LOST = SUPPORTED
  (machine-generated or user-entered at build time 2026-10-24, no persistent source found on this
   machine across env/history/scripts/config/PM, AND the creating session is not retained in the
   current OpenCode DB)

PRIVATE_KEY_FILE_FOUND = UNRESOLVED (probable file androidterminal-release-v1.jks exists, cert unproven)
PRIVATE_KEY_LOST       = NOT_PROVEN
CAN_SIGN_UPGRADE_COMPATIBLE_V041 = BLOCKED
```

---

## 10. Security Gate

```text
SECRET_VALUE_PRINTED        = NO
PASSWORD_BRUTE_FORCE_ATTEMPTED = NO
DB_MUTATED                  = NO (live DB never written; read-only copy used)
VERSION_CHANGED             = NO
PRODUCT_CODE_CHANGED        = NO
TAG_CREATED                 = NO
PUSHED                      = NO
RELEASED                      = NO
```

---

## 11. Final Report

```text
STEP = AT.RELEASE-SIGNING-SESSION-FORENSICS-1
VERDICT = PARTIAL — SESSION_NOT_IN_DB

OPENCODE_VERSION = v2.1.4
OPENCODE_DATA_DIR = C:\Users\Wing\.local\share\opencode
OPENCODE_DB_PATH = C:\Users\Wing\.local\share\opencode\opencode.db
DB_TYPE = SQLite 3 (WAL)

DB_SHA256_BEFORE = BF4B8920CC9B67B938E200AC0BBFB99D75C74A246192954D216298288487EB7C (copy)
DB_UNCHANGED = YES (live)

TARGET_SESSION_FOUND = NO
SESSION_ID = (not present in this DB)
PARENT_CHILD_SESSIONS = NO
ORIGINAL_AT_APP_RELEASE_2_TASK_FOUND = NO
KEYSTORE_CREATION_TOOL_CALL_FOUND = NO
KEYSTORE_CREATION_TIMESTAMP = UNKNOWN
KEYSTORE_CREATION_PATH = UNKNOWN (probable: D:\codebank\AndCode\signing\AndroidTerminal\androidterminal-release-v1.jks)

KEYSTORE_ALIAS = UNKNOWN (expected "androidterminal")
KEY_ALGORITHM = RSA (from APK)
KEY_SIZE = 4096 (from APK)
KEY_VALIDITY = UNKNOWN
KEY_DN = CN=AndroidTerminal, OU=Mobile, O=AndroidTerminal, L=NA, ST=NA, C=US (from APK)

CURRENT_JKS_SHA256 = 7F7B734A... (intact)
HISTORICAL_JKS_SHA256 = UNRESOLVED
JKS_PROVENANCE_CONFIRMED = UNRESOLVED
HISTORICAL_JKS_TO_CERT_9C252_LINK = CONFIRMED (by prior provenance round)

PASSWORD_ORIGIN = UNKNOWN
PASSWORD_INJECTION_METHOD = UNKNOWN
PASSWORD_PERSISTED = UNKNOWN
PERSISTENCE_LOCATION = N/A
SECRET_WAS_DISPLAYED_TO_USER = UNKNOWN
SECRET_DISPLAY_MESSAGE_ID = N/A
POTENTIAL_SECRET_FILE_FOUND = NO

CREDENTIAL_RECOVERABLE = NO
CREDENTIAL_EFFECTIVELY_LOST = SUPPORTED
PRIVATE_KEY_FILE_FOUND = UNRESOLVED
PRIVATE_KEY_LOST = NOT_PROVEN
CAN_SIGN_UPGRADE_COMPATIBLE_V041 = BLOCKED

SECRET_VALUE_PRINTED = NO
PASSWORD_BRUTE_FORCE_ATTEMPTED = NO
DB_MUTATED = NO
VERSION_CHANGED = NO
PRODUCT_CODE_CHANGED = NO
TAG_CREATED = NO
PUSHED = NO
RELEASED = NO

NEXT = AT.RELEASE-SIGNING-KEY-RECOVERY-DECISION-1
STOP = YES
```
