# AT.0.4.0-WIRELESS-BASELINE-FREEZE — Evidence

**Step:** AT.0.4.0-WIRELESS-BASELINE-FREEZE
**Date:** 2026-10-30
**Branch:** master
**Validated Source HEAD:** a92eab5 (a92eab5a1550c839fc3f39628921c86a8a5c6c48)
**Device:** HONOR ALI-AN00, Android 15, `APLMUT4312005935`
**SSOT:** `docs/freeze/AT.0.4.0-WIRELESS-BASELINE-FREEZE.md`

> 本轮 DOCS + EVIDENCE ONLY。PRODUCTION_CODE_CHANGED = NO。

---

## 1. Baseline Integrity

```bash
git branch --show-current   # → master
git rev-parse --short HEAD  # → a92eab5
git status --short          # → (clean)
```

```text
BRANCH = master
HEAD   = a92eab5  (MATCH expected a92eab5)
STATUS = CLEAN
AUDIT  = a92eab5..HEAD 空（HEAD 即 expected，无额外 production change）
```

---

## 2. Historical Evidence Chain（CLOSED）

```text
AT.KEY-REGISTRATION-FLOW-1     = CLOSED  (identity lifecycle root cause confirmed)
AT.REPAIRING-UX-1              = CLOSED  (VERDICT = PASS, AT_REPAIRING_UX_1_STATUS = CLOSED)
AT.REPAIRING-UX-1.CLOSEOUT     = CLOSED  (regression + restart/reboot PASS)
AT.TRUST-LOSS-RECOVERY-1       = CLOSED  (VERDICT = PASS, AT_TRUST_LOSS_RECOVERY_STATUS = CLOSED)
```

`docs/evidence/AT.REPAIRING-UX-1.md`、`AT.REPAIRING-UX-1.CLOSEOUT.md`、
`AT.TRUST-LOSS-RECOVERY-1.md` 均存在且状态 CLOSED。

---

## 3. Test Suite (master)

```bash
.\gradlew.bat test :app:assembleDebug --console=plain
```

```text
BUILD SUCCESSFUL
MASTER_TEST_TOTAL = 143
MASTER_TEST_PASS  = 143
MASTER_TEST_FAIL  = 0
```

含：ADB Engine tests、architecture gates、trust-loss tests（TrustLossRecoveryTest 11/11）、
pairing metadata tests、bootstrap-state tests。

---

## 4. Build Gates

```bash
.\gradlew.bat :adb-engine:assembleDebug :app:assembleDebug :app:assembleRelease --console=plain
```

```text
BUILD_ADB_ENGINE  = PASS
BUILD_APP_DEBUG   = PASS
BUILD_APP_RELEASE = PASS   (NOT published, 未覆盖历史 0.4.0 发布物)
BUILD SUCCESSFUL
```

---

## 5. Lint Classification

```bash
.\gradlew.bat lint --console=plain
```

```text
Lint found 3 errors, 4 warnings  (adb-only-test-host)
```

逐项（全部**既有**，非本轮引入）：

```text
1. Error  CoarseFineLocation      adb-engine merged manifest (ACCESS_FINE_LOCATION)
2. Error  ProtectedPermissions    adb-only-test-host WRITE_SECURE_SETTINGS
3. Error  ProtectedPermissions    adb-only-test-host BIND_ACCESSIBILITY_SERVICE
warnings (非阻断): GradleDependency x2, DataExtractionRules, MissingApplicationIcon
```

```text
LINT = PRE_EXISTING_NON_BLOCKING
LINT_NEW_FINDINGS = 0
```

---

## 6. Architecture Gate

```bash
.\gradlew.bat test   # 全量；架构门类如下（XML 结果）
```

```text
com.at.architecture.ProductionArchitectureGateTest : tests=7 failures=0 errors=0
com.at.architecture.ArchitectureNegativeTest       : tests=6 failures=0 errors=0
```

```text
ARCHITECTURE_GATE = PASS
AdbRuntime = unique ADB lifecycle owner（未被 Activity/Backend/AT Engine 抢占）
NO Activity owning canonical trust state
NO Backend owning pairing policy
NO AT Engine depending on ADB lifecycle details
```

---

## 7. Wireless Smoke (non-destructive)

```bash
adb -s APLMUT4312005935 shell "settings put global adb_wifi_enabled 1"
adb -s APLMUT4312005935 shell "am force-stop com.androidterminal.app"
adb -s APLMUT4312005935 shell "am start -n com.androidterminal.app/.MainActivity"
adb -s APLMUT4312005935 shell "input tap 670 2134"   # ADB 连接
adb -s APLMUT4312005935 shell "logcat -d"            # 抓签名
```

```text
AdbNsdDiscovery: scan: 1 candidate(s): adb-APLMUT4312005935-J4nb5z:37019(192.168.137.57)
[C01] CONNECT_START 127.0.0.1:37019
[C02] TCP_CONNECTED 127.0.0.1:37019
[C04] RX_STLS (device requests TLS upgrade)
[C05] TX_STLS (reply to device, pre-TLS)
[C07] TLS_OK
[C12] REMOTE_FEATURES=[...21 features...]
[C13] ONLINE protocol_version=0x01000001 max_payload=1048576
[HUB01] RX_OWNER_START
AdbClient::connect: ADB session established (online, TLS active)
AdbEndpointStore: endpoint set MDNS_LOOPBACK 127.0.0.1:37019
```

```text
WIRELESS_DISCOVERY = PASS
WIRELESS_TCP       = PASS
WIRELESS_STLS      = PASS
WIRELESS_TLS       = PASS
WIRELESS_CNXN      = PASS
WIRELESS_READY     = PASS  (UI: ADB: READY, PAIRING PROMPT = NO)
```

---

## 8. Restart Smoke

```bash
adb -s APLMUT4312005935 shell "am force-stop com.androidterminal.app"
adb -s APLMUT4312005935 shell "am start -n com.androidterminal.app/.MainActivity"
adb -s APLMUT4312005935 shell "input tap 670 2134"
```

```text
APP_RESTART_READY = PASS  (ADB: READY)
PAIRING_PROMPT_ON_NORMAL_RESTART = NO
IDENTITY after restart = UNCHANGED
```

---

## 9. Reboot Smoke

```bash
adb -s APLMUT4312005935 reboot
# 等待设备回来 (~60s)
adb -s APLMUT4312005935 shell "settings put global adb_wifi_enabled 1"
adb -s APLMUT4312005935 shell "am force-stop com.androidterminal.app"
adb -s APLMUT4312005935 shell "am start -n com.androidterminal.app/.MainActivity"
adb -s APLMUT4312005935 shell "input tap 670 2134"
adb -s APLMUT4312005935 shell "logcat -d"
```

```text
[C04] RX_STLS / [C05] TX_STLS / [C07] TLS_OK / [C13] ONLINE
AdbClient::connect: ADB session established (online, TLS active)
```

```text
DEVICE_REBOOT_READY = PASS  (ADB: READY)
PAIRING_PROMPT_ON_DEVICE_REBOOT = NO
IDENTITY after reboot = UNCHANGED
```

---

## 10. Identity Stability

```bash
adb -s APLMUT4312005935 shell "run-as com.androidterminal.app \
  sha256sum /data/user/0/com.androidterminal.app/files/adb_identity/adbkey"
adb -s APLMUT4312005935 shell "run-as com.androidterminal.app \
  cat /data/user/0/com.androidterminal.app/files/adb_identity/pairing_meta"
```

```text
IDENTITY_PRIVATE_FILE_SHA256_BEFORE              = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
IDENTITY_PRIVATE_FILE_SHA256_AFTER_APP_RESTART   = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
IDENTITY_PRIVATE_FILE_SHA256_AFTER_DEVICE_REBOOT = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
→ ALL_EQUAL = YES

PAIRING_PUBLIC_KEY_FINGERPRINT (pairing_meta.paired_key_fingerprint):
  BEFORE = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
  AFTER  = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
  → ALL_EQUAL = YES
device_guid = adb-APLMUT4312005935-J4nb5z (unchanged)
```

> 未复制 private key / pairing code（仅 SHA256 / fingerprint 派生值）。

---

## 11. Freeze Validation Artifact

```bash
.\gradlew.bat :app:assembleDebug
# 计算哈希
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
# 取证书
apksigner verify --print-certs app\build\outputs\apk\debug\app-debug.apk
# 取版本
aapt2 dump badging app\build\outputs\apk\debug\app-debug.apk
```

```text
APK_PATH     = D:\codebank\AndCode\AndroidTerminal-V040-FIX\app\build\outputs\apk\debug\app-debug.apk
APK_SHA256   = 6F807990D32B4F3AC44B124626571434C3D41AE2E64B136D89D8BB35DB1D5F84
APK_SIZE     = 8475090 bytes (8.08 MB)
VERSION_NAME = 0.4.0
VERSION_CODE = 2
CERT_SHA256  = 56586d91ebe30843e44840524d4d7d2b045ae34a006f22eee7c726f65bee41b9
```

> FREEZE VALIDATION ARTIFACT，非发布物。未覆盖历史 0.4.0 发布物。

---

## 12. Known Non-Blocking Debt（记录，不修）

```text
D1  adb-only-test-host lint: 3 pre-existing manifest findings
D2  GUID_STORED_BUT_NOT_ENFORCED
D3  0.4.1 LOCAL_TCP_BYPASS_OF_TRUST = PRESENT
D4  Local TCP normalization policy unresolved
```

以上**不得**被误写成 Wireless baseline failure。

---

## 13. Verdict

```text
VERDICT = PASS
AT_040_WIRELESS_BASELINE_STATUS = FROZEN
TAG_CREATED = NO
PUSHED = NO
RELEASED = NO
PRODUCTION_CODE_CHANGED = NO
WIRELESS_POLICY_CHANGED = NO
LOCAL_TCP_CHANGED = NO
V1_CHANGED = NO

NEXT = RESUME feature/local-tcp-v0.4.1 → AT.LOCAL-TCP-DIRECT-PROMOTION-1
```
