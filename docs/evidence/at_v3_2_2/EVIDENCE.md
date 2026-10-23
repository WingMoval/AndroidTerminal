# AT.V3-2.2 �?Evidence: ADB One-shot WRTE/OKAY Flow-Control Closure

> 真机证据�?026-10-12）。设�?`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）�?
> 无线调试端口动态变化：本批测试最终端�?`40473`（不可写死进生产代码）�?
> 身份：PC adbkey `developer@HOST` �?`run-as com.androidterminal.testhost` 注入 `files/adbkey`（未提交）�?

## 0. 修复

- `adb-engine/src/main/cpp/adb/client/adb_client.cpp` `AdbClient::runShell`�?
  `openStream(service, /*ack_writes=*/false, ...)` �?`/*ack_writes=*/true`
- 语义：one-shot 逻辑流与 persistent 一致，�?WRTE �?hub dispatcher �?OKAY credit
  （经 connection-wide TX mutex，完�?packet 不交错）�?
- 诊断日志（仅观测）：`[HUB07] RX_WRTE ... -> TX_OKAY_CREDIT`（Gate A 计数）�?
  `[HUB07] STREAM_REGISTER/UNREGISTER ... registry=N`（lifecycle 轨迹）�?

## 1. Gate A �?WRTE/OKAY credit 序列�?4K one-shot，logcat 摘录�?

```text
[HUB02] STREAM_OPEN_TX local=1 service="shell,v2,raw:i=0; while [ $i -lt 1024 ]; do printf '%064d\n' $i; i=$((i+1)); done" ack_writes=1
[HUB03] STREAM_ACTIVE local=1 remote=2272
[HUB07] RX_WRTE local=1 remote=2272 bytes=70 -> TX_OKAY_CREDIT     <- WRTE #1, credit #1
[HUB07] RX_WRTE local=1 remote=2272 bytes=70 -> TX_OKAY_CREDIT     <- WRTE #2, credit #2
...�?024 × 70B �?+ 1 × 6B = 66560B�?
[HUB07] RX_WRTE local=1 remote=2272 bytes=6 -> TX_OKAY_CREDIT      <- 最�?6B（EXIT �?尾部�?
[HUB04] STREAM_CLOSE_PEER local=1 remote=2272
[HUB07] STREAM_UNREGISTER local=1 registry=0
runShell: done exit_code=0 stdout=66560 bytes stderr=0 bytes
```

计数（同次运行）�?
- `WRTE_COUNT (stream local=1) = 1025`
- `OKAY_CREDIT_COUNT (stream local=1) = 1025`�?:1；reuse stream local=2 另有 2 �?credit�?
- 结论：WRTE_COUNT > 1 且每�?WRTE 均获 credit —�?credit 协议正确�?

## 2. Gate B �?64 KiB（与 AT.V3-3 同一命令差分�?

```text
l2_adbLargeOutput64k            PASS�?0s bounded�?
stdout = 66560 B�? 1024 �?× 65B）精�?
exit_code = 0
tail = 最后一�?64 位零填充 + \n 完整
reuse（echo after_large_1024）PASS
```

AT.V3-3 基线对照：同命令 USB 基线 66560B/1024 行（PASS）；one-shot 旧行�?300s 卡死�?
本轮 66560B 精确返回，与 USB 基线一�?�?ADB_ONESHOT_FLOW_CONTROL_BUG 已消除�?

## 3. Gate C �?256 KiB

```text
l3_adbLargeOutput256k           PASS�?80s bounded�?
stdout = 266240 B�? 4096 �?× 65B）精�?
exit_code = 0
tail 完整；reuse PASS
```

## 4. Gate D �?1 MiB

逐行 1M�?6384 行）需�?~5-6 min（设�?adbd 每行 70B WRTE、每 credit 往�?~19ms），
超过�?HONOR 设备无线调试会话寿命（~2-4 min，见 §9）——环境限制，已记录�?
改用单管道分块输出（�?WRTE），1 MiB �?~1s 内完成：

```text
l9_adbLargeOutput1mChunked      PASS�?20s bounded，实�?~1s�?
命令：dd if=/dev/zero bs=1048576 count=1 2>/dev/null | tr '\000' 'A'; printf TAIL1M
stdout = 1048582 B�? 1048576 + "TAIL1M" 6B）精�?
tail = "TAIL1M" 完整；exit_code = 0；reuse（echo after_1m_chunked）PASS
WRTE（stream local=1�? 个，全部 credit）：
  180249 + 241749 + 247941 + 233093 + 145674 + 17
```

## 5. Mixed stdout + stderr + non-zero exit

```text
l6_adbMixedLargeStdoutStderrNonzero   PASS�?0s bounded�?
命令：gen(512) + "; printf ERRLARGE 1>&2; exit 5"
stdout = 33280 B�?12 �?× 65B）精确，tail 完整
stderr = "ERRLARGE" 精确；exit_code = 5
outcome = COMPLETED（非零退�?�?transport failure�?
```

## 6. Connection reuse（跨大小交错，单连接�?

```text
l7_adbConnectionReuseWithLargeOutput  PASS�?00s bounded，实�?148s�?
序列：echo S1 �?64K �?echo S2 �?256K �?echo S3
全部 COMPLETED + 输出精确；连接全�?ONLINE�? physical conn / 1 TLS�?
```

## 7. Cross-mode（persistent + large one-shot 共存�?

```text
x3_adbCrossModeLargeOneShot      PASS�?20s bounded�?
persistent session A ACTIVE 期间执行 one-shot 64K
B 完整 66560B；A 保持 ACTIVE；A �?B 完成后继续执行（PERSIST_AFTER_X3=5�?
```

## 8. 失败路径（one-shot 中途异常关闭）

```text
f1_adbOneShotMidStreamClose      PASS�?0s bounded，实�?~3s�?
命令：echo PARTIAL; exec 1>&- 2>&-; sleep 2
观测：exec 关闭 stdout/stderr 后，adbd 保持流直至进程退出（sleep 2 完成），
     未强制提�?CLSE；exit_code=0，stdout="PARTIAL\n"�?B）完整；
     reuse（echo AFTER_F1）PASS�?
结论：bounded、无 hang、无 crash、连接复用。本设备 adbd 不会�?fd 关闭提前 CLSE
     （记录为观测行为；真正的 mid-stream 异常关闭需 transport 层，�?AT.V3-3 t1）�?
```

## 9. 无线调试会话寿命（环境限制，非代码问题）

- 逐行 1M（~5-6 min）期间无线监听器�?HONOR 框架关闭（`adb_wifi_enabled` 写回 0
  �?adbd `disable_wifi_debugging()` �?`kick_all_tcp_tls_transports()` �?`SSL_read err=5`）�?
- AOSP 代码核对（`research/ADB`，本地原�?adb）：adbd 侧无任何自动中断/超时机制�?
  唯一踢连接路�?= `persist.adb.tls_server.enable` 属性变 0（`daemon/adb_wifi.cpp`
  `start_wifi_enabled_observer` / `disable_wifi_debugging`）。框架侧不在本地
  （`AdbDebuggingManager`），AOSP 无会话时长自动关闭；HONOR MagicOS 存在 OEM 修改�?
- 缓解：稳定网�?+ `svc power stayon true` + 启用后立即执行。换稳定网络�?
  l7�?48s�?整类�?78s）均稳定完成�?

## 10. 回归汇总（本轮全部真机�?

```text
AdbBackendHeadlessTest（a1-a5 + b1-b10 + c1-c8 + s1/s2�?25/25 PASS�?0s�?
BackendParityAuditTest 整类                               34/34 PASS�?78s�?
LocalPersistentSliceTest                                  11/11 PASS
ATContractTest（JVM unit�?+3�?                           6/6  PASS
TestHostHeadlessInputTest                                  1/1  PASS
AppDebugInputTest                                          7/7  PASS
DynamicSessionPagesTest                                    9/9  PASS

Gate A：WRTE/OKAY 1:1 credit 证实
Gate B 64K�?6560B 精确（同 V3-3 命令差分一致）
Gate C 256K�?66240B 精确
Gate D 1M（分块）�?048582B 精确
mixed / reuse / cross-mode / failure-path：全 PASS
1 RX owner / TX serialized / registry�? / �?stale reader / �?leak / �?SIGABRT / �?deadlock / �?cross-talk
```

## 11. 修改边界

```text
AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
ADB_BACKEND_MODIFIED = NO（AdbBackend.kt / AdbOneShotBackend 未动�?
LOCAL_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = YES（仅 runShell ack_writes + 诊断日志�?
:test-host = 测试更新（bounded timeout + 新用�?l6/l7/l9/x3/f1�?
```

## 12. 端口记录

```text
AT.V3-2.2 结束时无线调试端�?= 40473（动态；测试�?-e port 注入，未写死�?
```