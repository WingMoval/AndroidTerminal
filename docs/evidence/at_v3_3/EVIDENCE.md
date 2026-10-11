# AT.V3-3 — Backend Parity & Contract Closure Audit Evidence

设备 `APLMUT4312005935`（ALI-AN00 / Android 15）。全部用例真机逐个运行。
Endpoint 说明：审计开始 38793；transport-loss 测试有意关闭/恢复 wireless debugging 后，
端口变为 **39731**（hex `9B33`）——恢复验证通过（显式 connect 成功）。

---

## 1. One-shot parity（Local + ADB 同测，5/5 PASS）

| Test | 结果 | 断言要点 |
|---|---|---|
| o1_smallStdout | PASS | COMPLETED / stdout=hello / stderr empty / exit 0 / !hasError |
| o2_stderrSeparation | PASS | stdout empty / stderr=ERRONLY / exit 0 |
| o3_explicitNonZero | PASS | exit 7 → COMPLETED（non-zero ≠ 失败） |
| o4_stdoutStderrNonzero | PASS | OUTO4 / ERRO4 / exit 9 / COMPLETED |
| o5_noOutputSuccess | PASS | empty/empty/0/COMPLETED |

## 2. ADB large-output audit（关键发现）

| Test | 结果 | 证据 |
|---|---|---|
| l1_localLargeOutputBaseline64k | PASS | Local 64 KiB（66560B / 1024 行 / tail 完整）21s |
| **l2_adbLargeOutput64k** | **FAIL** | **300s 超时（test timed out）** |
| l3_adbLargeOutput256k | NOT_RUN | l2 已确认机制，继续跑更大档无新信息（记录理由） |
| l4_adbLargeOutput1m | NOT_RUN | 同上 |
| l5_adbLargeOutputViaPersistentPath64k | PASS | 同一条命令经 persistent 路径（ack_writes=true）**67931 字节 23s 全量到达** |
| p8 LOCAL launch-size | PASS（含 finding） | Local `SZ=[0 0]`（忽略 Launch 尺寸）；ADB `SZ=[33 99]` |

### l2 卡死过程 logcat（35s 时快照）

```text
[HUB02] STREAM_OPEN_TX local=1 service="shell,v2,raw:i=0; while [ $i -lt 1024 ]; do printf '%064d\n' $i; ..." ack_writes=0
RX OKAY arg0=0x00000731 arg1=0x00000001 len=0 tls=1
[HUB03] STREAM_ACTIVE local=1 remote=1841
RX WRTE arg0=0x00000731 arg1=0x00000001 len=70 tls=1
shell v2: stdout +65 bytes (total 65)        ← 只收到第 1 个 WRTE（1 行）
（此后 300 秒无任何 WRTE；host 未回 OKAY credit）
```

### 差分结论（同一连接、同一设备命令）

```text
one-shot 路径（ack_writes=false）→ 1 个 WRTE(70B) 后永久停住
persistent 路径（ack_writes=true）→ 67931B 全量到达
设备端命令基线（USB 标准 adb shell）→ 66560B / 1024 行 / tail 正确
```

```text
ADB_ONESHOT_FLOW_CONTROL_BUG = CONFIRMED
ROOT_CAUSE = one-shot 路径不发送 WRTE/OKAY credit；adbd 在首个未 credit 的 WRTE 后停止发送
MINIMAL_FIX（NEXT，本轮未实施）= one-shot 亦逐 WRTE 回 OKAY（即 ack_writes=true），或等价初始 credit
```

## 3. Persistent parity（Local + ADB 同测，8/8 PASS）

p1_openActiveWrite / p2_cwdEnvContinuity / p3_stdin / p4_ctrlC / p5_resize /
p6_naturalExit / p7_closeReopen / p8_launchInitialSize → 全部 PASS（p8 见 §2）。

- p6 自然退出：双后端均为 `state=EXITED` + `Eof` 事件（无 Failed/Exited 事件——见 §6）。
- p8: `e2` 计时 logcat `ATV33`：close() bounded（LOCAL/ADB 均 < 2.5s 断言通过）。

## 4. EOF / close / state semantics（双后端）

| Test | 结果 | 证据 |
|---|---|---|
| e1_eofState | PASS | 自然 exit → pull `read()` 首次返回 null，state=EXITED；read null 持续；write=-1 |
| e2_closeSemantics | PASS | close 后 state=CLOSED、Closed 事件、read null、write -1、resize false、重复 close 幂等、close bounded |

## 5. Multi-session parity（双后端，3/3 PASS）

| Test | 结果 | 覆盖 |
|---|---|---|
| m1_activeEnvCwdIsolation | PASS | 3 会话并发 ACTIVE；env/cwd 隔离（provider 各自合法目录） |
| m2_stdinCtrlCResizeIsolation | PASS | cat 仅 C 可见；Ctrl-C 仅 C；A/B 存活；40x120 vs 30x100 独立 |
| m3_closeReopenExitIsolation | PASS | close B 不影响 A/C；A/C 存活时重开 D；D exit 不影响 A/C |

## 6. Cross-mode（one-shot + persistent 共存，2/2 PASS）

| Test | 结果 | logcat |
|---|---|---|
| x1_localCrossMode | PASS | （本地无连接概念，独立 PTY/进程） |
| x2_adbCrossMode | PASS | CONNECT_START=1 / TLS_START=1：persistent 存活时 one-shot 复用同一物理连接开新 logical stream，无串扰 |

## 7. read/listener、byte-stream、principal（4/4 PASS）

| Test | 结果 | 证据 |
|---|---|---|
| r1_listenerOwnsStream | PASS | listener 激活时 pull read 返回 null（双后端） |
| r2_pullMode | PASS | 无数据→`ByteArray(0)`（非 null）；有数据→正常返回（双后端） |
| b1_byteStream | PASS | write 返回字节数；0x03 单字节接受；无 command-string API |
| c1_principalDifference | PASS | LOCAL=`uid=10335(u0_a335) ... u:r:untrusted_app:s0`；ADB=`uid=2000(shell) ... u:r:shell:s0` |

## 8. ADB transport-loss（真实断链审计，t1 PASS）

方法：listener 激活中，host（USB shell）执行 `settings put global adb_wifi_enabled 0` →
app 的 loopback ADB transport 被断开 → 记录 → 恢复 `=1`（端口 38793 → 39731）。

```text
before_loss : state=ACTIVE connOnline=true
after_loss  : observed=true state=EXITED eof=true failed=null exited=null closed=false connOnline=true
WRITE_AFTER_LOSS=-1
CONN_STATE_AFTER_LOSS=ONLINE                     ← stale 投影（RUNTIME_FOLLOWUP）
NEW_EXEC_AFTER_LOSS outcome=TRANSPORT_ERROR hasError=true
  error="failed to OPEN shell service: OPEN timeout waiting for OKAY" tookMs=5007
```

恢复验证：显式 connect 新端口 39731 → o1 PASS（手动可恢复；自动 recovery 仍 OUT_OF_SCOPE）。

## 9. Registry 平衡（logcat 汇总）

- whole-class / 压测历史：STREAM_OPEN_TX == STREAM_CLOSE_LOCAL + STREAM_CLOSE_PEER（如 60/60）；
- 正常 lifecycle 清零；极端（peer 不回 CLSE）由 local close/failAll 确定性注销；
- 无 unbounded growth 证据。

## 10. 回归（全部 PASS，不退化）

| 套件 | 结果 |
|---|---|
| ADB_BACKEND (AdbBackendHeadlessTest) | OK (25 tests) |
| LOCAL (LocalPersistentSliceTest) | OK (11 tests) |
| AT_ENGINE (ATExecutionContractTest+ATSessionContractTest) | 6/6 |
| HEADLESS (TestHostHeadlessInputTest) | OK (1 test) |
| APP_DEBUG (AppDebugInputTest) | OK (7 tests) |
| DYNAMIC_SESSION (DynamicSessionPagesTest) | OK (9 tests) |
| PARITY_AUDIT_TESTS (本文件 §1–§7) | 26 PASS / 1 FAIL(l2) / 2 NOT_RUN(l3,l4) |

## 11. 复跑命令

```powershell
$port = "39731"   # 以 adbd 当前无线端口为准（见 usage 文档）
& $adb shell am instrument -w -e class "com.androidterminal.testhost.BackendParityAuditTest#o1_smallStdout" -e port $port -e keyDir /data/user/0/com.androidterminal.testhost/files com.androidterminal.testhost.test/androidx.test.runner.AndroidJUnitRunner
# transport-loss 需宿主编排（见 AdbTransportLossAuditTest 头注释）
```