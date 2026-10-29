# AT.REPAIRING-UX-1.CLOSEOUT — Regression + Pairing Metadata Closeout

**Step:** AT.REPAIRING-UX-1.CLOSEOUT
**Date:** 2026-10-30
**Device:** HONOR ALI-AN00, Android 15, `APLMUT4312005935`
**Purpose:** 正式关闭 AT.REPAIRING-UX-1 — 补齐 unit tests / 双分支完整回归 / 实机
restart/reboot persistence 验证，确认 pairing metadata 行为正确，且 0.4.0 与
0.4.1 两分支均无 Wireless ADB 回归。

本轮**不做** trust-loss recovery 产品开发（保留至 AT.TRUST-LOSS-RECOVERY-1）。

---

## 1. Baselines

### master / 0.4.0 (worktree `AndroidTerminal-V040-FIX`)
```text
BRANCH = master
HEAD   = 202ec97280dcda1f29dcdca9ddac98554f401acb   (expected 202ec97 — MATCH)
STATUS = clean (before closeout changes)
```

### feature / 0.4.1 (main worktree `AndroidTerminal-V4-LOCAL-TCP-041`)
```text
BRANCH = feature/local-tcp-v0.4.1
HEAD   = 9f986b06dd5e64b150139f65cf2949546d39287a   (expected 9f986b0 — MATCH)
STATUS = clean (before closeout changes)
```

---

## 2. Diff Audit — Repairing Fix Scope

### master (a3e0e74..202ec97)
```text
adb-engine/src/main/java/com/adb/engine/AdbConsumerBootstrap.kt   (M, +23/-)
adb-engine/src/main/java/com/adb/engine/AdbPairingMetadata.kt     (NEW, +143)
app/src/main/java/com/androidterminal/app/MainActivity.kt         (M, +46/-)
app/src/main/java/com/androidterminal/app/PairActivity.kt         (M, +5/-)
docs/evidence/AT.REPAIRING-UX-1.md                                (NEW)
```

### feature (a6f7a25..9f986b0)
同上 5 文件 + `本轮交付/` 文档轮换（非生产代码）。

```text
MASTER_REPAIR_SCOPE_OK  = YES
FEATURE_REPAIR_SCOPE_OK = YES
```

Wire/TLS/runtime 文件（`adb-engine/src/main/cpp/`、`AdbRuntime.kt`、`AdbEngine.kt`、
`internal/`）在两个 repair commit 中**零改动**。无 Local TCP / requestTcpip /
requestUsb / 38800 改动。

---

## 3. Pairing Metadata Static Contract

`AdbPairingMetadata`（`pairing_meta` 文件，properties 格式）字段：

```text
PAIRING_METADATA_FIELDS =
  paired_key_fingerprint  (SHA-256 64hex of adbkey.pub base64 body)
  device_guid             (GUID from pairing success)
  paired_at               (Unix epoch millis)
```

安全审计：

```text
private key NOT stored            (只存公钥指纹)
certificate NOT duplicated        (不复制 adb_cert.pem)
pairing code NOT stored           (markPaired(fp, guid) 无 code 参数)

PAIRING_METADATA_SECURITY = PASS
```

---

## 4. Fingerprint Binding Contract

`isCurrentKeyPaired() = true` 当且仅当：

```text
stored paired_key_fingerprint == SHA-256(current adbkey.pub bytes)  (case-insensitive)
```

单元测试覆盖（`AdbPairingMetadataTest`，16 用例全 PASS）：

```text
same fingerprint      -> paired        (M2/M3)
different fingerprint -> not paired    (M5/B4)
missing metadata      -> not paired    (M1/M6/B5)
malformed metadata    -> safe fallback (M7/M7b，无 crash)

FINGERPRINT_BINDING_CONTRACT = PASS
```

---

## 5. GUID Binding Policy

`device_guid` 被**持久化**（`markPaired` 写入、`deviceGuid()` 可读），但
`isCurrentKeyPaired()` 的判断**只使用 fingerprint**，不使用 GUID 做 trust 判断。

```text
fingerprint same + GUID differs  -> 仍判定 paired（当前语义）

GUID_BINDING_POLICY = GUID_STORED_BUT_NOT_ENFORCED
```

如实记录，本轮不擅自改（改 GUID 强制语义属于 trust-loss recovery 范围）。

---

## 6. 新增 Unit Tests

文件：`adb-engine/src/test/java/com/adb/engine/AdbPairingMetadataTest.kt`
（两分支 byte-identical）
依赖：`adb-engine/build.gradle.kts` 新增 `testImplementation("junit:junit:4.13.2")`
（test-only 改动，与 `at-engine/build.gradle` 既有写法一致）。

覆盖矩阵：

```text
M1  no metadata -> not paired                    (2 tests)
M2  markPaired writes fp+guid+ts                 (1)
M3  re-instantiation readable (app restart sim)  (1)
M5  key change invalidates old metadata          (1)
M6  missing metadata file -> not paired          (1)
M7  malformed / empty metadata -> safe fallback  (2)
M8  pair failure (markPaired not called) -> no file (1)
M9  timestamp written within [before, after]     (1)
M10 only fp/guid/ts fields; pairing code absent  (1)
I3  markPaired does NOT modify adbkey/adbkey.pub (1)
B4  mismatched fp -> not paired                  (1)
B5  no metadata -> safe UNKNOWN_TRUST probe      (1)
SEC metadata contains no private-key material; fp = 64 lowercase hex (1)
CLR clear() removes file                          (1)
```

M4（device reboot 可读）由实机 Gate C 直接验证（见 §13），非纯 host 模拟。
I1/I2/I4/I5、B1-B3/B6-B8 由实机 Gate A/B/C + 既有 architecture gate 覆盖
（`AdbKeyStore` 生成路径与 `AdbRuntime` lifecycle 在既有架构测试中已 gate）。

```text
IDENTITY_GENERATION_TESTS   = PASS (I3 host + 实机 key-preservation across A/B/C)
PAIRING_METADATA_TESTS      = PASS (16/16)
BOOTSTRAP_STATE_TESTS       = PASS (B4/B5 host + 实机 A/B/C 状态投影)
```

---

## 7. 完整测试套件 — master (0.4.0)

worktree `AndroidTerminal-V040-FIX`（已补 local.properties），
`.\gradlew.bat test :adb-engine:assembleDebug :app:assembleDebug`：

```text
AdbPairingMetadataTest      16  (debug + release)
ArchitectureNegativeTest      6
ProductionArchitectureGateTest 7
AdbCliArgsTest               27  (debug + release)
ATAndroidTest                 5  (debug + release)
ATExecutionContractTest       3  (debug + release)
ATSessionContractTest         3  (debug + release)

MASTER_TEST_TOTAL = 121
MASTER_TEST_PASS  = 121
MASTER_TEST_FAIL  = 0
MASTER_BUILD      = PASS (BUILD SUCCESSFUL)
```

## 8. 完整测试套件 — feature/local-tcp-v0.4.1 (0.4.1)

主 worktree，同样任务集：

```text
AdbPairingMetadataTest      16  (debug + release)
ArchitectureNegativeTest      6
ProductionArchitectureGateTest 7
NormalizationGateTest         8   (0.4.1 独有 — Local TCP normalization)
AdbCliArgsTest               27  (debug + release)
ATAndroidTest                 5  (debug + release)
ATExecutionContractTest       3  (debug + release)
ATSessionContractTest         3  (debug + release)

FEATURE_TEST_TOTAL = 129
FEATURE_TEST_PASS  = 129
FEATURE_TEST_FAIL  = 0
FEATURE_BUILD      = PASS (BUILD SUCCESSFUL)
```

0.4.1 独有的 `NormalizationGateTest` (8) + `:at-architecture-gate` 架构门全 PASS
→ forward-port pairing lifecycle 修复未破坏 0.4.1 Local TCP 代码。

```text
LOCAL_TCP_REGRESSION = PASS
```

---

## 9. Lint

两分支 `.\gradlew.bat lint` 均报**相同的 3 个既有 manifest 错误**（非本轮引入）：

```text
1. adb-engine merged manifest: CoarseFineLocation
   (ACCESS_FINE_LOCATION 需同时声明 COARSE — 既有 manifest 问题)
2-3. adb-only-test-host manifest: ProtectedPermissions x2
    (test host 的系统权限声明 — 既有问题)
```

```text
MASTER_LINT  = PRE_EXISTING_FAILURES (3 errors, 与 repair 文件无关)
FEATURE_LINT = PRE_EXISTING_FAILURES (3 errors, 与 repair 文件无关)
```

lint findings 中**零条**指向 MainActivity / PairActivity /
AdbConsumerBootstrap / AdbPairingMetadata。非 closeout 阻断项（修复 manifest
权限属于独立生产改动，超出本轮 test/docs-only 边界）。

---

## 10. Cross-Branch Semantic Equivalence

repair 三核心文件 + 新测试 + gradle 依赖：

```text
AdbPairingMetadata.kt   : IDENTICAL (byte-identical)
AdbConsumerBootstrap.kt : IDENTICAL (byte-identical)
MainActivity.kt         : IDENTICAL (byte-identical)
AdbPairingMetadataTest.kt + build.gradle.kts : IDENTICAL
```

fingerprint 算法、bootstrap pairing 判断、pair-success metadata 写入、
auto-reconnect 路径在两分支完全一致。

```text
REPAIRING_FIX_SEMANTIC_EQUIVALENCE = YES
```

---

## 11. Architecture / Lifecycle Gate

```text
LIFECYCLE_OWNER = AdbRuntime
AdbConsumerBootstrap = READ-ONLY 状态投影 + bootstrap 入口
UI (MainActivity)    = renderer + retryAdb 路由（PAIRING_REQUIRED -> 配对引导，
                       非 blind connectAuto）
```

静态确认：`bootstrapState()` 注释明示 "READ-ONLY consumer PROJECTION of
AdbRuntime state"；`isCurrentKeyPaired()` 只读 identityDir，不持有 lifecycle；
UI 不存 canonical paired state（每次从 bootstrap 投影）。

```text
ARCHITECTURE_PROJECTION_GATE = PASS
```

---

## 12. 实机 Gate — 已有 paired identity

PC preflight：`$env:ADB_MDNS_AUTO_CONNECT="0"`；`adb kill-server/start-server`；
USB present（`APLMUT4312005935 device`）；PC Wireless absent。V1 未触碰。
设备已装 0.4.1（versionName=0.4.1，REPAIRING-UX-1 构建），identity 已配对。

### Gate A — Existing Paired Identity (launch, no re-pair)
```text
force-stop -> relaunch -> tap ADB retry -> ADB: READY
EXISTING_IDENTITY_PAIRING_PROMPT = NO
EXISTING_IDENTITY_READY          = PASS
```

### Gate B — Force-Stop / Relaunch
```text
force-stop -> relaunch -> ADB: READY, 无 pairing prompt
APP_RESTART_READY        = PASS
APP_RESTART_PAIRING_PROMPT = NO
```

### Gate C — Device Reboot
```text
reboot -> USB 回连 -> settings put global adb_wifi_enabled 1 (TLS listener *:37551)
-> launch -> tap ADB -> ADB: READY, 无 pairing prompt
DEVICE_REBOOT_READY        = PASS
DEVICE_REBOOT_PAIRING_PROMPT = NO
```

---

## 13. Persistence Verification

```text
KEY_FP_BEFORE             = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
KEY_FP_AFTER_APP_RESTART  = c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
KEY_FP_AFTER_DEVICE_REBOOT= c46e4eb26a25970277d6a42c74b1c40a620038b333fbd5c0a54be4d454a20dea
IDENTITY_PERSISTENCE      = PASS (all equal)
```

```text
META_FP_BEFORE             = paired_key_fingerprint=3a46ca14d66668cae40319dc8dda8ac40b4e5e8a1f69ec7365db57e937e572c4
                             device_guid=adb-APLMUT4312005935-J4nb5z
                             paired_at=1793297606919
META_FP_AFTER_APP_RESTART  = (identical content)
META_FP_AFTER_DEVICE_REBOOT= (identical content)
PAIRING_METADATA_PERSISTENCE = PASS (fingerprint + guid 逻辑内容一致)
```

---

## 14. Loop Gates

```text
PAIRING_LOOP = ABSENT
  (boot 后 logcat 无 pairing 活动记录；READY 达成后未再触发任何 pairing)

UNPAIRED_CONNECT_LOOP = ABSENT
  (静态 + 测试：retryAdb() 在 PAIRING_REQUIRED 时 early-return 并引导「配对」按钮，
   不进入 ensureReady/connectAuto 循环 — MainActivity.kt:302-311；
   B4/B5 单测确认 unpaired 状态投影)
```

---

## 15. Backward Compatibility (UNKNOWN_TRUST)

已有 identity 但 metadata 缺失的场景由单测覆盖（M1/M6/B5：
`isCurrentKeyPaired() = false` → `bootstrapState() = PAIRING_REQUIRED`），
行为与 REPAIRING-UX-1 既定设计一致，本轮不改变策略。

```text
UNKNOWN_TRUST_BACKWARD_COMPAT = PASS
```

---

## 16. Trust-Loss — Explicitly Deferred

本轮未执行 "Forget paired device"，未重新配对。

```text
TRUST_LOSS_RECOVERY = DEFERRED_TO_AT.TRUST-LOSS-RECOVERY-1
```

（不计入本轮 FAIL。）

---

## 17. Security Review

新增 metadata / logging 审计：

```text
metadata 仅含：公钥指纹 (64hex) / guid / 时间戳
logcat 中 pairing 相关日志仅 AdbDebuggingManager 系统日志
无私钥 / 完整证书私料 / 6 位配对码 落盘或入日志

SECURITY_REVIEW = PASS
```

---

## 18. Release Boundary

即使全部 PASS，本轮**不 release** — 0.4.1 仍有
Local TCP direct promotion / normalization policy / final release validation 未完成。

```text
PRODUCT_CODE_CHANGED     = NO  (本轮仅 test + test dep + docs)
LOCAL_TCP_POLICY_CHANGED = NO
ADB_PROTOCOL_CHANGED     = NO
TLS_PROTOCOL_CHANGED     = NO
V1_CHANGED               = NO
```

---

## 19. Verdict

全部 mandatory gates PASS：
双分支测试 121/129 全过、build 全过、架构 gate 过、
实机 Gate A/B/C 全过（READY 且无 pairing prompt）、
identity + metadata 持久化一致、无 pairing/connect 循环、
cross-branch 语义等价、security 过。

lint 3 错误为**既有 manifest 问题**（repair 文件零涉及），非本轮引入，
不阻断 closeout（修复需独立生产改动，超出 test/docs-only 边界）。

```text
VERDICT = PASS
AT_REPAIRING_UX_1_STATUS = CLOSED
NEXT = AT.TRUST-LOSS-RECOVERY-1
```
