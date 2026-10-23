# AT.V3-2.3 Evidence �?ADB-only Composition & Auto-Connection / Recovery

> 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a�?
> 网络：稳�?Wi-Fi / PC 热点（本机网络波动曾中断一�?BackendParityAuditTest，重跑后 34/34 PASS�?
> 身份：PC adbkey `developer@HOST`（run-as 注入 adbonlyhost + testhost `files/adbkey`，未提交�?
> 日期�?026-10-13

## 0. 基线

```text
V3 HEAD  = 83a0664（AT.V3-2.2 完成�?clean�?
V2 HEAD  = 726a441（只读，43 会前 dirty，零写入�?
```

## 1. ADB-only Composition (G1) + Artifact Audit (G2)

```text
:adb-only-test-host Gradle graph (debugRuntimeClasspath):
  +--- project :at-engine
  +--- project :at-backend-adb
  |    +--- project :at-engine
  |    \--- project :adb-engine
  \--- project :adb-engine
  LOCAL_BACKEND_GRADLE_DEPENDENCY = ABSENT（无 :at-backend-local / :app�?

APK native libs:
  libadbd_engine.so  3273952B
  LOCAL_PTY_NATIVE_LIB = ABSENT（无 liblocal_pty.so�?

APK dex (classes*.dex x6) ASCII 扫描:
  at/backend/local, LocalOneShotBackend, LocalPersistentBackend,
  LocalTerminalSession, LocalPtyNative, liblocal_pty = 0 命中
  LOCAL_BACKEND_DEX = ABSENT

对照（PRESENT�?
  at/engine/ATEngine = PRESENT
  at/backend/adb/AdbOneShotBackend = PRESENT
  at/backend/adb/AdbPersistentBackend = PRESENT
  adb/engine/AdbEngine = PRESENT
  adb/engine/AdbRuntime = PRESENT

ADB_ONLY_COMPOSITION = PASS
ADB_ONLY_ARTIFACT_AUDIT = PASS
LOCAL_BACKEND_REQUIRED = NO
```

## 2. Deterministic lifecycle tests（真机，fake backends，无需 WD�?

`AdbRuntimeLifecycleTest` �?**12/12 PASS**�?.4s�?

```text
readiness_enabled_reportsEnabled                  PASS
readiness_disabled_reportsDisabled                PASS
readiness_unknown_reported                        PASS
connectAuto_wdDisabled_userActionRequired_noDiscovery  PASS（USER_ACTION_REQUIRED + scan=0�?
connectAuto_emptyDiscovery_endpointNotFound        PASS（ENDPOINT_NOT_FOUND + scan=1�?
connectAuto_unknownReadiness_stillDiscovers        PASS（UNKNOWN≠DISABLED；ENDPOINT_NOT_FOUND�?
explicitIntent_noMdnsFallbackOnRecovery            PASS（RECOVERY_FAILED；scanCount=0�?
autoRecovery_boundedAttempts_noInfiniteLoop        PASS（有界，<5s�?
recovery_singleOwner_serialized                    PASS
manualDisconnect_clearsIntent_noAutoRecover        PASS（TRANSPORT_LOST，非 RECOVERING�?
unknownOutcomeOperation_notTransparentlyReplayed   PASS（op 计数=1，runtime 不重放）
backoffInvoked_betweenAttempts                     PASS�?=50ms backoff�?
```

## 3. Real-device G-gates

### G3 �?Cold-start connectAuto（无 explicit endpoint�?
```text
WD=ENABLED（adb_wifi_enabled=1）；discovery 修复�? ENDPOINT_NOT_FOUND
修复后（resolveService 已接，V2 Oracle）：READY
ATEngine.execute("id") �?stdout �?"uid=2000" �?COMPLETED
CONSUMER_KNEW_PORT = NO；EXPLICIT_ENDPOINT_INJECTED = NO；AUTO_DISCOVERY_USED = YES
```
> **迁移 bug 发现**：V3 首次移植�?`AdbAndroidNsdDiscoveryBackend.scan()` 直接�?
> `onServiceFound` �?host/port（Android 给出�?resolve �?service，host=null/port=0），
> 且用永不 countDown �?latch 等满超时 �?永远 `ENDPOINT_NOT_FOUND`。按 V2
> AT.RUNTIME-2B Oracle 修正�?`onServiceFound �?resolveService()` + 收集全候�?+
> loopback-first 排序。这�?COMPILED 未真机验�?迁移的典�?bug�?

### G10 �?Connection reuse
```text
connectAuto �?READY �?execute(A/B/C) 全部 COMPLETED（REUSE_A/B/C 输出�?
�?READY；无 per-command discovery/reconnect。PASS
```

### G8+G9 �?Persistent + Multi-session after auto-connect
```text
PERSIST_OK 会话 echo 正常 �?close
B + C 双会�?echo SB / SC 正常 �?close；仍 READY。PASS
```

### G17 �?Explicit intent semantics
```text
connectAuto �?connectionIntent = Auto（非 Explicit）。PASS
（EXPLICIT no-mDNS-fallback 由确定性测�?explicitIntent_noMdnsFallbackOnRecovery 覆盖�?
```

### G18 �?Error observability
```text
resetForTest 后（无连接）execute("id"):
  outcome=TRANSPORT_ERROR, hasError=true, error 非空�?not connected..."�?
AT_TRANSPORT_ERROR_OBSERVABILITY = PASS �?AG_ERROR_OBSERVABILITY = PASS
```

### G11 �?Process restart cold-start
```text
am force-stop adbonlyhost �?fresh 进程 �?connectAuto �?READY �?id �?uid=2000
无前进程内存里的 endpoint。PASS
```

### G13+G14 �?Dynamic endpoint recovery + no transparent replay（PC 编排�?
```text
OLD 端口 42713 �?PC toggle adb_wifi_enabled 0�? �?NEW 端口 40437
测试: connectAuto �?READY �?�?at23_ready �?[PC 轮换端口] �?�?at23_cycle
  op A: echo TOKEN >> /data/local/tmp/at23_guard.txt
        （连接已�?�?TRANSPORT_ERROR；TOKEN 未到达设备）
  onOperationResult(true) �?bounded recovery（rediscover 找到 NEW 40437 �?reconnect �?READY�?
  op B: echo B_OK �?COMPLETED（POST_RECOVERY_COMMAND = PASS�?
guard 文件最终内�?= 空（0 TOKEN）→ TRANSPARENT_UNKNOWN_OUTCOME_REPLAY = 0
Time: 30.486s。PASS
```

### G15 �?Post-recovery new persistent session
```text
connectAuto �?READY �?at23g15_ready �?[PC 轮换端口] �?probe 触发 recovery
�?�?persistent session PERSIST_RECOVERED echo 正常 �?close。PASS
```

### G16 �?WD OFF behavior
```text
adb_wifi_enabled=0 �?connectAuto �?USER_ACTION_REQUIRED + code=USER_ACTION_REQUIRED_WD_DISABLED
elapsed < 10s（有界，�?reconnect storm）。PASS
```

## 4. Regression（本轮改动后全绿�?

```text
Local (at.backend.local.test)                11/11   PASS
AT Engine unit (ATExecutionContractTest 3 + ATSessionContractTest 3)  6/6  PASS
App Debug (com.androidterminal.app.test)     16/16   PASS�? debug input + 9 dynamic pages�?
Headless (TestHostHeadlessInputTest)          1/1    PASS
ADB Backend (AdbBackendHeadlessTest)         25/25   PASS（显式端�?42583 注入�?
Backend Parity (BackendParityAuditTest)      34/34   PASS�?81.5s，覆�?flow-control/大输�?persistent/multi/cross-mode/failure-path�?
ADB-only deterministic (AdbRuntimeLifecycleTest) 12/12 PASS
```

## 5. Stale connectionState

```text
BEFORE: rxLoop 真实 read-fail �?failAll() �?fail streams，AdbConnection::state_ 保持 Online（stale�?
FIX   : AdbConnection::markTransportLost()（Online→Offline，幂等，不关 socket）；
        AdbStreamHub::rxLoop 真实失败分支（非 stopping_）调用它
AFTER : 真实 transport loss �?connectionState()==OFFLINE �?ensure-ready 正确跳过 /
        recovery 正确触发
（G13/G14 真机确认：掉线后恢复触发 �?新连�?ONLINE �?op B 成功�?
```

## 6. 动态端点记�?

```text
G13/G14: OLD=42713 �?NEW=40437
G3/G10/G8G9 会话: 39795 →（testhost parity 会话�?2583 �?40103
（无线端口随 adbd 重启轮换；evidence 只记录，�?hardcode 到生产）
```

## 7. Identity 安全

```text
PC adbkey 仅经 run-as 注入 app files/（adbonlyhost + testhost），未写仓库 / evidence
测试结束�?data/local/tmp/at23_* 信号/guard 文件清理
```

## 8. 环境备注

```text
本机网络波动导致一�?BackendParityAuditTest 中断（USB 连接仍在；WD 会话�?WiFi 断）�?
重跑�?34/34 PASS�?81.5s）。无代码问题�?
AOSP adbd 无自动中断机制�?
关于 WD 会话寿命：早期不稳定网络环境曾观察到 Wireless Debugging drop�?
稳定 Wi-Fi / hotspot 下已完成较长时间连续运行（本 evidence �?BackendParityAuditTest
781.5s 全程稳定）；当前没有足够证据证明存在固定 2�? 分钟 MagicOS lifetime�?
本证全部在稳定网络下完成�?
```