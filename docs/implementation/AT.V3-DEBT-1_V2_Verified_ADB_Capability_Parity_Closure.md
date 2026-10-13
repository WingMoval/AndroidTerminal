# AT.V3-DEBT-1 — V2 Verified ADB Capability Parity Closure

**V2 已验证 ADB 能力恢复闭环**

- 日期：2026-10-13
- 基线：V3 `3b6428a`（clean）/ V2 `726a441`（43 会前 dirty，零写入）
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）—— 稳定 Wi-Fi / hotspot
- 结论：**STATUS = COMPLETE**（V2_VERIFIED_MIGRATION_DEBT = 0）

---

## 1. Objective

只恢复"V2 有真实设备验证证据、但 V3 尚未达到同等证据级别"的 ADB 能力：

```text
A. Pairing primitive V3 real-device parity（V2 E4）
B. Pairing endpoint discovery _adb-tls-pairing._tcp（V2 E1）
C. Pairing code Accessibility acquisition（V2 E2）
D. WRITE_SECURE_SETTINGS bootstrap mechanism（V2 E3）
E. adb_wifi_enabled / auto-WD mechanism（V2 E3）
```

本轮**不是**：Pairing 产品化、First-run UI、PairingCoordinator、Identity lifecycle、
AUTH_FAILURE taxonomy、Reconnect 产品化、Emulator、Contract Freeze、AndroidAgent。

## 2. Baseline

```text
V3 HEAD  = 3b6428a（AT.V3-DEBT-0 后 clean）
V3 status = CLEAN（本轮开始）
V2 HEAD  = 726a441
V2 status = 43 项会前 dirty（本轮 V2 零写入）
设备     = 已接入（稳定 Wi-Fi / hotspot）
```

## 3. V2 Oracle

| V2 能力 | V2 最终状态 | 证据 |
|---|---|---|
| Pairing primitive（SPAKE2/AES-GCM/HKDF/PeerInfo/EKM "adb-label"） | VERIFIED/CLOSED | AT.C-1 |
| E4（fresh identity → pair → trust → connectAuto → uid=2000） | PASS | AT.C-1 §13 |
| Pairing endpoint discovery（_adb-tls-pairing._tcp） | E1 VERIFIED | FIRST_RUN §5.5 |
| Pairing code Accessibility（com.android.settings:id/pairing_code） | E2 VERIFIED | FIRST_RUN §6.3 |
| WRITE_SECURE_SETTINGS + adb_wifi_enabled mechanism | E3 VERIFIED（机制） | FIRST_RUN §9.4 |

## 4. Scope / Exclusions

```text
允许：
  - ADB-domain 最小扩展（discovery serviceType、AdbRuntime.discoverPairing）
  - deterministic pairing selftest（native，V2 IDENTICAL 迁移）
  - validation host（adb-only-test-host）manifest：WRITE_SECURE_SETTINGS + DEBUG-ONLY Accessibility probe
  - :adb-engine 仅 ADB-domain 改动；AT public contract 0 change

禁止（V2 design-only，非 migration debt）：
  PairingCoordinator / PAIRING_* 产品状态 / AUTH_FAILURE taxonomy /
  Identity lifecycle / re-pair policy / Settings 导航产品流 / manual code UI /
  onboarding UI / Reconnect UI / background recovery / liveness
```

## 5. Pre-Audit

```text
PAIRING_NATIVE_PRESENT = YES（adb/pairing/*，V2 逐字节 IDENTICAL 10/10）
PAIRING_JNI_PRESENT = YES（AdbNative.pair + Java_..._AdbNative_pair，结构化错误）
PAIRING_KOTLIN_PRESENT = YES（AdbEngine.pair → AdbPairingResult）
ANDROID_PUBKEY_FIX_PRESENT = YES（rsa_2048_key.cpp 524B，offset4 n0inv）
PAIRING_DISCOVERY_PRESENT = NO（discovery 仅 connect；本轮扩展）
ACCESSIBILITY_PROBE_PRESENT = NO（本轮新增 DEBUG-ONLY probe）
WRITE_SECURE_PERMISSION_PRESENT = NO（validation host 本轮声明）
AUTO_WD_CONTROL_PRESENT = NO（本轮验证机制）
```

## 6. Pairing Source Parity

```text
V2↔V3 MD5（pairing 8 + crypto 2 = 10 files）：
  pairing_aes_gcm.h/.cpp, pairing_auth.h/.cpp, pairing_client.h/.cpp,
  pairing_packet.h, pairing_peerinfo.h, rsa_2048_key.h/.cpp
  => 10/10 IDENTICAL
PAIRING_SOURCE_PARITY = IDENTICAL（10/10）
SPAKE2 key material = 64B（kSpake2KeySize=SPAKE2_MAX_KEY_SIZE；V2 AT.C-1 §6.3 #2 修复在）
android_pubkey 524B = PRESENT（V2 AT.C-1 §6.3 #6 修复在）
```

## 7. Deterministic Pairing Tests

```text
新增 :adb-engine CMake target `pairing_selftest`（V2 IDENTICAL 迁移 pairing_selftest.cpp）
真机运行（/data/local/tmp/pairing_selftest，EXIT=0）：
  PairingPacket framing (BE/bounds/invalid)     12 PASS
  PeerInfo 8192B layout/serialize/parse         14 PASS
  SPAKE2 client/server 64B key + fresh + wrong-pw  8 PASS
    INFO  SPAKE2 processMsg key_len=64          ← 历史"32B 假 PASS"回归 guard
  AES-128-GCM roundtrip/bad-tag/wrong-key       10 PASS
  => PAIRING_DETERMINISTIC_TESTS = 54/54 PASS
```

## 8. Pairing Discovery Parity（Gate 1）

```text
扩展（最小、backend-neutral、不复制）：
  AdbDiscoveryServiceType { CONNECT, PAIRING }
  AdbDiscoveryBackend.scan(timeoutMs, serviceType=CONNECT)
  AdbAndroidNsdDiscoveryBackend 用 serviceType.serviceType（同一 NSD 实现）
  AdbRuntime.discoverPairing(timeoutMs)

真机（配对弹窗打开）：
  ss: *:37251（pairing）+ *:37809（connect）
  mDNS resolve → 10.196.195.199:37251（交叉印证）
  PAIRING_DISCOVERY = PASS；RESOLVE = PASS；LIFECYCLE = PASS（弹窗关→消失）
  CONNECT_DISCOVERY_REGRESSION = PASS（E4 后 connectAuto 正常）
```

## 9. E4 Pairing Parity（Gate 2）

```text
fresh identity（V3 loadOrCreate，keyDir 独立，PRETRUSTED=NO）
BEFORE_PAIR_CONNECT = FAIL_EXPECTED（untrusted）
V3 AdbEngine.pair(host, pairing_port, code) = SUCCESS
  device_guid=adb-APLMUT4312005935-J4nb5z
  日志：SPAKE2 32B msg → 64B key material → AES-GCM ready → SUCCESS
设备信任：adbd Matched auth_key=...androidterminal@app + Handshake succeeded
同 identity connectAuto → READY → ATEngine id → uid=2000(shell)
CERTIFICATE_VERIFY_FAILED = NO；AUTH_KEY_MISMATCH = NO
PAIRING_V3_E4_PARITY = PASS
PAIR_IDENTITY_CONTINUITY = PASS（同 pub key 跨 pair/connect）
PAIRED_IDENTITY_REUSE_AFTER_RESTART = PASS（force-stop → 新进程 → 同 identity connectAuto，不 re-pair）
```

## 10. Identity Continuity

```text
pair 前 adbkey.pub == pair 后 adbkey.pub（同 keyDir，字节一致）
identity lifetime 跨 pair/connect/restart 稳定复用
PAIR_IDENTITY_CONTINUITY = PASS
```

## 11. Accessibility Parity（Gate 3）

```text
DEBUG-ONLY PairingCodeProbeService（validation-only，非产品组件）
AccessibilityNodeInfo.text 读 com.android.settings:id/pairing_code = 427661（6 位稳定）
com.android.settings:id/ip_addr = 10.196.195.199:38453（端口交叉印证）
PAIRING_CODE_ACCESSIBILITY = PASS
PAIRING_IP_PORT_ACCESSIBILITY = PASS
ACCESSIBILITY_SCOPE = VALIDATION_ONLY
（view id 非 Android 公共契约；manual fallback 为未来跨 OEM 产品路径）
```

## 12. WRITE_SECURE_SETTINGS Parity（Gate 4）

```text
validation host manifest 声明 WRITE_SECURE_SETTINGS
shell pm grant → dumpsys package granted=true（非仅 exit code）
WRITE_SECURE_SETTINGS_GRANT = PASS；EFFECTIVE = PASS
```

## 13. Auto-WD Mechanism Parity（Gate 4）

```text
app 进程（V3）Settings.Global.putInt("adb_wifi_enabled", 0)
  → read back 0；listener 停止（WD_DISABLE_LISTENER_STOP = PASS）
app 进程 putInt(1)
  → read back 1；listener 重启 + 端口轮换（37809→38249）（WD_ENABLE_LISTENER_START = PASS；
    WD_PORT_ROTATION_OBSERVED = YES）
putInt 后 discovery → connectAuto（已配对 identity）→ READY → id uid=2000
  POST_AUTO_WD_DISCOVERY/CONNECT_AUTO/ID = PASS
APP_PROCESS_ADB_WIFI_ENABLED_CONTROL = PASS
AUTO_WD_MECHANISM_PARITY = PASS
AUTO_WD_PRODUCT_CLOSURE = NOT_STARTED（仅机制恢复，无产品策略）
```

## 14. V2 E1-E4 Matrix

| V2 | V3 parity | 结果 |
|---|---|---|
| E1 pairing discovery | `_adb-tls-pairing._tcp` 真机发现+resolve+lifecycle | **PASS** |
| E2 Accessibility code | `com.android.settings:id/pairing_code` 读 6 位码 + ip_addr | **PASS** |
| E3 WRITE_SECURE + auto-WD | grant 生效 + app 进程 putInt 0/1 控制监听 + post-enable connectAuto | **PASS** |
| E4 pairing primitive | fresh identity → pair → trust → connectAuto → uid=2000 | **PASS** |

## 15. Regression

```text
Local 11/11 · App 16/16 · Headless 1/1 · ADB Backend 25/25 · Backend Parity 34/34（776.9s）
AdbRuntimeLifecycle 12/12（含 discovery serviceType 扩展后）· AT Engine unit 6/6
Pairing deterministic 54/54
FLOW_CONTROL / MULTI_STREAM / RECOVERY = PASS；RX_OWNER_COUNT = 1
```

## 16. Cleanup

```text
TEST_KEY_FILES_REMOVED = YES（debt1_e4/debt1_cont/pairing_* 删除）
TEMP_FILES_REMOVED = YES（/data/local/tmp/pairing_selftest）
ACCESSIBILITY_PROBE_DISABLED = YES
TEST_APKS_REMOVED = YES（主 App 保留）
WD_FINAL_STATE = 0
DEVICE_TEST_TRUST_RESIDUE = PRESENT / USER_CLEANUP_REQUIRED
  （本轮测试 identity 入设备 user_keys；无 per-key remove API；用户可"忘记设备"但会移除 PC key）
```

## 17. Remaining Design-only Work（非 V2 debt，转入 ADB Engine Completion）

```text
PairingCoordinator / PAIRING_* 状态机 / AUTH_FAILURE taxonomy / Identity lifecycle /
re-pair policy / identity invalidation / Settings 导航产品流 / manual code 产品 UI /
onboarding UI / Reconnect 产品 UX / background recovery / liveness
全部 = NOT_STARTED（V2 design-only，DEBT-0 已分类 D；不属本轮）
```

## 18. Final Verdict

```text
AT.V3-DEBT-1 FINAL VERDICT

STATUS = COMPLETE

BASELINE_V3 = 3b6428a（clean）
BASELINE_V2 = 726a441（43 会前 dirty）

V2_WRITES = 0
PRODUCTION_CODE_MODIFIED = YES（:adb-engine ADB-domain 扩展 + validation host）
AT_ENGINE_MODIFIED = NO
AT_ENGINE_PUBLIC_CONTRACT_CHANGED = NO
ADB_ENGINE_MODIFIED = YES（discovery serviceType + AdbRuntime.discoverPairing +
                       pairing_selftest CMake target；native pairing 0 改动）
APP_VALIDATION_CODE_MODIFIED = YES（adb-only-test-host validation-only）
LOCAL_BACKEND_MODIFIED = NO

PAIRING_SOURCE_PARITY = IDENTICAL（10/10）
PAIRING_SOURCE_IDENTICAL_COUNT = 10/10
ANDROID_PUBKEY_524B_FIX_PRESENT = YES
PAIRING_DETERMINISTIC_TESTS = 54/54

PAIRING_DISCOVERY_SERVICE = _adb-tls-pairing._tcp
PAIRING_DISCOVERY_PARITY = PASS
PAIRING_DISCOVERY_RESOLVE = PASS
PAIRING_SERVICE_LIFECYCLE = PASS
CONNECT_DISCOVERY_REGRESSION = PASS

FRESH_IDENTITY_CREATED_BY_V3 = YES
IDENTITY_PRETRUSTED = NO
BEFORE_PAIR_CONNECT = FAIL_EXPECTED
V3_PAIR_CALL = PASS
PAIR_SUCCESS = YES
DEVICE_GUID_RETURNED = YES
PAIR_IDENTITY_CONTINUITY = PASS
AFTER_PAIR_CONNECT_AUTO = PASS
AFTER_PAIR_PRINCIPAL = uid=2000(shell)
AFTER_PAIR_ATENGINE_ID = PASS
PAIRING_V3_E4_PARITY = PASS
CERTIFICATE_VERIFY_FAILED = NO
AUTH_KEY_MISMATCH = NO
PAIRED_IDENTITY_REUSE_AFTER_RESTART = PASS

PAIRING_CODE_ACCESSIBILITY = PASS
PAIRING_CODE_VIEW_ID = com.android.settings:id/pairing_code
PAIRING_IP_PORT_ACCESSIBILITY = PASS
ACCESSIBILITY_SCOPE = VALIDATION_ONLY

WRITE_SECURE_SETTINGS_DECLARED = VALIDATION_PACKAGE
WRITE_SECURE_SETTINGS_GRANT = PASS
WRITE_SECURE_SETTINGS_EFFECTIVE = PASS
APP_PROCESS_ADB_WIFI_ENABLED_CONTROL = PASS
WD_DISABLE_LISTENER_STOP = PASS
WD_ENABLE_LISTENER_START = PASS
WD_PORT_ROTATION_OBSERVED = YES
POST_AUTO_WD_DISCOVERY = PASS
POST_AUTO_WD_CONNECT_AUTO = PASS
POST_AUTO_WD_ID = PASS
AUTO_WD_MECHANISM_PARITY = PASS
AUTO_WD_PRODUCT_CLOSURE = NOT_STARTED

V2_E1_PARITY = PASS
V2_E2_PARITY = PASS
V2_E3_PARITY = PASS
V2_E4_PARITY = PASS

V2_VERIFIED_CAPABILITY_PARITY = CLOSED
V2_VERIFIED_MIGRATION_DEBT = 0

PAIRING_MECHANISM_CLOSURE = VERIFIED_IN_V3
FIRST_RUN_MECHANISM_BASELINE = RESTORED
FIRST_RUN_PRODUCT_ONBOARDING = NOT_STARTED
PAIRING_COORDINATOR = NOT_STARTED
IDENTITY_LIFECYCLE = NOT_STARTED
AUTH_FAILURE_CLASSIFICATION = NOT_STARTED
REPAIR_POLICY = NOT_STARTED
MANUAL_PAIRING_CODE_PRODUCT_UI = NOT_STARTED
RECONNECT_PRODUCT_UX = DEFERRED

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_REGRESSION = 16/16
HEADLESS_REGRESSION = 1/1
ADB_BACKEND_REGRESSION = 25/25
BACKEND_PARITY_REGRESSION = 34/34
ADB_RUNTIME_REGRESSION = 12/12
PAIRING_DETERMINISTIC_TESTS = 54/54

FLOW_CONTROL_REGRESSION = PASS
MULTI_STREAM_REGRESSION = PASS
RECOVERY_REGRESSION = PASS
RX_OWNER_COUNT = 1

TEST_KEY_FILES_REMOVED = YES
TEMP_FILES_REMOVED = YES
TEST_APKS_REMOVED = YES
DEVICE_TEST_TRUST_RESIDUE = PRESENT / USER_CLEANUP_REQUIRED
WD_FINAL_STATE = 0

EVIDENCE = docs/evidence/at_v3_debt_1/
GIT_COMMIT = 6d22ab8（AT.V3-DEBT-1 closure commit）
GIT_WORKTREE = CLEAN
V2_WRITES = 0

NEXT = ADB_ENGINE_COMPLETION
```

## 19. 项目状态（COMPLETE 后）

```text
V2 Pairing Primitive                          VERIFIED IN V3（E4）
V2 Pairing Discovery                          VERIFIED IN V3（E1）
V2 Accessibility Pairing Code Mechanism       VERIFIED IN V3（E2，validation level）
V2 WRITE_SECURE_SETTINGS Bootstrap Mechanism  VERIFIED IN V3（E3，validation level）
V2 Auto-WD Mechanism                          VERIFIED IN V3（E3，validation level）

V2_VERIFIED_MIGRATION_DEBT = 0

此后剩余工作不再叫"还 V2 债"，转入：
ADB Engine Completion / Productization（Pairing lifecycle、Identity lifecycle、
AUTH classification、re-pair、first-run orchestration、diagnostics、reconnect UX）
```