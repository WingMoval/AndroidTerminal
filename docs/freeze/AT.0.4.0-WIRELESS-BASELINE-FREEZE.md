# AT.0.4.0 — Wireless ADB Stable Baseline Freeze

> 性质：**WIRELESS BASELINE FREEZE / SSOT**。
> **非**功能开发。DOCS + EVIDENCE ONLY（本轮唯一 commit 为 docs-only）。
> PRODUCTION_CODE_CHANGED = NO / TEST_CODE_CHANGED = NO / BUILD_CHANGE = NO /
> LOCAL_TCP_CHANGED = NO / V1_CHANGED = NO。

---

## 1. Purpose

Wireless ADB 主线已完成并通过实机验证：

```text
AT.KEY-REGISTRATION-FLOW-1     → identity lifecycle root cause confirmed
AT.REPAIRING-UX-1              → fresh identity / re-pair lifecycle fixed
AT.REPAIRING-UX-1.CLOSEOUT     → regression + restart/reboot PASS
AT.TRUST-LOSS-RECOVERY-1       → device-side Forget → same-key re-pair → READY → CLOSED
```

本轮 **不再修改 Wireless ADB 产品逻辑**，把当前 master 正式冻结为后续
**0.4.1 Local TCP 开发**的 **Wireless 基准线（oracle）**。

---

## 2. Freeze Identity

```text
STEP                          = AT.0.4.0-WIRELESS-BASELINE-FREEZE
BRANCH                        = master
VALIDATED_SOURCE_HEAD         = a92eab5   (a92eab5a1550c839fc3f39628921c86a8a5c6c48)
FREEZE_COMMIT                 = ef0dbb9   (docs-only, after validation)
WORKTREE_BEFORE               = CLEAN (0 modified / 0 untracked)
WORKTREE_AFTER                = CLEAN (docs + 本轮交付 only, then committed)
```

> **重要区分**：`VALIDATED_SOURCE_HEAD = a92eab5`（被验证的源码基线）与
> `FREEZE_COMMIT`（验证之后落盘的 docs-only commit）是**两个不同 commit**。
> 本 SSOT 提交后，`FREEZE_COMMIT` 即成为后续 0.4.1 的 "Wireless baseline frozen at" 指针，
> 而 `a92eab5` 是被冻结验证的源码 HEAD。二者不得混为一谈。

---

## 3. Baseline Integrity

```text
BRANCH   = master
HEAD     = a92eab5  (EXPECTED a92eab5 → MATCH)
STATUS   = CLEAN
AUDIT    = a92eab5..HEAD 为空（HEAD 即 expected，无额外 production change）
```

---

## 4. Historical Evidence Chain（SSOT，不得重新解释已 CLOSED 根因）

| Step | Status | 关键结论 |
|---|---|---|
| AT.KEY-REGISTRATION-FLOW-1 | CLOSED | identity lifecycle root cause confirmed（密钥注册流程 = identity 生命周期根因） |
| AT.REPAIRING-UX-1 | CLOSED | fresh identity / re-pair 生命周期修复 |
| AT.REPAIRING-UX-1.CLOSEOUT | CLOSED | regression + restart/reboot PASS |
| AT.TRUST-LOSS-RECOVERY-1 | CLOSED | device-side Forget → same-key re-pair → READY；trust-rejection signature = TLS_OK + post-TLS pre-CNXN EOF |

证据位置：`docs/evidence/AT.REPAIRING-UX-1.md`、`docs/evidence/AT.REPAIRING-UX-1.CLOSEOUT.md`、
`docs/evidence/AT.TRUST-LOSS-RECOVERY-1.md`。

```text
WIRELESS_ROOT_CAUSE_CHAIN = CLOSED
```

---

## 5. Architecture Boundary（FROZEN）

```text
AdbRuntime
  = unique ADB lifecycle owner
    (owns: connection, pairing lifecycle, trust-rejected observation,
     reconnect policy, READY/NEEDS PAIRING state)

AdbConsumerBootstrap
  = state projection
    (bootstrapState / bootstrapStateForDir → PAIRING_REQUIRED | CONNECTING | READY | ...)

UI (MainActivity / ATAndroidAdbControl)
  = rendering / user action only
    (renders projected state; 用户动作: ADB connect / 配对 / 关ADB)

ADB Engine (C++: AdbConnection / AdbClient / AdbStreamHub / tls_transport)
  = protocol / provider capability
    (TCP / STLS / TLS / CNXN / AUTH / streams; 观察 trust-rejection signal)
```

冻结的 ownership 约束：

```text
NO  Activity owning canonical trust state
NO  Backend owning pairing policy
NO  AT Engine depending on ADB lifecycle details
```

```text
ARCHITECTURE_GATE = PASS
  (ProductionArchitectureGateTest 7/7 + ArchitectureNegativeTest 6/6 = 0 failures)
```

---

## 6. Frozen Runtime Semantics（Wireless ADB 状态机）

```text
1. No identity
   → generate identity (keypair, persisted)
   → PAIRING_REQUIRED

2. Identity exists, but current fingerprint not paired
   → PAIRING_REQUIRED

3. Paired identity
   → normal Wireless connect (mDNS → TCP → STLS → TLS → CNXN → ONLINE)
   → READY

4. Device-side trust lost
   → TLS_OK
   → post-TLS pre-CNXN EOF (device closes before sending CNXN)
   → Runtime trust-rejected observation (AdbConnection → AdbClient → AdbRuntime)
   → PAIRING_REQUIRED  (not generic CONNECT_FAILED)

5. Re-pair
   → SAME identity (key NOT regenerated)
   → metadata refreshed (paired_at updated, fingerprint unchanged)
   → auto reconnect
   → READY
```

---

## 7. Frozen Trust-Rejection Signature（0.4.1 Wireless Oracle）

```text
WIRELESS_TRUST_REJECTION_SIGNATURE =
  TCP connected
  + STLS success (RX_STLS + TX_STLS)
  + client TLS handshake success (TLS_OK)
  + device closes BEFORE post-TLS CNXN (post-TLS read EOF / clean FIN)
```

**产品判断不得依赖**：

```text
adbd logcat
CERTIFICATE_VERIFY_FAILED text
```

（BoringSSL 客户端在 peer 证书被拒时 `SSL_do_handshake rc=1` 成功，
客户端侧 `CertificateRejected` 永不触发；拒绝只发生在设备端 TLS 握手完成**之后**、
发送 CNXN **之前**。因此只能从客户端可观察结果推断：TLS_OK + post-TLS read failure。）

> 本签名成为后续 0.4.1 的 Wireless oracle：任何 Local TCP / transport-selection
> 改动都不得破坏该签名在 Wireless 路径上的判定。

---

## 8. Frozen Pairing Invariants

```text
PAIRING MUST NOT regenerate identity

PAIRING_PUBLIC_KEY
  == CURRENT_IDENTITY_PUBLIC_KEY

PAIR SUCCESS
  → metadata fingerprint bound to current key
  (paired_key_fingerprint / device_guid / paired_at)

TRUST LOSS
  → key remains unchanged
  (identity_private_file SHA256 + pairing public fingerprint 全程不变)

REPAIR
  → same key reused
  → metadata rewritten (paired_at 更新, fingerprint 不变)
```

---

## 9. Real-Device Evidence（本轮非破坏 smoke，无 fresh install / Forget）

```text
Device            = HONOR ALI-AN00, Android 15, APLMUT4312005935
PC_WIRELESS       = ABSENT (PC USB only, ADB_MDNS_AUTO_CONNECT=0)
Identity baseline = IDENTITY_PRIVATE_FILE_SHA256 = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
                    PAIRING_PUBLIC_KEY_FINGERPRINT = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
                    device_guid = adb-APLMUT4312005935-J4nb5z
```

### Wireless smoke（existing paired identity → launch → discovery → connect → READY）

```text
WIRELESS_DISCOVERY = PASS  (mDNS: adb-APLMUT4312005935-J4nb5z:37019 @ 192.168.137.57)
WIRELESS_TCP       = PASS  ([C02] TCP_CONNECTED 127.0.0.1:37019)
WIRELESS_STLS      = PASS  ([C04] RX_STLS + [C05] TX_STLS)
WIRELESS_TLS       = PASS  ([C07] TLS_OK)
WIRELESS_CNXN      = PASS  ([C12] REMOTE_FEATURES + [C13] ONLINE v0x01000001 max_payload=1048576)
WIRELESS_READY     = PASS  (ADB: READY)
PAIRING_PROMPT_ON_WIRELESS = NO
```

### Restart smoke（force-stop → relaunch）

```text
APP_RESTART_READY = PASS
PAIRING_PROMPT_ON_NORMAL_RESTART = NO
IDENTITY after restart = UNCHANGED
```

### Reboot smoke（device reboot → re-enable WD → launch → connect）

```text
DEVICE_REBOOT_READY = PASS  (TLS_OK → ONLINE → ADB: READY)
PAIRING_PROMPT_ON_DEVICE_REBOOT = NO
IDENTITY after reboot = UNCHANGED
```

### Identity stability

```text
IDENTITY_PRIVATE_FILE_SHA256_BEFORE              = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
IDENTITY_PRIVATE_FILE_SHA256_AFTER_APP_RESTART   = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
IDENTITY_PRIVATE_FILE_SHA256_AFTER_DEVICE_REBOOT = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
→ ALL_EQUAL = YES

PAIRING_PUBLIC_KEY_FINGERPRINT_BEFORE = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
PAIRING_PUBLIC_KEY_FINGERPRINT_AFTER  = 3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
→ ALL_EQUAL = YES
```

> 字段名严格区分：`IDENTITY_PRIVATE_FILE_SHA256`（private-key-file SHA256）与
> `PAIRING_PUBLIC_KEY_FINGERPRINT`（adbkey.pub-derived pairing fingerprint），
> 不再混用 `KEY_FP`。

---

## 10. Test + Build + Lint

```text
TEST_TOTAL = 143   TEST_PASS = 143   TEST_FAIL = 0
BUILD_ADB_ENGINE = PASS  (:adb-engine:assembleDebug)
BUILD_APP_DEBUG  = PASS  (:app:assembleDebug)
BUILD_APP_RELEASE= PASS  (:app:assembleRelease, NOT published)
LINT = PRE_EXISTING_NON_BLOCKING
LINT_NEW_FINDINGS = 0
ARCHITECTURE_GATE = PASS
```

Lint 既有 3 项（非本轮引入，全部位于 adb-only-test-host / adb-engine merged manifest）：

```text
1. CoarseFineLocation      (adb-engine merged manifest)
2. ProtectedPermissions    (adb-only-test-host WRITE_SECURE_SETTINGS)
3. ProtectedPermissions    (adb-only-test-host BIND_ACCESSIBILITY_SERVICE)
```

---

## 11. Freeze Validation Artifact（非发布物）

```text
APK_PATH     = D:\codebank\AndCode\AndroidTerminal-V040-FIX\app\build\outputs\apk\debug\app-debug.apk
APK_SHA256   = 6F807990D32B4F3AC44B124626571434C3D41AE2E64B136D89D8BB35DB1D5F84
APK_SIZE     = 8475090 bytes (8.08 MB)
VERSION_NAME = 0.4.0
VERSION_CODE = 2
CERT_SHA256  = 56586d91ebe30843e44840524d4d7d2b045ae34a006f22eee7c726f65bee41b9
```

> 此 APK **仅**属 FREEZE VALIDATION ARTIFACT，**不是**发布物。
> **不得**覆盖历史上已有的旧 0.4.0 发布物。

---

## 12. What Is Frozen

```text
Wireless ADB discovery (mDNS endpoint discovery)
Pairing + Same-key pairing
Identity generation / persistence
Pairing metadata (fingerprint + guid + timestamp)
Fresh-install NEEDS PAIRING
Automatic reconnect after pairing
Trust-loss detection (TLS_OK + post-TLS pre-CNXN EOF)
Same-key re-pair
TCP / STLS / TLS / ADB CNXN / READY
App restart recovery
Device reboot recovery
AdbRuntime = lifecycle owner; Bootstrap = projection; UI = rendering
Wireless trust-rejection signature (oracle)
Pairing invariants (no key regen; public key == current identity)
```

---

## 13. What Is NOT Frozen

```text
Local TCP / tcpip:38800 policy
requestUsb normalization
transport-selection policy
Local/Wireless priority
final public AT API
Terminal Emulator / ScreenState
AG integration
0.4.1 release
```

尤其：

```text
LOCAL_TCP_BYPASS_OF_TRUST = PRESENT
```

属于 0.4.1 已知交互，**不阻塞**本次 Wireless baseline freeze。

---

## 14. Known Non-Blocking Debt（记录，不修，不得写成 Wireless failure）

```text
D1  adb-only-test-host lint: 3 pre-existing manifest findings
    (CoarseFineLocation + ProtectedPermissions x2)
D2  GUID_STORED_BUT_NOT_ENFORCED
D3  0.4.1 LOCAL_TCP_BYPASS_OF_TRUST = PRESENT
    (Local TCP 同 key 无 TLS 信任检查，可用时掩盖 Wireless trust loss)
D4  Local TCP normalization policy unresolved
```

---

## 15. Verdict

所有 mandatory gate PASS：

```text
test suite (143/143)            = PASS
build (adb-engine + app debug + release) = PASS
zero new lint findings          = PASS (0)
architecture gate               = PASS
Wireless READY smoke            = PASS
restart READY                   = PASS
reboot READY                    = PASS
identity stable                 = PASS
worktree clean                  = PASS
```

```text
VERDICT = PASS
AT_040_WIRELESS_BASELINE_STATUS = FROZEN
TAG_CREATED = NO   (等 Controller 决定是否打 freeze tag)
PUSHED = NO
RELEASED = NO

NEXT = RESUME feature/local-tcp-v0.4.1 → AT.LOCAL-TCP-DIRECT-PROMOTION-1
```
