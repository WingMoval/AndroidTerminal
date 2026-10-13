# AT.V3-DEBT-1 Evidence — V2 Verified ADB Capability Parity Closure

> 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
> 网络：稳定 Wi-Fi / PC hotspot（配对/发现全程稳定）
> 身份：本轮测试用 V3 自生成 fresh identity（`androidterminal@app`，独立 keyDir），
>       未复用用户 PC trusted key（PC key 仅用于 testhost 回归注入）
> 日期：2026-10-13

## 0. 基线

```text
V3 HEAD = 3b6428a（AT.V3-DEBT-0 后 clean）
V2 HEAD = 726a441（只读，43 会前 dirty，零写入）
```

## 1. Gate 0 — Pairing source parity + deterministic tests

### Source parity（V2↔V3 MD5）

```text
pairing_aes_gcm.h/.cpp, pairing_auth.h/.cpp, pairing_client.h/.cpp,
pairing_packet.h, pairing_peerinfo.h, rsa_2048_key.h/.cpp
=> 10/10 IDENTICAL（DEBT-0 后复核，未变）
ANDROID_PUBKEY_524B_FIX = PRESENT（rsa_2048_key.cpp，offset4 n0inv，IDENTICAL）
SPAKE2 64B key material = PRESENT（pairing_auth.cpp kSpake2KeySize=SPAKE2_MAX_KEY_SIZE=64）
PAIRING_SOURCE_PARITY = IDENTICAL（10/10）
```

### Deterministic selftest（V3 新增 pairing_selftest 可执行，真机运行）

```text
PairingPacket roundtrip (BE/bounds)          PASS（12 checks）
PairingPacket invalid (short/ver/type/oversize)  PASS（5 checks）
PeerInfo 8192B layout + serialize/parse      PASS（14 checks）
SPAKE2 client/server 同 64B key               PASS
  INFO  SPAKE2 processMsg key_len=64 (SPAKE2_MAX_KEY_SIZE=64)   ← 历史回归 guard
SPAKE2 fresh ephemeral + wrong-password       PASS
AES-128-GCM roundtrip + bad-tag + wrong-key + empty-key  PASS（10 checks）
PAIRING_DETERMINISTIC_TESTS = 54/54 PASS（真机 /data/local/tmp/pairing_selftest，EXIT=0）
```

## 2. Gate 1 — Pairing endpoint discovery（V2 E1 parity）

### Service type 扩展

```text
AdbDiscoveryServiceType { CONNECT("_adb-tls-connect._tcp."), PAIRING("_adb-tls-pairing._tcp.") }
AdbDiscoveryBackend.scan(timeoutMs, serviceType=CONNECT)   ← 同一 NSD 实现，未复制 backend
AdbRuntime.discoverPairing(timeoutMs)                       ← ADB-domain 最小扩展
AT public contract = 0 change；:adb-engine 零 :at-engine 依赖
```

### 真机发现（配对弹窗打开时）

```text
设备侧 ss:  *:37251 LISTEN（+ connect *:37809）
mDNS discover: onServiceFound adb-APLMUT4312005935-J4nb5z
mDNS resolve: host=10.196.195.199 port=37251（与设备侧 ss 交叉印证）
PAIRING_DISCOVERY = PASS
PAIRING_DISCOVERY_RESOLVE = PASS
PAIRING_SERVICE_LIFECYCLE = PASS（弹窗关闭 → service 消失；V2 E1 已证瞬态）
PAIRING_ENDPOINT = 37251 / 38257 / 38453（evidence only，未 hardcode）
PAIRING_ENDPOINT_HARDCODED = NO
CONNECT_DISCOVERY_REGRESSION = PASS（g3 cold-start connectAuto → uid=2000 在 E4 后验证）
```

## 3. Gate 2 — Pairing primitive E4 parity（V2 E4）

### 真机因果链（码 284575 / 427661，两轮独立 fresh identity）

```text
IDENTITY_CREATED_BY_V3 = YES（AdbEngine 首次 connect 前 loadOrCreate 生成 fresh identity）
IDENTITY_PRETRUSTED = NO（fresh keyDir，未注入 PC key）
BEFORE_PAIR_CONNECT = FAIL_EXPECTED（connectAuto 失败：CONNECT_FAILED/TRANSPORT_ERROR，untrusted）

pair() 调用（V3 AdbEngine.pair，非 PC adb pair）：
  PairingClient::pair: 10.196.195.199:37251 code=284575
  PairingAuth::generateMsg: 32-byte SPAKE2 msg (role=alice/client)
  PairingAuth::processMsg: derived 64-byte key material
  PairingAesGcm::init: AES-128-GCM ready (from 64-byte SPAKE2 key)
  PairingClient::pair: SUCCESS device_guid=adb-APLMUT4312005935-J4nb5z
  V3_PAIR_CALL = PASS；PAIR_SUCCESS = YES；DEVICE_GUID_RETURNED = YES

设备信任：
  adbd: Matched auth_key=QAAAAMFK... androidterminal@app
  adbd: [server]: Handshake succeeded.
  → DEVICE_TRUSTS_IDENTITY = YES（设备 authorized-keys 接受 V3 fresh identity）

AFTER_PAIR_CONNECT_AUTO（同一 identity keyDir）：
  AdbRuntime: connectAuto READY via 127.0.0.1:37809
  STREAM_OPEN_TX service="shell,v2,raw:id" → RX_WRTE 281B → TX_OKAY_CREDIT → CLSE
  AFTER_PAIR_CONNECT_AUTO = PASS；AFTER_PAIR_PRINCIPAL = uid=2000(shell)
  AFTER_PAIR_ATENGINE_ID = PASS

CERTIFICATE_VERIFY_FAILED = NO（android_pubkey 524B fix 有效）
AUTH_KEY_MISMATCH = NO

PAIRING_V3_E4_PARITY = PASS
```

### Identity continuity（码 427661）

```text
同一 keyDir：pair 前 adbkey.pub == pair 后 adbkey.pub（字节一致）
PAIR_IDENTITY_CONTINUITY = PASS
```

### Paired identity reuse after restart（码 427661 配对后）

```text
force-stop adbonlyhost → fresh 进程
connectAuto(debt1_cont 已配对 identity) → READY（不重新 pair）
ATEngine.execute("id") → uid=2000(shell)
PAIRED_IDENTITY_REUSE_AFTER_RESTART = PASS
```

## 4. Gate 3 — Accessibility pairing-code acquisition（V2 E2 parity）

### DEBUG-ONLY probe（PairingCodeProbeService，validation-only，非产品组件）

```text
AccessibilityNodeInfo 读 com.android.settings:id/pairing_code：
  PAIRING_CODE_READ text='427661' vid=com.android.settings:id/pairing_code
  → 6 位数字 code 稳定读取
com.android.settings:id/ip_addr：
  PAIRING_IP_PORT_READ text='10.196.195.199:38453'（与弹窗端口交叉印证）
PAIRING_CODE_ACCESSIBILITY = PASS
PAIRING_CODE_VIEW_ID = com.android.settings:id/pairing_code
PAIRING_IP_PORT_ACCESSIBILITY = PASS
ACCESSIBILITY_SCOPE = VALIDATION_ONLY
（view id 属 OEM/Settings 实现细节，非 Android 公共契约；manual code fallback 为未来跨 OEM 路径）
```

## 5. Gate 4 — WRITE_SECURE_SETTINGS + app-process adb_wifi_enabled（V2 E3 parity）

### Grant 机制

```text
validation host manifest 声明 WRITE_SECURE_SETTINGS
shell pm grant com.androidterminal.adbonlyhost android.permission.WRITE_SECURE_SETTINGS
dumpsys package → WRITE_SECURE_SETTINGS: granted=true（非仅 pm exit code）
WRITE_SECURE_SETTINGS_DECLARED = VALIDATION_PACKAGE
WRITE_SECURE_SETTINGS_GRANT = PASS
WRITE_SECURE_SETTINGS_EFFECTIVE = PASS（granted=true 在 package state 确认）
```

### app-process Settings 写入（V3 进程自己 putInt，非 adb shell）

```text
app 进程 Settings.Global.putInt("adb_wifi_enabled", 0)
  → read back 0；wireless listener 停止（ss 无新 *:port）
  WD_DISABLE_LISTENER_STOP = PASS
app 进程 Settings.Global.putInt("adb_wifi_enabled", 1)
  → read back 1；listener 重启（新端口 37809 → 38249，动态轮换）
  WD_ENABLE_LISTENER_START = PASS；WD_PORT_ROTATION_OBSERVED = YES
app 进程 putInt 后：discovery → connectAuto（已配对 identity）→ READY → id uid=2000
  POST_AUTO_WD_DISCOVERY = PASS
  POST_AUTO_WD_CONNECT_AUTO = PASS
  POST_AUTO_WD_ID = PASS
APP_PROCESS_ADB_WIFI_ENABLED_CONTROL = PASS
AUTO_WD_MECHANISM_PARITY = PASS
AUTO_WD_PRODUCT_CLOSURE = NOT_STARTED（仅机制；无产品 auto-WD 策略）
```

## 6. Regression（Gate 5，全绿）

```text
Local (at.backend.local.test)            11/11   PASS
App Debug (app.test)                     16/16   PASS（7 debug input + 9 dynamic pages）
Headless (TestHostHeadlessInputTest)      1/1    PASS
ADB Backend (AdbBackendHeadlessTest)     25/25   PASS
Backend Parity (BackendParityAuditTest)  34/34   PASS（776.9s）
ADB Runtime lifecycle (AdbRuntimeLifecycleTest) 12/12 PASS（含 discovery serviceType 扩展后）
AT Engine unit (ATExecutionContractTest 3 + ATSessionContractTest 3)  6/6 PASS
Pairing deterministic (pairing_selftest) 54/54 PASS

FLOW_CONTROL_REGRESSION = PASS（Parity 34 覆盖 64K/256K/1M + WRTE/OKAY credit）
MULTI_STREAM_REGRESSION = PASS（Parity multi-session + RX_OWNER=1）
RECOVERY_REGRESSION = PASS（AdbRuntimeLifecycle + Parity）
RX_OWNER_COUNT = 1
```

## 7. Cleanup

```text
TEST_KEY_FILES_REMOVED = YES（files/debt1_e4, files/debt1_cont, pairing_code/ipport 删除）
TEMP_FILES_REMOVED = YES（/data/local/tmp/pairing_selftest 删除）
ACCESSIBILITY_PROBE_DISABLED = YES（enabled_accessibility_services 清空）
TEST_APKS_REMOVED = YES（adbonlyhost, testhost, local.test, app.test 卸载；主 App 保留）
WD_FINAL_STATE = 0（恢复约定值）
DEVICE_TEST_TRUST_RESIDUE = PRESENT / USER_CLEANUP_REQUIRED
  （本轮 3 个 androidterminal@app 测试 key 已入设备 user_keys；无 per-key remove API；
    系统"无线调试→忘记设备"可清理但会移除用户 PC key，需用户自行决定）
PAIRING_CODE = 仅临时使用（284575/427661），未写入本 evidence
PRIVATE KEY = 未写入本 evidence（仅公钥 comment/hash）
```

## 8. 安全

```text
测试 identity = V3 自生成（独立 keyDir），未打包进 APK，未 commit
未复用/复制用户 PC trusted key 到测试身份（PC key 仅 testhost 回归注入）
私钥未出现在 Git/docs/evidence/log archive
```