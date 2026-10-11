# AT.V3-2.1 — Concurrent ADB Stream Closure Evidence

设备 `APLMUT4312005935`（ALI-AN00 / Android 15）；endpoint `127.0.0.1:38793`；
trusted identity = PC adbkey（测试时 `run-as` 注入，未入库）。
所有用例逐个真机运行；最后由整类运行（同进程 25 tests）佐证稳定性。

---

## 1. Gate A — one-shot 回归（5/5 PASS）

| Test | 结果 | 输出 |
|---|---|---|
| a1_echo | PASS | OK (1 test) |
| a2_principal | PASS | OK (1 test) |
| a3_pwd | PASS | OK (1 test) |
| a4_stderrNonZero | PASS | OK (1 test) |
| a5_connectionReuse | PASS | OK (1 test) |

## 2. Gate B — persistent 回归 + reader cleanup（10/10 PASS）

b1…b8 PASS（OK (1 test)）。

b9/b10 计时证据（logcat `ATV31`）：

```text
10-11 17:57:21.957 I ATV31: b9: close() returned in 1ms
10-11 17:57:23.743 I ATV31: b10: read woke after 1ms, minusOne=true
```

```text
READER_EXIT_AFTER_CLOSE = PASS
STALE_READER_AFTER_CLOSE = NO
```

## 3. Gate C — concurrent multi-session（c1–c8 全 PASS）

| Test | 结果 |
|---|---|
| c1_threeConcurrentSessionsActive | PASS |
| c2_cwdEnvIsolation | PASS |
| c3_stdinIsolation | PASS |
| c4_ctrlCIsolation | PASS |
| c5_resizeIsolation | PASS |
| c6_closeIsolation | PASS |
| c7_reopenWithOtherStreamsActive | PASS |
| c8_naturalExitIsolation | PASS |

### c1 单次运行 transport 证据（logcat AndroidTerminalADB）

```text
[C01] CONNECT_START 127.0.0.1:38793              ← 1 physical connection
[C02] TCP_CONNECTED 127.0.0.1:38793
[C06] TLS_START                                   ← 1 TLS handshake
DoHandshake: TLS 1.3 handshake success (role=Client)
TlsTransport::startTls: TLS 1.3 handshake success  （同一握手的两条日志）
[C13] ONLINE protocol_version=0x01000001
[HUB01] RX_OWNER_START (single transport reader, post-ONLINE rx owner count = 1)
[HUB02] STREAM_OPEN_TX local=1 service="shell,v2,pty:" ack_writes=1
[HUB03] STREAM_ACTIVE local=1 remote=1600
[HUB02] STREAM_OPEN_TX local=2 service="shell,v2,pty:" ack_writes=1
[HUB03] STREAM_ACTIVE local=2 remote=1601
[HUB02] STREAM_OPEN_TX local=3 service="shell,v2,pty:" ack_writes=1
[HUB03] STREAM_ACTIVE local=3 remote=1602
[HUB04] STREAM_CLOSE_LOCAL local=1 remote=1600
[HUB04] STREAM_CLOSE_LOCAL local=2 remote=1601
[HUB04] STREAM_CLOSE_LOCAL local=3 remote=1602
```

```text
PHYSICAL_ADB_CONNECTION_COUNT = 1
TLS_HANDSHAKE_COUNT = 1
CONCURRENT_ADB_STREAM_COUNT = 3
TRANSPORT_RX_OWNER_COUNT = 1
```

## 4. Stability（s1/s2 + 整类）

| 项目 | 结果 | logcat 汇总 |
|---|---|---|
| s1_sequentialLifecycle（10 轮） | PASS | conn=1 tls=1 STREAM_ACTIVE=10 OPEN_FAILED=0 |
| s2_concurrentLifecycle（3 轮并发 + 收尾） | PASS | conn=1 tls=1 STREAM_ACTIVE=10 OPEN_FAILED=0 |
| **整类 25 tests 同进程** | **OK (25 tests)** | CONNECT_START=1 TLS_START=1 RX_OWNER_START=1 STREAM_ACTIVE=60 OPEN_FAILED=0 RX_READ_FAIL=0 |

无 SIGABRT / 无 deadlock / 无 stream crosstalk（隔离断言全过）。

## 5. 回归（不退化）

| 套件 | 结果 |
|---|---|
| LocalPersistentSliceTest | OK (11 tests) |
| AT Engine unit（ATExecutionContractTest + ATSessionContractTest） | 3+3 = 6/6 |
| AppDebugInputTest | OK (7 tests) |
| TestHostHeadlessInputTest | OK (1 test) |
| DynamicSessionPagesTest | OK (9 tests) |

## 6. 归档位置（复跑命令见 AT.V3-2 Usage 文档）

- 单用例日志全文：临时目录（验收会话内）；关键摘录见本文档。
- 生成命令：`adb logcat -d -s AndroidTerminalADB` / `-s ATV31`。
- 报告：`docs/implementation/AT.V3-2.1_ADB_Engine_Concurrent_Stream_Closure.md`