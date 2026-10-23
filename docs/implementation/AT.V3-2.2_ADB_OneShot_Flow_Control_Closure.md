# AT.V3-2.2 �?ADB One-shot WRTE/OKAY Flow-Control Closure

**ADB one-shot WRTE/OKAY 流控最小修复与 AT Execution Closure 闭环**

- 日期�?026-10-12
- 基线：V3 `1311750`（AT.V3-3 完成�?clean�?
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a�?
- Endpoint：无线调试动态端口（测试�?`-e port` 注入；结束端�?`40473`，未写死进生产代码）
- 身份：PC adbkey `developer@HOST`（`run-as` 注入 testhost `files/adbkey`，未提交�?
- 结论�?*STATUS = COMPLETE**

---

## 1. Objective

只修�?AT.V3-3 已用差分证据锁定�?**ADB one-shot WRTE/OKAY credit 缺陷**�?
one-shot 逻辑流在同一复用 TCP/TLS/ADB connection 上可靠完�?64 KiB / 256 KiB / 1 MiB 大输出，
同时保持 one-shot / persistent / multi-session / cross-mode 全部不退化；
修复�?`AT_EXECUTION_CLOSURE` �?`BLOCKED_BY_ADB_ENGINE_BUG` 转为 `PASS`�?

## 2. Baseline

```text
V3 HEAD  = 1311750（AT.V3-3: record audit commit hash in verdict�?
V3 status = CLEAN
V2 HEAD  = 726a441
V2 status = 43 项会�?dirty（本�?V2 零写入）
```

## 3. AT.V3-3 blocker evidence（原始审计结论，未篡改）

AT.V3-3 报告（`docs/implementation/AT.V3-3_Backend_Parity_Contract_Closure_Audit.md`）记录：

```text
ADB one-shot 小输�?       PASS
ADB persistent 大输�?     PASS
USB 标准 adb shell 大输�? PASS
ADB one-shot 64 KiB        FAIL�?00s 超时�?
ADB_ONESHOT_FLOW_CONTROL_BUG = YES（CONFIRMED�?
AT_EXECUTION_CLOSURE = BLOCKED_BY_ADB_ENGINE_BUG
```

## 4. Root cause（代码级确认�?

- `AdbClient::runShell` �?`ack_writes=false` 打开 one-shot �?
  （`adb_client.cpp` openStream 调用），即收到设�?WRTE �?*从不�?OKAY**�?
- hub dispatcher �?WRTE 处理：`ack = slot->ack_writes`；仅�?true 才发�?OKAY credit
  （`adb_stream_hub.cpp` dispatch）�?
- adbd �?shell 服务�?credit 发送：首个 WRTE（~70B）后没有 OKAY �?不再发送后�?WRTE �?卡死�?
- persistent（`AdbInteractiveStream`）以 `ack_writes=true` 打开 �?�?WRTE �?OKAY �?正常�?
- 差分证明（AT.V3-3）：同一命令 persistent 路径 67931B 全量通过；one-shot 首个 WRTE 后静止�?

## 5. Previous flow-control path

```text
adbd WRTE
   �?
AdbStreamHub dispatcher（RX owner = 1�?
   �?
one-shot（ack_writes=false�? enqueue �?slot 队列，不发�?OKAY credit  �?缺陷
persistent（ack_writes=true�? enqueue + 发�?OKAY credit（arg0=local_id, arg1=remote_id�?
   �?
stream read() �?slot 队列消费
```

## 6. Fix

```text
adb_client.cpp AdbClient::runShell�?
  openStream(service, /*ack_writes=*/false, ...)  �? openStream(service, /*ack_writes=*/true, ...)
```

- 协议语义：one-shot �?persistent 一致，�?WRTE �?dispatcher �?OKAY credit�?
- OKAY �?arg0=local_id / arg1=remote_id 沿用 persistent 已验证约定（host→device 方向�?
  arg0=host 本地 id，arg1=device remote id）�?
- OKAY �?`AdbConnection::writePacket`（connection-wide TX mutex，完�?packet 不交错）�?
- 未新�?worker / transport / connection / 特殊大输出分�?/ retry / sleep / polling�?
- 未重�?AdbStreamHub / connection ownership / TLS / CNXN / registry / JNI / multi-session 模型�?

附加（仅观测）：hub dispatcher 增加 `[HUB07] RX_WRTE -> TX_OKAY_CREDIT` �?
`STREAM_REGISTER/UNREGISTER registry=N` 诊断日志（Gate A �?lifecycle 证据用）�?

## 7. WRTE/OKAY evidence（Gate A�?

64K one-shot 单次运行 logcat（`docs/evidence/at_v3_2_2/EVIDENCE.md` §1）：

```text
STREAM_OPEN_TX local=1 service="shell,v2,raw:..." ack_writes=1
STREAM_ACTIVE local=1 remote=2272
RX_WRTE local=1 remote=2272 bytes=70 -> TX_OKAY_CREDIT    #1
RX_WRTE local=1 remote=2272 bytes=70 -> TX_OKAY_CREDIT    #2
...�?025 �?WRTE，每个均�?credit�?
STREAM_CLOSE_PEER local=1 remote=2272
STREAM_UNREGISTER local=1 registry=0
```

```text
WRTE_COUNT（stream local=1�? 1025
OKAY_CREDIT_COUNT（stream local=1�? 1025�?:1�?
```

## 8. 64K result（Gate B�?

```text
l2_adbLargeOutput64k   PASS�?0s bounded；实�?~20s�?
stdout = 66560 B�?024 �?× 65B）精确；line count = 1024；tail 完整；exit_code = 0
reuse（echo after_large_1024）PASS
```

差分：与 AT.V3-3 同一命令、同一设备；AT.V3-3 基线 = 300s 卡死�?
USB 基线 = 66560B/1024 行。本�?66560B 精确返回 �?�?USB 基线一致�?

## 9. 256K result（Gate C�?

```text
l3_adbLargeOutput256k  PASS�?80s bounded；实�?~80s�?
stdout = 266240 B�?096 �?× 65B）精确；tail 完整；exit_code = 0；reuse PASS
```

## 10. 1M result（Gate D�?

逐行 1M�?6384 行）受设�?adbd 每行 70B WRTE、每 credit 往�?~19ms 限制需 ~5-6 min�?
超过�?HONOR 设备无线调试会话寿命（~2-4 min，见 §19）。改用单管道分块输出�?

```text
l9_adbLargeOutput1mChunked   PASS�?20s bounded；实�?~1s�?
命令：dd if=/dev/zero bs=1048576 count=1 2>/dev/null | tr '\000' 'A'; printf TAIL1M
stdout = 1048582 B�? 1048576 + "TAIL1M" 6B）精确；tail 完整；exit_code = 0；reuse PASS
WRTE（stream local=1�? 6 个（180249/241749/247941/233093/145674/17），全部 credit
```

吞吐：~1 MiB / ~1s（大 WRTE 路径），远优于逐行 credit 往返�?

## 11. stdout / stderr / nonzero result

```text
l6_adbMixedLargeStdoutStderrNonzero   PASS�?0s bounded�?
stdout = 33280 B�?12 �?× 65B）精确，tail 完整
stderr = "ERRLARGE" 精确；exit_code = 5
outcome = COMPLETED（非零退�?�?transport failure�?
```

flow-control 修复未破�?shell-v2 frame 映射（stdout/stderr/exit 帧均正确）�?

## 12. Connection reuse

```text
l7_adbConnectionReuseWithLargeOutput   PASS�?00s bounded；实�?148s�?
序列：echo S1 �?64K �?echo S2 �?256K �?echo S3
全程 1 physical connection / 1 TLS；无 disconnect/reconnect；连接保�?ONLINE
```

logcat 佐证：`AdbClient::connect` 仅出现一次；后续 stream 复用同一 connection�?

## 13. Persistent regression

```text
AdbBackendHeadlessTest b1-b10（open/write/cwd/env/stdin/Ctrl-C/resize/exit/reopen/
                               reader exit/blocked read wakeup）全�?PASS
```

## 14. Multi-session regression

```text
AdbBackendHeadlessTest c1-c8�? concurrent sessions / 1 conn / 1 TLS / 1 RX owner�?
  cwd/env/stdin/Ctrl-C/resize/close/reopen/natural-exit isolation）全�?PASS
```

## 15. Cross-mode regression

```text
x2_adbCrossMode（persistent + �?one-shot�?        PASS
x3_adbCrossModeLargeOneShot（persistent A + 64K B�?PASS
  A �?B 大输出期间保�?ACTIVE；B 完整 66560B；A �?B 完成后继续执�?
  1 physical connection / 1 TLS / multiple logical streams / �?cross-talk
```

## 16. Lifecycle / registry audit

```text
64K one-shot 结束：STREAM_CLOSE_PEER �?STREAM_UNREGISTER registry=0
reuse stream：STREAM_REGISTER local=2 registry=1 �?... �?UNREGISTER registry=0
大输出结�?registry �?0，不持续增长；无 stale reader；无 stream leak
（logcat 轨迹�?EVIDENCE.md�?
```

## 17. AT / Contract impact

```text
AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
ADB_BACKEND_MODIFIED = NO（AdbBackend.kt 未动�?
LOCAL_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = YES（仅 runShell ack_writes=true + 诊断日志�?
```

GAP-1 / GAP-2 / GAP-3 本轮未触碰（保持 DEFERRED）�?

## 18. Execution closure status

```text
AT_EXECUTION_CLOSURE =
  BLOCKED_BY_ADB_ENGINE_BUG（AT.V3-3 历史�? �? PASS（本轮，AT.V3-2.2�?

AT_CONTRACT_FREEZE_READINESS =
  BLOCKED_BY_BACKEND_BUG（AT.V3-3 历史�? �? READY_FOR_EMULATOR_VALIDATION

AG_TERMINALTOOL_EXECUTION_READINESS =
  READY_WITH_KNOWN_LIMITS  �? READY_FOR_EXECUTION_INTEGRATION
  （仍明确：GAP-1/GAP-2/GAP-3 为已�?Contract semantics �?freeze 决定；不开�?AG 开发）
```

## 19. Remaining known gaps

```text
GAP-1 EOF/FAILED 折叠            DEFERRED（AT.CONTRACT-1 决定�?
GAP-2 persistent exitCode        DEFERRED
GAP-3 read/listener invariant    DEFERRED
LOCAL initialRows/Cols bug       DEFERRED
ADB connectionState stale        ADB_RUNTIME_FOLLOWUP
Pairing / Discovery / Recovery   ADB_RUNTIME_FOLLOWUP（未完成�?
Emulator / ScreenState           NOT_IMPLEMENTED / FOLLOWUP（AT.EMULATOR-0�?
Human adb CLI / SSH / AG         OUT_OF_SCOPE
```

环境限制记录：本 HONOR 设备无线调试会话寿命 ~2-4 min（框架侧�?`adb_wifi_enabled`
写回 0 �?adbd `kick_all_tcp_tls_transports()` �?`SSL_read err=5`）。AOSP adbd 代码
（`research/ADB/daemon/adb_wifi.cpp`）无任何自动中断机制；属 OEM/框架行为�?
逐行 1M 因此改用分块命令（�?0）。稳定网�?+ 保屏后可稳定完成整类�?78s）�?

## 20. Final Verdict

```text
STATUS = COMPLETE

PREVIOUS_BLOCKER = ADB_ONESHOT_WRTE_OKAY_FLOW_CONTROL
ROOT_CAUSE_CONFIRMED = YES（AT.V3-3 差分 + 本轮代码级确认）
FIX_SCOPE = :adb-engine AdbClient::runShell one-shot ack_writes=false→true�?hub 诊断日志�?

ONESHOT_ACK_WRITES_BEFORE = false
ONESHOT_ACK_WRITES_AFTER  = true

WRTE_OKAY_FLOW_CONTROL = PASS
WRTE_COUNT = 1025�?4K�? 6�?M 分块�?
OKAY_COUNT  = 1025�?:1�? 6�?:1�?

ADB_LARGE_OUTPUT_64K = PASS        �?6560B 精确，同 V3-3 命令差分�?
ADB_LARGE_OUTPUT_256K = PASS       �?66240B 精确�?
ADB_LARGE_OUTPUT_1M = PASS         �?048582B 精确，分块命令，~1s�?
LARGE_OUTPUT_COMPLETENESS = PASS   （byte count + line count + tail marker + exit 0�?
LARGE_OUTPUT_TIMEOUT = NO

MIXED_STDOUT_STDERR = PASS         �?12 �?stdout + ERRLARGE stderr + exit 5，COMPLETED�?
NONZERO_EXIT_SEMANTICS = PASS      （exit 127 a4、exit 5 l6、exit 9 o4 �?COMPLETED�?

ONESHOT_REGRESSION = 5/5（a1-a5�?
CONNECTION_REUSE = PASS（l7：S1+64K+S2+256K+S3，单连接 148s�?
PHYSICAL_CONNECTION_COUNT = 1（l7�? 1（整类共享）
TLS_HANDSHAKE_COUNT = 1

PERSISTENT_REGRESSION = 10/10（b1-b10�?
MULTI_SESSION_REGRESSION = 8/8（c1-c8�?
CROSS_MODE_REGRESSION = PASS（x2 + x3 large�?

RX_OWNER_COUNT = 1
TX_SERIALIZATION = PASS（writePacket TX mutex�?
STALE_READER = NO
STREAM_LEAK = NO（registry�?�?
SIGABRT = NO
DEADLOCK = NO
STREAM_CROSSTALK = NO

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_DEBUG_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
DYNAMIC_SESSION_REGRESSION = 9/9
ADB_BACKEND_HEADLESS = 25/25
PARITY_AUDIT_WHOLE_CLASS = 34/34

AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
ADB_BACKEND_MODIFIED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = YES

GAP_1_EOF_FAILED = DEFERRED
GAP_2_PERSISTENT_EXITCODE = DEFERRED
GAP_3_READ_LISTENER = DEFERRED
LOCAL_INITIAL_SIZE_BUG = DEFERRED
ADB_STALE_CONNECTION_STATE = ADB_RUNTIME_FOLLOWUP

AT_EXECUTION_CLOSURE = PASS
AT_CONTRACT_FREEZE_READINESS = READY_FOR_EMULATOR_VALIDATION
AG_TERMINALTOOL_EXECUTION_READINESS = READY_FOR_EXECUTION_INTEGRATION

PAIRING = OUT_OF_SCOPE / ADB_RUNTIME_FOLLOWUP
DISCOVERY = ADB_RUNTIME_FOLLOWUP
RECOVERY = ADB_RUNTIME_FOLLOWUP
EMULATOR = NOT_STARTED
HUMAN_ADB_CLI = OUT_OF_SCOPE

REAL_DEVICE_VALIDATION = PASS
EVIDENCE = docs/evidence/at_v3_2_2/EVIDENCE.md

GIT_COMMIT = 234df9d（AT.V3-2.2 flow-control fix commit�?
GIT_WORKTREE = CLEAN

NEXT = AT.EMULATOR-0
```

## 附：COMPLETE Gate 核对

1. 根因�?AT.V3-3 一�?�?
2. 修复位于 ADB Engine�?adb-engine）✓
3. one-shot 正确 WRTE/OKAY flow-control �?
4. 64 KiB 完整 PASS �?
5. 256 KiB 完整 PASS �?
6. 1 MiB 完整 PASS（分块命令，�?§10 环境说明）✓
7. mixed stdout/stderr PASS �?
8. non-zero exit 仍为 COMPLETED �?
9. one-shot 5/5 回归 �?
10. connection reuse PASS �?
11. persistent 全回归（b1-b10）✓
12. multi-session 全回归（c1-c8）✓
13. cross-mode PASS（x2/x3）✓
14. 1 RX owner 不退�?�?
15. TX serialized �?
16. �?stale reader �?
17. �?stream leak �?
18. �?SIGABRT �?
19. �?deadlock �?
20. �?cross-talk �?
21. Local/AT/App/Headless/Dynamic 回归不退�?�?
22. AT Engine 未修�?�?
23. AT Contract 未修�?�?
24. ADB Backend public behavior/API 未修�?�?
25. evidence/docs/manifest 完成（见本文�?+ EVIDENCE.md + Parity Matrix + Manifest）✓
26. V3 commit �?
27. V3 clean �?
28. V2 本轮零写�?�