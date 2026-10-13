# AT.V3-DEBT-0 — V2 Verified Debt Inventory & First-run Closure Plan

**V2 已验证能力债务盘点与首次使用闭环规划**

- 日期：2026-10-13
- 性质：**DOCS / AUDIT ONLY**（不修改 production code；不重新实现 Pairing；不 push）
- 基线：V3 `c56a3ba`（clean）/ V2 `726a441`（43 会前 dirty，零写入）
- 设备：不要求真机（本轮复用 V2 已验证 evidence；仅静态审计 V3 源码）

---

## 1. Objective

把 V2 中**已研究 / 已真机实验 / 已 VERIFIED / 已 CLOSED / 已证明可行**、但 V3 尚未迁移 /
只迁入未 parity / 只 COMPILED / 未接入 Runtime / 未接入 Host / 未形成产品闭环的能力，
全部盘清楚，并回答：

> **"V2 到底还欠 V3 哪些债？"**（而不是只回答"Pairing 还没做"）

特别关注：Pairing / Identity / First-run Bootstrap / Wireless Debugging bootstrap /
Reconnect lifecycle / Human-facing connection control。

## 2. Baseline

```text
V3 HEAD  = c56a3ba（AT.V3-2.3: record audit commit hash）
V3 status = CLEAN（本轮开始）
V2 HEAD  = 726a441
V2 status = 43 项会前 dirty（本轮 V2 零写入）
设备     = 本轮不接入（历史 evidence 足够；静态源码审计）
```

## 3. Audit Method

- 只读 V2 最终 VERIFIED/CLOSED 文档（优先级：final 版 > 早期 PARTIAL 版）。
- 静态审计 V3 真实源码（`:adb-engine` native + Kotlin、`:app` manifest/MainActivity）。
- V2↔V3 关键文件 `git diff --no-index` + MD5 比对（确定"完全一致 / 有改写"）。
- **不修改 production source**；不做 fresh-install 真机（后续 slice）。
- 允许 compile-only / static validation（本轮不需要，pairing 与 connect 均已编译）。

## 4. V2 Final Verified Baseline（只读 Oracle）

| 能力 | V2 最终状态 | 证据 |
|---|---|---|
| Pairing protocol primitive（SPAKE2+AES-128-GCM+HKDF+PeerInfo+TLS EKM "adb-label"） | **VERIFIED / CLOSED** | `AT.C-1_Core_Pairing_Primitive.md`（§5/§6/§17） |
| E4：fresh Identity A → pair 前 connect FAIL → pair() → 设备 trust A → 同 A connectAuto → uid=2000(shell) | **PASS**（真机闭环 2026-10-10，热点网络） | AT.C-1 §13 |
| android_pubkey 编码修复（`len LE + n0inv LE + n[256] LE + rr[256] LE + exp LE`，524B） | **VERIFIED 修复**（曾致 CERTIFICATE_VERIFY_FAILED） | AT.C-1 §6.3 #6 |
| Pairing endpoint discovery（`_adb-tls-pairing._tcp`） | **E1 VERIFIED**（发现+resolve 39237，ss 交叉印证；弹窗关→service 消失） | AT_FIRST_RUN §5.5 |
| Pairing code Accessibility（`com.android.settings:id/pairing_code` 6 位码；`id/ip_addr` 端口） | **E2 VERIFIED**（code=847563 稳定读取） | AT_FIRST_RUN §6.3 |
| WRITE_SECURE_SETTINGS bootstrap（Manifest 声明 + shell pm grant + `adb_wifi_enabled` 0/1 真控制监听+端口轮换） | **E3 VERIFIED（机制层）** | AT_FIRST_RUN §9.4 |
| Persistent identity 文件（adbkey/adbkey.pub/adb_cert.pem，loadOrCreate） | **EXISTS / VERIFIED**（文件级持久） | AT.C-1 §4、AT_FIRST_RUN §8.1 |
| manual reconnect primitive（connect 可重调用；disconnect 先拆旧 session） | **EXISTS / TEST_CONFIRMED**（T6 disconnect+reconnect；T8 失败后重连） | AT.RUNTIME-1 §H |
| **automatic reconnect mechanism** | **MISSING in V1**（后由 2D 实现：R1-R7 VERIFIED） | AT.RUNTIME-1 §H + 2D |
| Automatic recovery（loss→invalidate→rediscover→reconnect，no-replay） | **2D VERIFIED**（R1-R7 = 30 checks 0 failed） | AT.RUNTIME-2D |
| Reconnect button / manual reconnect UI / background recovery / human status | **V2 从未实现 / 无运行时证据**（仅讨论） | AT.RUNTIME-1、路线纪要 §12/§16 |
| Identity lifecycle（fingerprint/export/import/re-pair decision/invalidation） | **V2 仅设计**（无运行时实现） | AT_FIRST_RUN §8.3、路线纪要 §7 |
| PairingCoordinator / first-run state machine | **V2 仅设计**（状态机 proposal §10，未实现） | AT_FIRST_RUN §10 |
| AccessibilityService / Settings navigation / pairing UI | **V2 仅 diagnostic probe**（PairingCodeProbeService 已删） | AT_FIRST_RUN §6.2-6.3 |

## 5. V3 Current State（静态审计实测）

### 5.1 Pairing native（`:adb-engine`）

```text
adb/pairing/pairing_aes_gcm.{h,cpp}    = PRESENT（与 V2 逐字节 IDENTICAL）
adb/pairing/pairing_auth.{h,cpp}       = PRESENT（IDENTICAL）
adb/pairing/pairing_client.{h,cpp}     = PRESENT（IDENTICAL；PairingStatus 结构化错误 13 态）
adb/pairing/pairing_packet.h           = PRESENT（IDENTICAL）
adb/pairing/pairing_peerinfo.h         = PRESENT（IDENTICAL）
adb/crypto/rsa_2048_key.{h,cpp}        = PRESENT（IDENTICAL；android_pubkey 524B 修复已含）
adb/tls/tls_connection.cpp             = PRESENT（EKM "adb-label"；AT.C-1 §6.3 #4 修复）
```

**V2↔V3 MD5 比对：10/10 文件 IDENTICAL（pairing 8 个 + crypto 2 个）。**

### 5.2 Pairing JNI / Kotlin

```text
jni/adb_engine_jni.cpp Java_..._AdbNative_pair  = PRESENT（完整；AdbKeyStore(keys)+PairingClient.pair；
  返回 "status=..;success=..;guid=..;error=.." 结构化字符串）
internal/AdbNative.kt  external fun pair(...)   = PRESENT（编译）
AdbEngine.pair(host,port,code,keyDir)           = PRESENT（解析为 AdbPairingResult{success,deviceGuid,error}）
```

### 5.3 Identity

```text
adb/client/adb_key_store.{h,cpp}  loadOrCreate  = PRESENT（文件级持久：adbkey/adbkey.pub/adb_cert.pem）
JNI ClientFor(key_dir)  identity 复用逻辑        = PRESENT（keyDir 相同→复用 g_client；不同→重建。
   AT.V3-2 修复：避免错误复用 stale client/identity）
identity lifetime 跨 connection：同一 keyDir 的 connectAuto/connect 复用同一身份 = 已具备（AT.V3-2.3 真机）
```

### 5.4 Runtime / discovery / state

```text
AdbRuntime（AT.V3-2.3）connectAuto/connect/disconnect/onOperationResult  = PRESENT（post-trust VERIFIED）
AdbRuntimeState = DISCONNECTED/CONNECTING/READY/CONNECT_FAILED/ENDPOINT_NOT_FOUND/
  USER_ACTION_REQUIRED/TRANSPORT_LOST/RECOVERING/RECOVERY_FAILED
  → 无 PAIRING_REQUIRED / AUTH_FAILURE / IDENTITY_* 状态
AdbRuntime 无 pair() / identity() / discoverPairing() / ensureIdentity() 方法
AdbDiscoveryBackend / AdbAndroidNsdDiscoveryBackend  = 仅 _adb-tls-connect._tcp（SERVICE_TYPE 硬编码）
  → 无 _adb-tls-pairing._tcp；无 serviceType 维度
```

### 5.5 App / Host

```text
app/AndroidManifest.xml：无 WRITE_SECURE_SETTINGS；无 BIND_ACCESSIBILITY_SERVICE；无任何服务/权限
app/src/main：无 MainActivity 之外的 pairing/accessibility/settings 导航代码（DEV_PREVIEW Local 终端）
```

## 6. Debt Classification Rules

```text
A. V2_VERIFIED_NOT_MIGRATED         V2 真机 VERIFIED，V3 完全没有
B. V2_VERIFIED_MIGRATED_NOT_PARITY   V2 VERIFIED，V3 已迁入，但 V3 无真机 parity
C. V2_VERIFIED_MECHANISM_NOT_PRODUCTIZED  机制真机证明，但 V2/V3 都未形成最终产品流程
D. V2_DESIGN_ONLY                   V2 只设计/讨论，无运行时证据
E. V3_NEW_REQUIREMENT               V2 从未做过，是 V3 新需求
F. ALREADY_CLOSED_IN_V3             已在 V3 完整关闭，不再列为 debt
```

## 7. Pairing Debt（逐层拆）

| # | 层 | V2 status | V3 current | Classification |
|---|---|---|---|---|
| P1 | Pairing protocol primitive（SPAKE2/AES-GCM/HKDF/PeerInfo/EKM） | VERIFIED | native IDENTICAL 迁入；真机 parity 未跑 | **B** V2_VERIFIED_MIGRATED_NOT_PARITY |
| P2 | android_pubkey regression fix | VERIFIED | 修复已随迁移（rsa_2048_key.cpp IDENTICAL） | **F**（已随迁入；parity 未真机 → 并入 P1） |
| P3 | Pairing JNI/Kotlin surface（AdbEngine.pair / AdbPairingResult） | VERIFIED（E4 走 Core harness） | PRESENT（编译；真机 pair() 未跑） | **B** |
| P4 | Pairing endpoint discovery（`_adb-tls-pairing._tcp`） | E1 VERIFIED | **MISSING**（discovery 仅 connect） | **A** V2_VERIFIED_NOT_MIGRATED |
| P5 | Pairing code Accessibility（`com.android.settings:id/pairing_code`） | E2 VERIFIED | **MISSING**（无 Accessibility service） | **A** |
| P6 | Pairing lifecycle orchestration（PairingCoordinator） | 设计 only（proposal §10） | **MISSING** | **D** V2_DESIGN_ONLY |
| P7 | Pairing state model（PAIRING_REQUIRED/PAIRING/PAIRED/WAITING_FOR_PAIRING_INPUT） | 设计 only | **MISSING**（AdbRuntimeState 无这些态） | **D** |
| P8 | Untrusted / auth-failure detection | 设计（AUTH_FAILURE 预留） | **MISSING**（无 auth-failure 分类；untrusted connect 表现为 generic CONNECT_FAILED/TRANSPORT_ERROR） | **D**（V2 也仅设计） |
| P9 | Pairing success → connectAuto handoff | E4 真机证明同 identity 复用 | 机制可行（同 keyDir 复用 identity），但无 Runtime 编排 | **C** V2_VERIFIED_MECHANISM_NOT_PRODUCTIZED |
| P10 | Re-pair decision | 设计 | MISSING | **D** |
| P11 | Identity invalidation | 设计 | MISSING | **D** |
| P12 | Identity persistence（文件） | VERIFIED | loadOrCreate PRESENT；跨 connection 复用 VERIFIED | **F**（identity 文件机制已闭环） |
| P13 | Human pairing guidance / manual code fallback | 设计（手工输入必做） | MISSING | **D**（manual fallback 是长期契约，非 V2 debt） |
| P14 | Accessibility automatic code acquisition（生产） | E2 probe VERIFIED | MISSING | **A**（probe 已验证，生产未做） |

> **P1 是 V3 pairing 的唯一"迁移 parity"债**：native/JNI/Kotlin 全在且与 V2 一致，只差 V3 真机
> E4（fresh identity → pair → trust → connectAuto → uid=2000）。P4/P5 是 V2 VERIFIED 但 V3 未迁。

## 8. Identity Debt

```text
I1 Identity 文件持久（loadOrCreate）        V2 VERIFIED → V3 PRESENT（KEYSTORE 文件机制）    F（已闭环）
I2 同一 identity 跨 connection 复用         V2 VERIFIED → V3 PRESENT（keyDir 复用 + AT.V3-2.3 真机） F
I3 Identity lifecycle 抽象（ensure/fingerprint/state/re-pair/invalidation）
                                            V2 设计 only → V3 MISSING                       D（V2 非 VERIFIED debt）
I4 Controlled provisioning（export/import） V2 设计 only（部署策略）→ V3 MISSING              D
I5 Device-trust persistence 区分：
   - identity 文件持久 ≠ device trust 持久（device trust 存设备 authorized-keys，卸载/清数据不删设备侧）
   - "忘记无线调试设备" 是设备侧撤销；identity 文件仍在 → 需 re-pair
                                            V2 记录 → V3 无表达                                 D
```

## 9. First-run Bootstrap Debt

```text
F1 fresh install → persistent identity create  V2 机制 VERIFIED → V3 loadOrCreate PRESENT；无 Runtime
                                               identity lifecycle 包装                            B/D
F2 进入 Wireless Debugging pairing 状态（Settings 导航） V2 设计 → V3 MISSING                     D
F3 discover pairing endpoint                   E1 VERIFIED → V3 MISSING                            A
F4 获取 6 位 code（Accessibility 或手工）      E2 VERIFIED → V3 MISSING（Accessibility）/ D（手工 fallback）
F5 pair 当前 persistent identity               E4 VERIFIED（Core）→ V3 机制在但无编排/无真机         B
F6 device trust → connectAuto                   E4 VERIFIED → V3 post-trust 已闭环；handoff 无编排   C
F7 FIRST_RUN_ADB_BOOTSTRAP 完整 Gate            未定义 Gate → 本轮定义（§38 任务要求）               （规划）
```

## 10. Wireless Debugging Bootstrap Debt

```text
W1 WD readiness（detect+report）               V2 2B VERIFIED → V3 AdbRuntime.readiness VERIFIED    F
W2 WD user-action-required                      V2 2B/2D → V3 USER_ACTION_REQUIRED VERIFIED        F
W3 WRITE_SECURE_SETTINGS manifest declaration  E3 VERIFIED（前置=声明）→ V3 MISSING                 A
W4 first successful shell → pm grant bootstrap E3 机制 VERIFIED → V3 MISSING                       A
W5 grant verification                          E3 机制 → V3 MISSING                                 A
W6 adb_wifi_enabled controller（auto enable）  E3 机制 VERIFIED → V3 MISSING（无 WRITE_SECURE_SETTINGS） A
W7 automatic WD enable on next launch          设计 only → V3 MISSING                              D
W8 failure fallback to manual enable           V2 2B USER_ACTION_REQUIRED → V3 有 USER_ACTION_REQUIRED F
W9 Settings navigation                         设计 → V3 MISSING                                    D
W10 OEM portability（HONOR 已验证）            E3 真机 HONOR → V3 无控制层                          C（机制已验证，产品化未做）
```

> **AUTO_WD_BOOTSTRAP Gate（独立于 Pairing Gate，§39）**：identity trusted + shell 可用 +
> WRITE_SECURE_SETTINGS bootstrap 完成后 → WD OFF → app enable → listener → discovery →
> connectAuto → READY；失败 fallback = USER_ACTION_REQUIRED。本 Gate 可晚于 Pairing。

## 11. Reconnect Debt（重新核查）

```text
V3 已具备（AT.V3-2.3，F 类非 debt）：
  bounded automatic recovery（maxAttempts/backoff）
  single recovery owner
  RECOVERY_FAILED 有界失败态
  dynamic endpoint rediscovery
  transport loss → invalidate → rediscover → reconnect
  error observability（TRANSPORT_ERROR + error）

V2 真实验证过的 reconnect（migration debt）：
  R-reconnect-1  manual reconnect primitive（connect 可重调用；T6/T8 TEST_CONFIRMED）
                 → V3 等价：AdbRuntime.connect / connectAuto 可重调用 + disconnect 幂等（AT.V3-2.3 真机）  F（已闭环）
  R-reconnect-2  post-failure reconnect（T8，test-orchestrated）
                 → V3：onOperationResult → bounded recovery（真机 G13/G14）                        F（已闭环）

V2 只有设计、无运行时证据（D 类，不是 V2 debt）：
  Reconnect button / manual reconnect UI      = 设计 only → V3 MISSING（ADB_RECONNECT_UI = DEFERRED）
  recovery exhausted → human status UI        = 设计 only → V3 只有 RECOVERY_FAILED 状态（无 UI）   D
  background reconnect / continuous liveness  = 设计 only → V3 无（ADB_BACKGROUND_RECOVERY = DEFERRED） D
  UI reconnect state                          = 设计 only → V3 无                                     D

结论：reconnect 迁移债 = 0（V2 的 manual/post-failure reconnect 已在 V3 真机闭环）；
其余 reconnect 产品能力全是 V2_DESIGN_ONLY，不构成 V2 VERIFIED migration debt。
```

## 12. Human UX Debt

```text
U1 Human ADB connection status presentation   V2 无生产 UI → V3 无（DEV_PREVIEW Local 终端）      D
U2 Fresh-install onboarding / pairing UI      V2 无生产 UI（仅讨论） → V3 无                        D
U3 Accessibility pairing code reader（生产）  E2 probe VERIFIED → V3 MISSING                        A（probe 已验证）
U4 Settings navigation                        V2 设计 → V3 MISSING                                  D
U5 Manual pairing-code fallback               长期契约（跨 OEM） → V3 MISSING                        D（非 V2 debt）
```

## 13. Already Closed / Do Not Reopen

```text
F 类（V3 已闭环，本轮/后续不得重开）：
  ADB TLS/CNXN Golden Chain（AT.V3-1）        CLOSED
  single RX owner + multi-stream（AT.V3-2.1）  CLOSED
  WRTE/OKAY flow-control（AT.V3-2.2）          CLOSED
  Local/ADB execution parity（AT.V3-3）        CLOSED
  post-trust discovery/recovery/connectAuto（AT.V3-2.3）  CLOSED
  AT Engine independence（AT.V3-1）            CLOSED
  identity 文件持久 + 跨 connection 复用       CLOSED（F）
  manual/post-failure reconnect primitive      CLOSED（F）
  E3 mechanism（adb_wifi_enabled 控制监听）    VERIFIED 机制（但产品化 W4/W6 未做 → A）
```

> 唯一例外判断：若后续 pairing parity 发现这些 CLOSED 项直接阻塞 pairing 才考虑重开；
> 当前审计未发现。

## 14. V2 Debt Ledger

| ID | Capability | V2 status | V2 evidence | V3 current | V3 status | Target owner | Classification | Needs code migration | Needs runtime wiring | Needs real-device parity | Needs product UI | Blocks fresh-install READY? | Recommended slice |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DEBT-P01 | Pairing protocol primitive（pair() Core） | VERIFIED/CLOSED E4 | AT.C-1 §13 | native IDENTICAL + JNI/Kotlin | COMPILED（真机 parity 未跑） | :adb-engine | **B** | NO（已迁入） | NO（已接线） | YES | NO | YES（fresh identity→trusted） | Slice 1 |
| DEBT-P02 | android_pubkey regression fix | VERIFIED 修复 | AT.C-1 §6.3#6 | 已随迁（IDENTICAL） | PRESENT | :adb-engine | **F** | NO | NO | NO（修复已含） | NO | NO | — |
| DEBT-P03 | Pairing endpoint discovery（`_adb-tls-pairing._tcp`） | E1 VERIFIED | FIRST_RUN §5.5 | 仅 connect service | MISSING | :adb-engine（discovery） | **A** | YES | YES | YES | NO | YES | Slice 1/2 |
| DEBT-P04 | Pairing code Accessibility（生产） | E2 probe VERIFIED | FIRST_RUN §6.3 | MISSING | MISSING | :app（Host） | **A** | YES | YES | YES | YES | YES（可手工 fallback） | Slice 3 |
| DEBT-P05 | PairingCoordinator + first-run state machine | 设计 only | FIRST_RUN §10 | MISSING | MISSING | :adb-engine（Runtime） | **D** | YES | YES | YES | NO | YES | Slice 2 |
| DEBT-P06 | PAIRING_REQUIRED/AUTH_FAILURE/IDENTITY runtime state | 设计 only（预留 seam） | FIRST_RUN §10.1 | AdbRuntimeState 无 | MISSING | :adb-engine（Runtime） | **D** | YES | YES | YES | NO | YES | Slice 2 |
| DEBT-P07 | Untrusted/auth-failure classification | 设计 only | AT.C-1 E4 STEP1 | connect 失败=generic | MISSING | :adb-engine | **D** | YES | YES | YES | NO | NO | Slice 2 |
| DEBT-P08 | Pairing→connectAuto handoff | E4 真机（同 keyDir 复用） | AT.C-1 §10/§13 | 机制在，无编排 | PARTIAL | :adb-engine | **C** | NO | YES | YES | NO | YES | Slice 2 |
| DEBT-P09 | Identity lifecycle（ensure/fingerprint/state/re-pair/invalidation） | 设计 only | FIRST_RUN §8.3 | loadOrCreate 有，无抽象 | MISSING | :adb-engine（Runtime） | **D** | YES | YES | NO | NO | NO | Slice 2 |
| DEBT-P10 | WRITE_SECURE_SETTINGS bootstrap | E3 VERIFIED（机制） | FIRST_RUN §9.4 | manifest 无 | MISSING | :app（Host）+ :adb-engine（platform backend） | **A/C** | YES | YES | YES | YES | NO（WD 可手动） | Slice 4 |
| DEBT-P11 | Auto-WD platform backend（controller） | E3 VERIFIED（机制） | FIRST_RUN §9.4 | readiness 只读 | MISSING | :adb-engine（platform backend） | **A/C** | YES | YES | YES | NO | NO | Slice 4 |
| DEBT-P12 | Settings navigation | 设计 only | 路线纪要 §16 | MISSING | MISSING | :app | **D** | YES | YES | NO | YES | YES（引导进配对页） | Slice 3 |
| DEBT-P13 | Manual pairing-code fallback | 长期契约（跨 OEM） | FIRST_RUN §6.3 fallback | MISSING | MISSING | :app | **D** | YES | YES | NO | YES | NO（Accessibility 为 preferred） | Slice 3 |
| DEBT-P14 | Reconnect UX / manual reconnect UI | 设计 only（无 V2 生产 UI） | AT.RUNTIME-1 §H | RECOVERY_FAILED 状态有，无 UI | MISSING | :app | **D** | YES | YES | NO | YES | NO | Slice 5（仅当 usability blocker） |
| DEBT-P15 | Background recovery / continuous liveness | 设计 only | AT.RUNTIME-1 §H | 无 | MISSING | :adb-engine | **D** | YES | YES | NO | NO | NO | DEFERRED |
| DEBT-P16 | Human ADB connection status presentation | 设计 only | 路线纪要 §12 | DEV_PREVIEW 无 | MISSING | :app | **D** | YES | YES | NO | YES | NO | DEFERRED |
| DEBT-P17 | Fresh-install onboarding flow | 设计 only | 路线纪要 §12 | MISSING | MISSING | :app | **D** | YES | YES | NO | YES | YES | Slice 3 |

> 计数：A=3（P03/P04/P10/P11 中 4 项…见 Final Verdict 精确计数）

**精确计数（按 ID）**：
- A（V2_VERIFIED_NOT_MIGRATED）= **4**：P03、P04、P10、P11
- B（V2_VERIFIED_MIGRATED_NOT_PARITY）= **2**：P01（含 P02 fix）、P08 handoff（PARTIAL 归 B/C）
- C（V2_VERIFIED_MECHANISM_NOT_PRODUCTIZED）= **1**：P08（handoff）
- D（V2_DESIGN_ONLY）= **9**：P05、P06、P07、P09、P12、P13、P14、P15、P16、P17（P13 归 D 但属长期契约）
- F（ALREADY_CLOSED_IN_V3）= **3**：P02、identity 文件机制、manual/post-failure reconnect
- E（V3_NEW_REQUIREMENT）= **0**（本轮无 V2 从未做过的 V3 新需求）

## 15. Fresh Install → READY Target Flow

```text
FRESH_INSTALL
  → IDENTITY_READY（loadOrCreate；无则生成；Runtime identity lifecycle 确保）
  → WIRELESS_DEBUGGING_REQUIRED（WD OFF；readiness 已具备）
        ├─ 已持 WRITE_SECURE_SETTINGS → auto enable（W6，Slice 4 后）→ 继续
        └─ 未持 → USER_ACTION_REQUIRED（引导用户进 Developer Options）
  → WAITING_FOR_PAIRING_MODE（用户打开"使用配对码配对设备"）
  → PAIRING_ENDPOINT_FOUND（discover `_adb-tls-pairing._tcp`，E1 机制）
  → WAITING_FOR_PAIRING_INPUT（Accessibility 读 6 位 code [E2] 或手工 fallback）
  → PAIRING（Core pair(endpoint, code, identity)）
  → PAIRED（device GUID 返回；设备信任 identity）
  → CONNECTING（discover `_adb-tls-connect._tcp` → connectAuto）
  → READY（ATEngine.execute("id") = uid=2000(shell)）
  → [可选] 首次 READY 后 shell pm grant WRITE_SECURE_SETTINGS bootstrap（W4）
  → 后续启动：auto-WD（若已 bootstrap）→ connectAuto → READY

状态归属：
  ADB Runtime（AdbRuntime / future PairingCoordinator）: IDENTITY_READY / WIRELESS_DEBUGGING_REQUIRED /
    WAITING_FOR_PAIRING_MODE / PAIRING_ENDPOINT_FOUND / WAITING_FOR_PAIRING_INPUT / PAIRING / PAIRED / CONNECTING / READY
  Host / UI（:app）: Settings 导航 / Accessibility code 读取 / 手工 code 输入 / onboarding 呈现 / pm grant 用户授权
  presentation: 进度 UI（可选）
```

## 16. Ownership Map

```text
ADB Engine Runtime（:adb-engine）
  owns: identity facts / pairing mechanism / pairing endpoint（discovery）/ connection endpoint /
        pairing lifecycle / readiness / connect/recovery
  （P01/P03/P05/P06/P07/P08/P09/P11 归此）

Host / Android Integration（:app，未来可选 :at-android-platform adapter）
  owns: Settings navigation / permission UI / Accessibility / pairing code presentation+input /
        onboarding UI / pm grant bootstrap 用户授权
  （P04/P10/P12/P13/P14/P16/P17 归此）

AT Engine（:at-engine）
  owns: terminal execution semantics（本轮无关）

严禁：
  把 Pairing 放进 AT Engine
  把 Accessibility 放进 adb-engine native
  让 ADB Engine 依赖 AndroidTerminal UI（:adb-engine 依赖 :at-engine / :app = 禁止）
```

## 17. Proposed Migration Slices

```text
Slice 1:  Pairing Primitive V3 Parity Closure（DEBT-P01）
  目标：V3 fresh identity → pair → trust → connectAuto → uid=2000（E4 V3 parity）
  内容：source parity audit（已做=IDENTICAL）+ deterministic regression + fresh identity gen +
        pair-before-connect FAIL + pair() + device trust confirmation + same identity connectAuto +
        id → uid=2000 + cleanup。若发现 migration bug 才最小修复。
  依赖：无（native/JNI/Kotlin 已在）；不要求 Host UI。
  真实设备：需要（fresh identity 端到端）。

Slice 2:  ADB Runtime Pairing + Identity Lifecycle（DEBT-P05/P06/P07/P08/P09）
  目标：AdbRuntime 能编排 pairing 状态并与现有 post-trust connectAuto 接起来
  内容：AdbRuntimeState 增 PAIRING_REQUIRED/AUTH_FAILURE/PAIRING/PAIRED/IDENTITY_*；
        AdbRuntime.pair()/discoverPairing()/identityState()；PairingCoordinator；IdentityLifecycle；
        discovery backend 加 serviceType 维度（复用同一 NSD 实现，不复制）。
  依赖：Slice 1。

Slice 3:  First-run Host Pairing Assistant（DEBT-P04/P12/P13/P17）
  目标：Settings navigation + Accessibility code acquisition + manual input fallback + onboarding
  内容：:app AccessibilityService（生产）+ SettingsNavigator + PairingAssistant + onboarding UI
  依赖：Slice 2；可与 Slice 4 并行。

Slice 4:  Wireless Debugging Bootstrap（DEBT-P10/P11）
  目标：首次获得 shell 后自举 WRITE_SECURE_SETTINGS + 后续 auto-WD
  内容：manifest 声明 + pm grant bootstrap + WirelessDebuggingController platform backend（Settings write）
  依赖：已配对 + shell 可用（Slice 1/2）；可与 Slice 3 并行。

Slice 5:  Reconnect Product UX（DEBT-P14）
  仅当审计证明它是 V2 verified debt（当前结论=D design-only）或 fresh-install usability blocker。
  当前：非 V2 debt；除非产品阻塞，否则 DEFERRED。
```

## 18. Dependencies

```text
Slice 1（pairing parity）→ Slice 2（runtime pairing）
Slice 2 → Slice 3（Host assistant）/ Slice 4（auto-WD）
Slice 3 ∥ Slice 4
FRESH_INSTALL_TO_READY Gate = Slice 1+2+3（pairing 闭环）+ 至少手动 WD（不需要 Slice 4）
AUTO_WD_BOOTSTRAP Gate = Slice 1+2+4（identity trusted + WRITE_SECURE_SETTINGS）
```

## 19. Risks

```text
R1  V3 pairing 真机 parity 可能暴露迁移差异（尽管 native 逐字节 IDENTICAL，JNI/Kotlin 形状、
    真机时序/端口生命周期仍可能不同）→ Slice 1 用 V2 E4 过程作模板重跑。
R2  V2 E1 的 pairing service 瞬态性（配对弹窗关闭即消失）→ Pairing Assistant 必须保持配对页前台
    （V2 FIRST_RUN §5.5 已记录）。
R3  Accessibility view id（com.android.settings:id/pairing_code）非跨 OEM 永久契约
    → 保留 manual fallback；Accessibility = convenience on verified OEM（V2 FIRST_RUN §6.3）。
R4  HONOR WRITE_SECURE_SETTINGS 授予后 app 进程字面 putInt 未真机字面验证（E3 残留）
    → Slice 4 真机验证；失败 fallback = 手动开 WD。
R5  pairing 会话一对一次 → pair() 需在有效窗口内发起（Runtime 职责，V2 AT.C-1 §16）。
R6  不要把 fresh-install 写成"完全零操作"：Developer Options 首次开启 / WD 首次开启 /
    配对码配对 / Accessibility 授权仍需用户动作（§23 任务要求）。
```

## 20. Recommended Next

```text
NEXT_IMPLEMENTATION_TASK = V3 Pairing Primitive Parity Closure（Slice 1）
  依据：
    1 阻塞 fresh identity → trusted（唯一真正的 fresh-install 前置缺口中机制层最核心项）
    2 V2 已 VERIFIED（AT.C-1 E4）
    3 V3 已有大部分代码（native/JNI/Kotlin IDENTICAL 迁入）
    4 能形成最小独立 parity closure（不要求同时改 Host UI / pairing UI）
    5 只做：fresh identity + pair-before-connect + pair() + trust + connectAuto + uid + cleanup
  若审计/parity 发现 migration bug → 才允许最小修复（不把 PairingCoordinator/Accessibility/UI 一起塞入）。
```

## 21. Final Verdict

```text
AT.V3-DEBT-0 FINAL VERDICT

STATUS = COMPLETE

BASELINE_V3 = c56a3ba（clean）
BASELINE_V2 = 726a441（43 会前 dirty）

V2_WRITES = 0
PRODUCTION_CODE_MODIFIED = NO

V2_PAIRING_PRIMITIVE = VERIFIED（AT.C-1 CLOSED）
V2_PAIRING_E4 = PASS（真机闭环 2026-10-10）
V2_PAIRING_ENDPOINT_DISCOVERY = VERIFIED（E1）
V2_PAIRING_CODE_ACCESSIBILITY = VERIFIED（E2）
V2_AUTO_WD_MECHANISM = VERIFIED（E3，机制层）

V3_PAIRING_NATIVE_PRESENT = YES
V3_PAIRING_NATIVE_STATUS = MIGRATED（10/10 IDENTICAL；真机 parity pending）
V3_PAIRING_JNI_PRESENT = YES
V3_PAIRING_KOTLIN_SURFACE_PRESENT = YES
V3_ANDROID_PUBKEY_FIX_PRESENT = YES（rsa_2048_key.cpp IDENTICAL 含 524B 修复）
V3_PAIRING_ENDPOINT_DISCOVERY = MISSING（仅 connect service）
V3_IDENTITY_MECHANISM = loadOrCreate PRESENT（跨 connection 复用 VERIFIED）
V3_IDENTITY_LIFECYCLE = MISSING（仅 keyDir 透传）
V3_PAIRING_COORDINATOR = MISSING
V3_AUTH_FAILURE_CLASSIFICATION = MISSING（untrusted connect = generic 失败）

V3_FIRST_RUN_HOST_UI = MISSING
V3_ACCESSIBILITY_PAIRING_CODE = MISSING
V3_MANUAL_CODE_FALLBACK = MISSING
V3_WRITE_SECURE_SETTINGS_BOOTSTRAP = MISSING
V3_AUTO_WD = MISSING

V3_POST_TRUST_LIFECYCLE = VERIFIED（AT.V3-2.3）

V2_VERIFIED_NOT_MIGRATED_COUNT = 4（P03 pairing discovery / P04 Accessibility / P10 WRITE_SECURE bootstrap / P11 auto-WD）
V2_VERIFIED_MIGRATED_NOT_PARITY_COUNT = 1（P01 pairing primitive，含 P02 fix）
V2_VERIFIED_MECHANISM_NOT_PRODUCTIZED_COUNT = 1（P08 handoff）
V2_DESIGN_ONLY_COUNT = 10（P05/P06/P07/P09/P12/P13/P14/P15/P16/P17）
V3_NEW_REQUIREMENT_COUNT = 0
ALREADY_CLOSED_IN_V3_COUNT = 3（identity 文件持久 / manual+post-failure reconnect / P02 修复已随迁）

PAIRING_BLOCKS_FRESH_INSTALL_READY = YES（唯一机制前置；post-trust 已闭环）

FIRST_RUN_ADB_BOOTSTRAP = READY_FOR_IMPLEMENTATION（V2 E1/E2/E3/E4 已真机；V3 缺 parity + orchestration + Host）
PAIRING_MECHANISM_CLOSURE = NOT_VERIFIED_IN_V3（Slice 1 后转 VERIFIED）
PRODUCT_ONBOARDING_CLOSURE = NOT_STARTED（Slice 3）

RECONNECT_VERIFIED_DEBT = 0（V2 manual/post-failure reconnect 已在 V3 真机闭环）
RECONNECT_DESIGN_ONLY = 5（reconnect UI / manual reconnect UI / recovery-status UI / background / liveness）

EMULATOR_PRIORITY = DEFERRED_UNTIL_V2_DEBT_CLOSURE

DEBT_LEDGER = docs/audit/AT.V3-DEBT-0_V2_Verified_Debt_Inventory_and_First_Run_Closure_Plan.md
MIGRATION_MANIFEST_UPDATED = YES
EVIDENCE_INDEX_UPDATED = YES
DOC_CORRECTION_2_4_MIN_LIFETIME = DONE（见 §附注）
DOC_CORRECTION_REPLAY_WORDING = DONE（见 §附注）

NEXT_IMPLEMENTATION_TASK = V3 Pairing Primitive Parity Closure（Slice 1）
NEXT_TASK_OBJECTIVE = V3 fresh identity → pair() → device trust → 同 identity connectAuto → uid=2000(shell)；
                      仅当 parity 发现 migration bug 才最小修复；不携带 PairingCoordinator/Accessibility/UI

GIT_COMMIT = 3b00177（AT.V3-DEBT-0 audit commit）
GIT_WORKTREE = CLEAN
V2_WRITES = 0

STOP
```

## 附注：文档债修正

### 1. 2.4 分钟 lifetime（AT.V3-2.3 EVIDENCE 环境备注）

```text
原表述："HONOR 约 2–4 分钟不活动会回 0" —— 证据不足。
修正为："早期不稳定网络环境曾观察到 WD drop；稳定 Wi-Fi / hotspot 已完成较长时间稳定运行
（BackendParityAuditTest 781.5s 连续完成）；当前没有足够证据证明存在固定 2–4 分钟
MagicOS lifetime。"
```

### 2. No-replay 表述（AT.V3-2.3 G13）

```text
原表述：TRANSPARENT_UNKNOWN_OUTCOME_REPLAY = 0
实际证据范围：连接已失效 → op A 得 TRANSPORT_ERROR → guard TOKEN=0（未到达设备）。
它证明"失败 operation 未被透明重放"；未完全构造"remote 已执行、host 未知结果"的
ambiguous outcome。
修正：区分
  FAILED_OPERATION_TRANSPARENT_REPLAY = 0（V3-2.3 证明）
  UNKNOWN_OUTCOME_RETRY_POLICY = DEFERRED（未证明；后续独立策略）
```