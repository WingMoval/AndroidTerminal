# AT.TRUST-LOSS-RECOVERY-1 — Device-Side Trust Revocation Recovery

**Step:** AT.TRUST-LOSS-RECOVERY-1
**Date:** 2026-10-30
**Device:** HONOR ALI-AN00, Android 15, `APLMUT4312005935`
**Purpose:** 完成 "device-side trust 丢失，但 local identity 未变化" 时的恢复闭环。

---

## 1. Baselines

```text
MASTER_BASE_HEAD   = c5caa6f (master / 0.4.0)
FEATURE_BASE_HEAD  = a44f673 (feature/local-tcp-v0.4.1 / 0.4.1)
```
两分支工作树 clean（无 reset/stash/clean）。

---

## 2. Precondition

```text
PRECONDITION_READY = PASS
KEY_FP_INITIAL     = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
PAIRING_META_FP_INITIAL = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
isCurrentKeyPaired() = true, ADB = READY
```

---

## 3. Existing Semantics Audit (CURRENT_TRUST_LOSS_BEHAVIOR)

```text
1. 本地 paired metadata 在 connect failure 后是否自动失效？ → NO（不失效）
2. 哪一层有权把 paired → needs pairing？ → 仅 AdbRuntime（lifecycle owner）
3. 当前能否区分 TCP failure / TLS-trust rejection / transport loss？ → NO
   （connectOne 只返回 bool；AdbRuntimeDiagnostic 只有 code/message）
4. paired metadata 存在但 device trust 被删除 → 展示什么？ → 普通 CONNECT_FAILED
   （误判：不提示 re-pair，用户看到误导性 "未连接ADB" 通用提示）
```

---

## 4. Critical Diagnosis — Trust-Rejection Client Signature

**这是本轮最关键的诊断数据。** 通过重启设备隔离 Local TCP（38800 消失）后强制走
Wireless 路径，捕获到 device-side trust 丢失时客户端真实签名：

```text
POST_FORGET_DISCOVERY  = PASS (mDNS 发现 endpoint 192.168.137.57:40975)
POST_FORGET_TCP        = PASS (TCP_CONNECTED)
POST_FORGET_STLS       = PASS (RX_STLS + TX_STLS)
POST_FORGET_CLIENT_TLS = PASS (SSL_do_handshake rc=1 → TLS_OK)   ← 客户端 TLS 握手"成功"
POST_FORGET_DEVICE_CNXN= FAIL
   adbd: Handshake failed in SSL_accept [CERTIFICATE_VERIFY_FAILED]  ← 设备端拒绝我们的证书
   [C09] RX_CNXN_POST_TLS read failed: clean EOF (FIN)              ← 设备在发 CNXN 前关闭 socket
POST_FORGET_FAILURE_STAGE = POST_TLS (TLS 之后、设备 CNXN 之前)
```

**关键洞察：** BoringSSL 客户端在 peer 证书被拒时**不会**让 `SSL_do_handshake` 失败
（`rc=1`），所以 `TlsError::CertificateRejected`（客户端侧 `SSL_R_CERTIFICATE_VERIFY_FAILED`）
**永远不会触发**。真正的信任拒绝签名是：

```text
TRUST_REJECTION_SIGNATURE =
  TLS handshake SUCCEEDS (TLS_OK)
  + device closes socket BEFORE post-TLS CNXN (post-TLS read EOF)
  [+ post-TLS AUTH rejection, secondary]
  (NO device CNXN, NO AUTH/OKAY)
```

因此**不能**依赖 adbd logcat 的 `CERTIFICATE_VERIFY_FAILED`（产品逻辑不得依赖系统
daemon 日志），也**不能**依赖客户端侧 cert-verify error。必须从客户端可观察结果推断：
TLS_OK + post-TLS read failure (EOF) + 无 CNXN。

```text
CLIENT_OBSERVABLE_TRUST_SIGNAL = YES
（通过 AdbConnection 的 post-TLS 读失败路径观察，非 logcat）
```

---

## 5. Implementation (Trust-Loss Transition)

```text
TRUST_LOSS_STATE_TRANSITION =
  AdbConnection 观察 post-TLS 失败 → last_connect_trust_rejected_ = true
  → AdbClient.last_connect_trust_rejected() = true
  → AdbRuntime.connectOne() 记录 lastWirelessConnectTrustRejected = true (lifecycle owner)
  → AdbConsumerBootstrap.bootstrapState():
        lastWirelessConnectTrustRejected → PAIRING_REQUIRED  (在 fingerprint 检查之前)
  → UI (MainActivity): "ADB: NEEDS PAIRING" + 信任丢失专用提示
```

修改文件（生产）：
- `adb_connection.{h,cpp}`：post-TLS read failure + post-TLS AUTH 设 flag；connect() 入口重置
- `adb_client.{h,cpp}`：`last_connect_trust_rejected()` = connection flag OR transport cert-rejected
- `tls_transport.{h,cpp}`：`last_tls_error()` 暴露（次要信号，保留）
- `adb_engine_jni.cpp`：`lastConnectTrustRejected()` JNI
- `AdbNative.kt` / `AdbEngine.kt`：Kotlin 绑定
- `AdbRuntime.kt`：`lastWirelessConnectTrustRejected` + test setter；connectOne 观察
- `AdbConsumerBootstrap.kt`：bootstrapState + bootstrapStateForDir 信任判断；trustRejectedObserved()
- `ATAndroidAdbControl.kt`：trustRejectedObserved() 投影
- `MainActivity.kt`：NEEDS PAIRING + 信任丢失专用提示文案

```text
PAIRING_METADATA_INVALIDATION_POLICY =
  不删除 key / 不重新生成 key / 不删 metadata。
  信任丢失由 Runtime 的 lastWirelessConnectTrustRejected 观察驱动 PAIRING_REQUIRED；
  re-pair 成功后 pair() 重写 metadata（更新 paired_at，fingerprint 不变）。
  metadata 本身不主动失效（保持 REPAIRING-UX-1 的 fingerprint-binding 语义）。
```

---

## 6. Real-Device Gates

### Gate A — Baseline (existing paired identity)
```text
GATE_A_READY = PASS (ADB: READY, no re-pair)
```

### Device-Side Forget
用户在 无线调试 → 已配对的设备 删除了当前 key 的 `androidterminal@app` 条目
（列表 15 条：14× androidterminal@app + 1× Wing@DESKTOP-PG78M2N；删 1 条命中当前 key）。

```text
KEY_FP_AFTER_FORGET       = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea  (UNCHANGED)
PAIRING_META_FP_AFTER_FORGET = 3a46ca14... (UNCHANGED, paired_at 未变)
LOCAL_IDENTITY_UNCHANGED_AFTER_FORGET  = YES
LOCAL_METADATA_UNCHANGED_AFTER_FORGET  = YES
DEVICE_TRUST_REMOVED = YES (Wireless TLS post-TLS rejection 证实)
```

### Gate B — First Connect After Forget (Wireless-only, post-reboot)
```text
POST_FORGET_FAILURE_STAGE = POST_TLS (TLS_OK + device close before CNXN)
UI: "ADB: NEEDS PAIRING" + "ADB 身份仍在，但设备端信任已被移除（无线调试中已忘记此设备）。
    点「配对」按钮，用当前 6 位配对码重新配对（不会重新生成密钥）。"
TRUST_LOSS_UI = PASS
TRUST_FAILURE_CONNECT_LOOP = ABSENT (bounded retries，无 connectAuto 死循环)
```

### Gate C/D — Re-pair Same Identity + READY
```text
REPAIR_KEY_FP_BEFORE = c46e4eb2...
REPAIR_KEY_FP_AFTER  = c46e4eb2...
SAME_KEY_REPAIR = YES
PAIR_RESULT = PASS (6 位码 re-pair via AT PairActivity)
paired_at UPDATED (1793336721837 → 1793339980047)  ← pair() 重写 metadata
AUTO_RECONNECT_AFTER_REPAIR = PASS (TLS_OK → ONLINE, ADB: READY, no manual re-connect)
REPAIR_READY = PASS
IDENTITY_PRESERVED_THROUGH_TRUST_LOSS = PASS
  (KEY_FP_INITIAL == AFTER_FORGET == AFTER_FAILED_CONNECT == AFTER_REPAIR == c46e4eb2)
```

### Gate E — App Restart
```text
APP_RESTART_READY = PASS (force-stop + relaunch → ADB: READY, no pairing prompt)
```

### Gate F — Device Reboot
```text
DEVICE_REBOOT_READY = PASS (reboot + WD enable → ADB: READY, no re-pair)
KEY + metadata unchanged after reboot
```

---

## 7. Local TCP Interaction

```text
LOCAL_TCP_BYPASS_OF_TRUST = PRESENT (观察到的既有行为，非本轮引入)
```
**说明：** 0.4.1 的 Local TCP (127.0.0.1:38800) 使用**同一把 key** 且无 TLS 信任检查，
在 trust loss 时仍会连接成功 → 掩盖 Wireless trust-loss。本轮通过**重启设备**隔离
Local TCP（重启后 38800 消失）来强制走 Wireless 路径，从而捕获 trust-rejection 签名。
这**不是**本轮引入的回归（Local TCP 是 0.4.1 既有设计），但记录为已知交互：
在 Local TCP 可用时，Wireless trust loss 不会被 UI 暴露（因为 Local 仍 READY）。
本轮范围（§2 Hard Rules）禁止修改 Local TCP policy，故仅记录，不修。

`NORMAL_NETWORK_FAILURE_MISCLASSIFIED = NO`
（post-TLS EOF 仅在 TLS 已成功且设备在 CNXN 前关闭时触发；pre-TLS 网络失败
不设 flag — T2/T10 单测覆盖）

---

## 8. Unit Tests (T1–T10)

`adb-engine/src/test/java/com/adb/engine/TrustLossRecoveryTest.kt`（两分支 byte-identical）：

```text
T1  paired + 无 trust rejection → 不强制配对
T2  paired + pre-TLS 网络失败 → 不设 trust flag，不强制配对
T3  paired + trust rejection → 投影 PAIRING_REQUIRED（metadata 仍 valid，靠 Runtime 观察）
T4  trust loss → key + pubkey 不变
T5  re-pair 成功 → metadata 重写（同 key fingerprint）
T6  re-pair 成功 → trust flag 清除 + metadata valid（auto reconnect）
T7  re-pair 失败 → trust flag 保持，仍 PAIRING_REQUIRED
T8  完整 trust-loss 循环 → key 永不重新生成
T9  trust stale 期间重试 → 投影保持 PAIRING_REQUIRED（非 CONNECT_FAILED，无 connectAuto 循环）
T10 正常 transport loss (READY 后) → 不自动判为 trust loss
SEC metadata 无私钥 / 无 pairing code
→ 11/11 PASS
```

---

## 9. Regression Suites

```text
MASTER_TEST_TOTAL = 143   MASTER_TEST_PASS = 143   MASTER_TEST_FAIL = 0
FEATURE_TEST_TOTAL = 151  FEATURE_TEST_PASS = 151  FEATURE_TEST_FAIL = 0
MASTER_BUILD  = PASS (test + :app:assembleDebug)
FEATURE_BUILD = PASS (test + :app:assembleDebug)
ARCHITECTURE_GATE = PASS (at-architecture-gate: ProductionArchitectureGateTest +
  ArchitectureNegativeTest 全绿；AdbRuntime 仍是 lifecycle owner，UI 只投影)
LOCAL_TCP_REGRESSION = PASS (0.4.1 NormalizationGateTest 8/8 + 架构门全绿)
```

lint：两分支均 3 个**既有** manifest 错误（CoarseFineLocation + ProtectedPermissions x2，
在 adb-only-test-host / adb-engine merged manifest），与本轮改动文件无关，非阻断项。

---

## 10. Security / Invariants

```text
SECURITY_REVIEW = PASS
  metadata 仅 fingerprint+guid+timestamp；logcat 只记 fingerprint/guid/state；
  无 private key / pairing code / 完整 trusted-key 库落盘。
GUID_POLICY_CHANGED = NO (仍 GUID_STORED_BUT_NOT_ENFORCED)
ADB_PROTOCOL_CHANGED = NO
TLS_PROTOCOL_CHANGED = NO (只观察握手结果，不改 TLS 行为)
KEY_GENERATION_CHANGED = NO
V1_CHANGED = NO
LOCAL_TCP_POLICY_CHANGED = NO
```

---

## 11. Verdict

所有 mandatory gate PASS：
- device trust 被移除（Wireless post-TLS rejection 证实）
- local identity + metadata 未变
- AT 检测到 trust loss → NEEDS PAIRING（非误判 CONNECT_FAILED）
- 同 key re-pair → metadata 重写 → auto reconnect → READY
- key 全程未重新生成
- app restart / device reboot → READY，无 re-pair
- 双分支测试全绿，架构门全绿，Local TCP 未受影响

```text
VERDICT = PASS
AT_TRUST_LOSS_RECOVERY_STATUS = CLOSED
AUTO_TRUST_LOSS_DETECTION = YES (post-TLS EOF signature, 非 logcat)
NEXT = AT.0.4.0-WIRELESS-BASELINE-FREEZE
```

**已知遗留（记录，非本轮 FAIL）：**
- `LOCAL_TCP_BYPASS_OF_TRUST = PRESENT`：0.4.1 Local TCP 可用时掩盖 Wireless
  trust loss（既有设计，本轮禁止改 Local TCP policy）。建议后续单独评估：
  当 active transport = LOCAL 但 Wireless trust 丢失时，是否需要在 UI 提示。
