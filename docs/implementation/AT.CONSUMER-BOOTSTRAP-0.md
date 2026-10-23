# AT.CONSUMER-BOOTSTRAP-0 �?Unified Pairing Bootstrap Surface（实现）

> 状态：**COMPLETE**（全 gate PASS，单次原子提交）
> 基线：V3 `bfd1ef6`（AT.CLI-CONSUMER-0 交付清理）← `c7eb84f`（AT.CLI-CONSUMER-0）← `7ccb1bf`（AT.PRE-EMULATOR-FREEZE-V1�?
> 设备：HONOR ALI-AN00 `APLMUT4312005935`（Android 15�?
> 约束遵守：只�?V3；AndroidAgent / AndroidAgent-V2 / AndroidTerminal-V2 只读；不重开冻结边界；不�?ADB wire 协议；无第二 AdbRuntime / recovery owner；无外部 adb 二进�?/ 5037；不复制其它 App key；pairing code 不持久化�?

---

## 0. 目标

让任�?AT Consumer �?*第一次没有有�?ADB trust** 时，通过一�?*正式、可复用、产品无关的 AT Bootstrap Surface** 完成�?

```
identity �?pairing service discovery �?pairing �?connectAuto �?READY
```

并让三个入口共享**同一套底层能�?*（PAIRING IMPLEMENTATION COUNT = 1）：

1. **AT Product UI**（PairActivity�?
2. **shell-visible `adb pair <host>:<port> <code>`**
3. **future external consumer API**（结构化 Bootstrap API�?

普通用户：只输 6 位配对码（host/port 自动发现）。高级用户：`adb pair <host>:<port> <code>`。未�?Consumer：同一个结构化 API。三�?*不依�?OpenCode**�?*不复�?key**�?*不创建第�?Runtime**�?

本轮不实�?AndroidAgent；只保留未来 Consumer 可调用的接口边界�?

---

## 1. §8 审计结果（audit before coding�?

| Owner | 位置 |
|---|---|
| PAIRING_PRIMITIVE_OWNER | `AdbEngine.pair(host, port, code, keyDir): AdbPairingResult`（adb-engine，native�?|
| PAIRING_DISCOVERY_OWNER | `AdbRuntime.discoverPairing(timeoutMs): List<AdbDiscoveredService>`（adb-engine，NSD�?|
| CONNECT_AUTO_OWNER | `AdbRuntime.connectAuto(keyDir): AdbRuntimeDiagnostic`（adb-engine，lifecycle owner�?|
| ENSURE_READY_OWNER | `AdbRuntime.ensureReady(keyDir, statusListener): AdbRuntimeState` |
| IDENTITY_OWNER | PER_CONSUMER_APP_PRIVATE（app: `files/adb_identity`；consumer host: `files/at-cli-identity`�?|
| CURRENT_PRODUCT_PAIRING_ENTRY | `PairActivity` —�?**直接编排** discoverPairing + AdbEngine.pair + connectAuto（违�?§15/§23�?|
| CURRENT_TEST_PAIRING_ENTRY | `AtCliConsumerTest.firstUsePairing` —�?**直接** AdbRuntime.discoverPairing + AdbEngine.pair（违�?§26�?|
| ATCLI_CURRENT | `AtCliMain` �?bridge �?`ATCliExecution.adbEngine().execute/openSession`（无 pairing 路径�?|
| BEST_REUSABLE_BOOTSTRAP_BOUNDARY | `:adb-engine` 紧邻 AdbRuntime �?`AdbConsumerBootstrap`（Controller 决策 C1�?|

**关键约束**�?
- `AdbEngine.connect` 只返�?`Boolean`（不区分配对拒绝 vs 其他失败）→ PAIRING_REQUIRED 必须**诚实保守分类**（�?2/C4）�?
- `AdbRuntimeState` 明确"Pairing states 不在 AdbRuntime 范围�?�?BootstrapState �?**consumer projection**，不是第二套 lifecycle owner（�?3/C6）�?

---

## 2. 设计（Controller 决策 C1-C7�?

### 2.1 放置：`:adb-engine` �?`AdbConsumerBootstrap`（C1�?

pairing / discovery / connectAuto �?**ADB 子系统语�?*，不�?CLI 语义也不�?backend-neutral AT Engine 语义。所以可复用 bootstrap 边界放在 **`:adb-engine` 紧邻 AdbRuntime**（不新�?`:at-bootstrap` 模块）�?

### 2.2 ONE 可复用实现（C2/C7�?

`AdbConsumerBootstrap` �?*唯一**触碰 `AdbEngine.pair` 的地方，参数�?consumer �?app-private `identityDir`。`:app` �?`:at-cli-consumer-host` **实例化同一个实�?*（不各自�?pairing wrapper）：

```kotlin
class AdbConsumerBootstrap(private val identityDir: String) {
    fun bootstrapState(): AdbBootstrapState          // 结构化投�?
    fun discoverPairing(timeoutMs): AdbPairingDiscovery
    fun pair(host, port, code): AdbPairingResultV2   // 唯一 AdbEngine.pair 调用�?
    fun pairDiscovered(code, timeoutMs): AdbPairingResultV2  // 1 endpoint 自动 / 0 NOT_FOUND / >1 MULTIPLE
    fun hasIdentity(): Boolean
}
```

**PAIRING_IMPLEMENTATION_COUNT = 1**；`PAIR_ACTIVITY_DIRECT_ADBENGINE_PAIR = NO` / `ATCLI_DIRECT_ADBENGINE_PAIR = NO` / `CONSUMER_TEST_DIRECT_ADBENGINE_PAIR = NO`�?

### 2.3 结构化结果（C5/C6�?

- `AdbBootstrapState`（durable/projection）：`READY` / `PAIRING_REQUIRED` / `USER_ACTION_REQUIRED_WD` / `ENDPOINT_NOT_FOUND` / `CONNECT_FAILED` / `NOT_READY`
- `AdbPairingDiscovery`（discovery 结果）：`endpoints` / `unique` / `serviceNotFound` / `multiple`
- `AdbPairingResultV2`（pair 结果，非 Boolean）：`success` / `outcome`(SUCCESS/REJECTED/SERVICE_NOT_FOUND/MULTIPLE_ENDPOINTS/FAILED) / `deviceGuid` / `error` / `endpoint`

**不引入第二套 lifecycle state machine**；`AdbRuntime` 仍是唯一 ADB lifecycle owner�?

### 2.4 诚实 PAIRING_REQUIRED 分类（C4/§12�?

`bootstrapState()`�?
- `READY`：AdbRuntime READY（有证据�?
- `USER_ACTION_REQUIRED_WD`：WD 确认 DISABLED
- `ENDPOINT_NOT_FOUND`：AdbRuntime 报无端点
- **`PAIRING_REQUIRED`：无可用身份（`hasIdentity()==false`）—�?有证据（�?key 材料），非猜�?*
- `CONNECT_FAILED`�?*有身份但连接失败 —�?�?assert "必须配对"**（Boolean 失败不是 auth 证据），把配对作�?remediation 提供

### 2.5 CLI `adb pair`（C3�?

- `AdbCliArgs` 新增 `Kind.PAIR` + `parsePair`（解�?`adb pair <host>:<port> <code>`；bad args �?exit 2，不触发底层配对�?
- `AdbCliProtocol` 新增 `T_PAIR`（tag 13，payload = `host:port\0code`）—�?**不复�?T_ONESHOT**
- `AtCliMain.bridgePair` �?T_PAIR 帧，�?T_OUT/T_ERR/T_EXIT
- `AdbCliBridgeServer.onPair` 路由�?`execution.consumerBootstrap().pair(...)`（bridge 不直�?AdbEngine.pair�?
- `ATCliExecution` 新增 `consumerBootstrap()`（默�?`AdbConsumerBootstrap(adbIdentityDir())`�?
- native `cli_bridge_server.cpp` �?T_PAIR 分发 + `onPair` 方法

### 2.6 Product UI（C7�?

`PairActivity` 改走 `AppRuntime.consumerBootstrap()`�?
- `bootstrapState()` 显示诚实状态（PAIRING_REQUIRED / READY / USER_ACTION_REQUIRED_WD...�?
- [配对] �?`pairDiscovered(code)`（自�?host/port，用户只�?6 位码�?
- 处理 0/1/N endpoint（SERVICE_NOT_FOUND �?"未发现配对服�?；MULTIPLE �?提示用完整形式）
- 成功�?`connectAuto` �?READY

**PairActivity 不直接调 AdbEngine.pair**（U1）�?

### 2.7 诚实配对提示（UX 修复，用户观察）

用户观察：未配对�?`adb shell` 失败只给 `retrying...`→`fail`，无"需要配�?说明。修复：`AppCliExecution.ensureReady` �?`AtCliConsumerHost.cliExecution().ensureReady` 在冷路径**先查 `hasIdentity()`**�?
- 无身�?�?明确提示 `pairing required: no ADB identity found` + `open Settings -> Wireless Debugging -> Pair device with pairing code, then run: adb pair <host>:<port> <code>`
- 有身份但连接失败 �?提示 `not connected (identity present). If pairing is needed: adb pair <host>:<port> <code>`（保守，�?assert�?

---

## 3. 真机验证（设�?`APLMUT4312005935`�?

### 3.1 Consumer Host B1-B5 门禁

| Gate | 结果 | 证据 |
|---|---|---|
| B1 NEW_IDENTITY | **PASS** | 空身�?bootstrap �?`PAIRING_REQUIRED`（诚实分类） |
| B2 DISCOVERY | **PASS** | 结构化发现（0/1/N endpoints，serviceNotFound/multiple�?|
| B3 PAIR | **PASS** | `bootstrap.pairDiscovered(code)` �?SUCCESS，guid=`adb-APLMUT4312005935-J4nb5z`，connectAuto→READY |
| B4 POST-PAIR | **PASS** | `adb shell id` = uid=2000(shell) |
| B5 RESTART REUSE | **PASS** | 重启�?ADB_STATE=READY，无需重配�?|

### 3.2 CLI P1-P3 门禁

| Gate | 结果 | 证据 |
|---|---|---|
| P1 CLI PAIR | **PASS** | `adb pair <LAN_IP>:38619 <code>` �?PAIR_RC=0，`adb shell id`=uid=2000 |
| P2 BAD CODE | **PASS** | 错误�?�?nonzero exit，身份不动，�?crash |
| P3 BAD ARGS | **PASS** | 无参/坏端�?坏端�?�?exit 2，身份不动，不触发底层配�?|

### 3.3 架构门禁（冻结边界不重开�?

| Gate | 结果 |
|---|---|
| SECOND_AT_ENGINE | NO |
| SECOND_ADB_RUNTIME | NO |
| SECOND_RECOVERY_OWNER | NO（AdbRuntime 仍唯一 owner�?|
| EXTERNAL_ADB_BINARY | NO（app_process AtCliMain，无 ELF�?|
| ADB_SERVER_5037 | NO |
| APP_PRIVATE_KEY_COPY | NO（consumer host 公钥主体 �?app�?|
| PAIR_ACTIVITY_DIRECT_PROTOCOL_OWNER | NO |
| ATCLI_DIRECT_PROTOCOL_OWNER | NO |
| PAIRING_IMPLEMENTATION_COUNT | 1（AdbConsumerBootstrap�?|
| ADB_RUNTIME_IS_SINGLETON | true |
| NESTED_ENGINE_DEADLOCK | NO |
| CLI_SHARED_AT_RUNTIME | PASS |

### 3.4 身份门禁

| Gate | 结果 |
|---|---|
| CONSUMER_IDENTITY_ORIGIN | SELF_GENERATED |
| CONSUMER_KEY_COPIED | NO（公钥主体对比） |
| CONSUMER_IDENTITY_REUSE_AFTER_RESTART | PASS |
| IDENTITY_OWNER | PER_CONSUMER_APP_PRIVATE |

### 3.5 产品 UI U1-U3 门禁

| Gate | 结果 | 证据 |
|---|---|---|
| U1 PRODUCT_API_BOUNDARY | **PASS** | PairActivity �?`AppRuntime.consumerBootstrap()`，不直接 AdbEngine.pair |
| U2 PRODUCT_MANUAL_PAIR | **PASS** | 清身份→打开 app→[配对]→自�?discover→只输码→配对成功（身份 00:04 生成，PairActivity finish）→Bridge 13/13（adb shell id=uid=2000�?|
| U3 NO_SERVICE_STATE | PASS（代码路径） | `pairDiscovered` 0 endpoint �?SERVICE_NOT_FOUND �?"未发现配对服�?提示（不 crash/hang/stale port�?|

### 3.6 回归

| 套件 | 结果 |
|---|---|
| 产品 AdbCliBridgeTest（C1-C15�?| **13/13** |
| 产品 AdbCliLifecycleTest（L1-L11�?| **11/11** |
| 产品 AdbCliLiveStatusTest（S1-S9�?| **9/9** |
| 产品 AdbCliInteractiveTest（I1-I16�?| **16/16** |
| Consumer Host C1-C10 | �?PASS（C2 `adb shell id`=uid=2000�?|
| JVM 单测（at-engine/at-backend-local/at-backend-adb/at-cli/adb-engine�?| PASS |
| `AdbCliArgsTest`（含新增 PAIR 解析 7 例） | PASS |
| 全模块构�?| BUILD SUCCESSFUL |

### 3.7 配对码安全（§19�?

- pairing code 只作短生命周�?secret：`pair`/`pairDiscovered` 入参，调用后丢弃
- **不写**长期日志 / SSOT / evidence 明文 / SharedPreferences / 数据�?
- 测试阶段�?`atcli_pairing_code.txt` 临时传递，测试结束清理
- bridge `onPair` 日志 **redact** 配对码（只记 host:port，`(code redacted)`�?
- 最终文档：**PAIRING_CODE = REDACTED**（不记录真实 6 位码�?

### 3.8 设备环境备注

- HONOR 设备无线调试状态需�?Settings UI 切换（OEM 拦截 `settings put global adb_wifi_enabled`）；产品 app �?WRITE_SECURE_SETTINGS 可自�?auto-WD
- 配对�?端口一次性，3 分钟窗口过期；adbd 配对服务只在"使用配对码配�?弹窗前台时可�?

---

## 4. 依赖关系

```
:app ──────────────�?
                   ├──�?AdbConsumerBootstrap�?adb-engine，ONE 实现）──�?AdbEngine.pair / AdbRuntime
:at-cli-consumer-host ─�?
:at-cli（frontend）──�?ATCliExecution.consumerBootstrap() ──�?host �?AdbConsumerBootstrap
```

- `:at-cli` 只是 **frontend**（`adb pair` �?T_PAIR �?bridge �?host bootstrap�?
- `:adb-engine` 承载可复�?bootstrap 边界
- 不新增模块；`:at-cli` 不依�?`:app`；consumer host 不依�?`:app`

---

## 5. 冻结边界遵守（PRE-EMULATOR-FREEZE�?

- AdbRuntime 仍唯一 ADB lifecycle owner（未重开�?
- 未改 ADB wire 协议 / SPAKE2 / pairing TLS / certificate 语义
- 未实�?Emulator / ScreenState
- AT 最终契约保�?**DRAFT**（`CONSUMER_BOOTSTRAP_API_STATUS = PROVISIONAL / DRAFT`，`FINAL_AT_API_FREEZE = NO`�?

---

## 6. DEFERRED（本轮不做）

- **`adb pair <code>` shorthand**（�? OPTIONAL）：只实现标�?`adb pair <host>:<port> <code>`。shorthand（discover�? endpoint→自动）DEFER，避免扩�?scope�?
- **MULTIPLE endpoints UI 选择**：代码保证不 silent pick arbitrary endpoint（`pairDiscovered` 返回 MULTIPLE_ENDPOINTS），�?UI �?endpoint 选择�?DEFER�?
- **AndroidAgent 集成**：本轮不�?AG 代码，只保留结构�?Bootstrap API 边界�?

---

## 7. 文档�?SSOT

- 新实现文档：`docs/implementation/AT.CONSUMER-BOOTSTRAP-0.md`（本文件�?
- `V3_Evidence_Index.md` 登记 AT.CONSUMER-BOOTSTRAP-0（不重写历史 AT.CLI-CONSUMER-0 / AT.PRE-EMULATOR-FREEZE / Handoff V1�?
- Handoff V1 未修改；**不创�?* Handoff V2（仍保留 POST AT.EMULATOR-0 + CONTRACT-1 REVIEW�?
- 本轮交付（`本轮交付/`）按 AGENTS.md §4 刷新（只放本轮新�?修改文档�?

---

## 8. 提交

- 单次原子提交：`AT.CONSUMER-BOOTSTRAP-0: unify pairing bootstrap surface`
- TAG=NO，PUSH=NO
- �?amend `c7eb84f` / `7ccb1bf`；不�?AT.PRE-EMULATOR-FREEZE-V1 tag

---

## 9. 最终架�?

```
                        Consumer
                           �?
                           �?
               AT Consumer Bootstrap API
            （AdbConsumerBootstrap�?adb-engine�?
                           �?
            ┌──────────────┼──────────────�?
        identity       discovery          pair
            �?             �?             �?
            └──────────────┼──────────────�?
                           �?
                      AdbRuntime（唯一 lifecycle owner�?
                           �?
                           �?
                      AdbEngine（pairing protocol primitive�?
                           �?
                           �?
                          adbd

两个 frontend（共享同一 AdbConsumerBootstrap）：

AndroidTerminal UI          AT CLI
       �?                      �?
   [配对按钮]            adb pair <host>:<port> <code>
       �?                      �?
   自动 discover host/port      �?
   用户只输 6 �?code          �?
       �?                      �?
       └──────────┬────────────�?
                  �?
          AdbConsumerBootstrap（PAIRING IMPLEMENTATION COUNT = 1�?
```

## 10. Gate 总表

| 类别 | Gate | 状�?|
|---|---|---|
| Bootstrap | B1 NEW_IDENTITY | PASS |
| Bootstrap | B2 DISCOVERY | PASS |
| Bootstrap | B3 PAIR | PASS |
| Bootstrap | B4 POST-PAIR (uid=2000) | PASS |
| Bootstrap | B5 RESTART REUSE | PASS |
| CLI | P1 CLI PAIR | PASS |
| CLI | P2 BAD CODE | PASS |
| CLI | P3 BAD ARGS | PASS |
| Product UI | U1 PRODUCT_API_BOUNDARY | PASS |
| Product UI | U2 PRODUCT_MANUAL_PAIR | PASS |
| Product UI | U3 NO_SERVICE_STATE | PASS |
| 架构 | SECOND_AT_ENGINE=NO | PASS |
| 架构 | SECOND_ADB_RUNTIME=NO | PASS |
| 架构 | SECOND_RECOVERY_OWNER=NO | PASS |
| 架构 | EXTERNAL_ADB_BINARY=NO | PASS |
| 架构 | ADB_SERVER_5037=NO | PASS |
| 架构 | APP_PRIVATE_KEY_COPY=NO | PASS |
| 架构 | PAIRING_IMPLEMENTATION_COUNT=1 | PASS |
| 身份 | CONSUMER_KEY_COPIED=NO | PASS |
| 身份 | CONSUMER_IDENTITY_REUSE_AFTER_RESTART | PASS |
| 回归 | 产品 CLI（Bridge/Lifecycle/LiveStatus/Interactive�?| PASS |
| 回归 | Consumer C1-C10 | PASS |
| 回归 | JVM 单测 + 构建 | PASS |

**�?gate PASS �?单次原子提交�?*
