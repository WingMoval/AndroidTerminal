# AT.V3-2.1 — ADB Engine Concurrent Stream Closure

**ADB Engine 并发 Stream 读所有权修复与 AT.V3-2 Gate C 闭环**

- 日期：2026-10-11
- 基线：V3 `c4fdf9e`（AT.V3-2 完成后 clean）
- 设备：`APLMUT4312005935`（HONOR ALI-AN00 / Android 15 / arm64-v8a）
- Endpoint：`127.0.0.1:38793`（adbd uid 2000 实际无线监听端口，hex `9789`）
- Identity：PC 已信任 adbkey（`Wing@DESKTOP-PG78M2N`，测试时经 `run-as` 注入，未提交）
- 结论：**STATUS = COMPLETE**（AT.V3-2 Gate C = CLOSED）

---

## 1. Objective

将 ADB Engine 从「每个 logical stream 自己读取共享 TLS connection」的单-reader 假设实现，
演进为 **一个 ONLINE connection 唯一 RX owner + packet dispatcher + per-stream queue** 的
真正多 stream multiplexing，并在一条 TCP/TLS/ADB connection 上同时运行至少三个独立 remote PTY，
关闭 AT.V3-2 唯一剩余的 Gate C。

约束（遵守）：

```text
AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_BACKEND_PUBLIC_CONTRACT_CHANGED = NO
本轮主要修改 :adb-engine（新增 AdbStreamHub；AdbClient/AdbInteractiveStream/JNI/transport 最小接线）
```

未触发任何需要 STOP 的 AT Contract 修改。

## 2. Previous blocker（AT.V3-2 记录）

```text
Gate A one-shot                    PASS
Gate B persistent single session  PASS
Gate C multi-session              BLOCKED

第二个并发 interactive open 失败（"ADB interactive open failed"），
随后进程崩溃（BoringSSL SIGABRT，栈在 SSL_read）；
persistent reader 在 close 后 join 超时，残留线程继续占用连接。
```

## 3. Root cause（本轮代码级确认）

以 AT.V3-2 基线代码逐条核实（非任务文本臆造）：

1. **多 read owner**：`AdbClient::runShell`（one-shot）与 `AdbInteractiveStream::pumpPacket`
   （persistent）各自在调用线程直接 `AdbConnection::readPacket()` → `SSL_read`。
   同一 SSL connection 上并发 `SSL_read`（以及一个线程阻塞 `SSL_read` 时另一线程
   `SSL_write`/`SSL_shutdown`）在 BoringSSL 中属未定义行为。
2. **OPEN 竞争**：`AdbInteractiveStream::open` 由调用线程 `readPacket` 等 OKAY；
   第二个 stream 的 OPEN-wait 与第一个 stream 的 read 同时消费 transport 字节流，
   互相吞掉对方 packet → 第二个 open 读不到自己的 OKAY → 失败；随后 `disconnect()`
   在另一个线程阻塞于 `SSL_read` 时执行 `tls_.reset()`（`SSL_shutdown` 写 + `SSL_free`）
   → SIGABRT。
3. **无法唤醒的阻塞读**：`TlsConnection::ReadFully` 对 `SSL_ERROR_WANT_READ` 无限
   `continue`（`SO_RCVTIMEO=5s` 只让底层 recv 周期性 EAGAIN，不构成语义超时）；
   `TcpTransport::close()` 只 `::close(fd)` 不 `::shutdown(fd)`，**无法唤醒**另一个线程中
   阻塞的 `recv`/`SSL_read` → reader 永久残留（join 超时）。
4. one-shot 完成后**从不 ack 设备 WRTE**（依赖小输出初始额度），persistent 则逐 WRTE ack —
   两条路径的 WRTE/OKAY 行为不同（本轮保持各自行为，不做统一）。

## 4. Previous ownership model

```text
AdbConnection（transport）
   ├── one-shot runShell      → readPacket()（调用线程）
   ├── AdbInteractiveStream A → readPacket()（reader 线程 A）
   └── AdbInteractiveStream B → readPacket()（reader 线程 B）   ← 冲突
```

- readPacket 无所有权约束；writePacket 无串行化（多 stream 并发写可交错）。
- connection 状态（Online）不因 reader 崩溃/残留而更新。

## 5. New RX ownership model

```text
ONLINE 之后：

AdbConnection（transport，仅被动 I/O）
   ▲ writePacket（connection-wide TX mutex，完整 packet 不交错）
   │
AdbStreamHub（新增 adb/connection/adb_stream_hub.*）
   ├── RX thread（唯一 readPacket 调用者，post-ONLINE RX owner = 1）
   │     readPacket → classify(OKAY/WRTE/CLSE) → registry lookup → per-stream queue + notify
   ├── Stream Registry（local_id → AdbStreamSlot，shared_ptr）
   ├── failAll（connection fatal → 全部 slot Failed + 唤醒）
   └── shutdown（stopping → failAll → socket shutdown → join RX → guard.alive=false）
         │
AdbStream A/B/C（read=slot queue；writeRaw=WRTE；close=CLSE+unregister）
         │
AdbInteractiveStream / AdbClient::runShell（消费者，只看自己的队列）
```

关键不变式：

- ONLINE 后 `readPacket` **只有一个 owner**（hub RX 线程）；
- 所有 logical stream（one-shot + persistent + 未来类型）统一经 `AdbStreamHub::openStream`；
- stream 消费者阻塞在**自己的 slot condvar**，绝不阻塞在 transport；
- transport 拆除顺序 = 先 `shutdown(2)` 唤醒 RX → join RX → 再 `tls_.reset()/close(fd)`，
  消除「另一线程 SSL_read 时 reset SSL」的 SIGABRT 场景。

实现文件：

- 新增 `adb-engine/src/main/cpp/adb/connection/adb_stream_hub.{h,cpp}`
- 修改 `adb/connection/adb_connection.{h,cpp}`（TX mutex、shutdownSocket、readPacket 所有权注释）
- 修改 `adb/transport/tls_transport.{h,cpp}`（shutdownSocket → fd-level shutdown）
- 修改 `transport/tcp_transport.{h,cpp}`（`shutdown()`：`::shutdown(fd, SHUT_RDWR)`）
- 修改 `adb/client/adb_client.{h,cpp}`（hub 生命周期；runShell 改走 hub）
- 修改 `adb/client/adb_interactive_stream.{h,cpp}`（经 hub 消费；read 语义=队列唤醒）
- 修改 `jni/adb_engine_jni.cpp`（g_streams=shared_ptr+mutex；interactiveOpen 用 hub）
- 修改 `adb-engine/src/main/cpp/CMakeLists.txt`（加入 adb_stream_hub.cpp）

## 6. Stream registry

`AdbStreamHub` 内 `unordered_map<uint32_t, shared_ptr<AdbStreamSlot>>`（registry mutex 保护）。
每个 `AdbStreamSlot` 表示一个 logical ADB stream：

```text
local_id / remote_id          ADB stream ids
state                         Opening → Active → Closed / Failed
ack_writes                    WRTE 是否回 OKAY（interactive=true，one-shot=false）
clse_sent / clse_received     CLSE 双向去重
close_reason                  关闭/失败原因（read 错误信息）
rx_chunks                     per-stream 原始 WRTE payload 队列（deque）
mu / cv                       per-stream 互斥 + 唤醒
guard                         shared_ptr<AdbConnGuard>（hub/connection 拆除后 TX 安全失败）
```

生命周期：全部 shared_ptr（registry 持一份、AdbStream 持一份、dispatch 临时持一份）；
stream close / 远端 CLSE / open 超时 / failAll 均在 registry 注销；无裸指针、无 dangling。

## 7. Packet dispatch

RX loop 收包后按 host 侧寻址约定分发（peer→host：`arg1` = 我们的 local id）：

| 包 | 处理 |
|---|---|
| OKAY（Opening） | remote_id = arg0；state=Active；notify（OPEN 等待者唤醒）；`[HUB03] STREAM_ACTIVE` |
| OKAY（Active） | WRTE 信用确认，忽略（日志） |
| WRTE | payload 入 slot 队列；若 `ack_writes` → 由 dispatcher 回 OKAY 信用（interactive 路径）；notify |
| CLSE | clse_received=true；Opening→Closed("OPEN rejected")；Active→Closed("stream closed by peer")；registry 注销；notify |
| 其他 | 忽略（日志），不重写协议 |

复用现有 `AdbPacket` / `AdbCommand` / `CommandToString` / magic / checksum 实现，未改协议。

## 8. Interactive read semantics（修复点之一）

```text
旧：AdbInteractiveStream::read → pumpPacket → conn->readPacket()（阻塞 SSL_read）
新：AdbInteractiveStream::read → 自己的 pending_（shell-v2 已解析 stdout+stderr）
                              → 否则 stream_->read()（slot queue condvar，带真实超时）
```

- data arrives → slot notify → read 返回数据（shell-v2 持久解析器解析后累积）；
- stream CLSE → slot Closed → read 返回 -1（EOF）；
- session close → slot Closed → notify → 阻塞中的 read 立即返回 -1；
- connection failure → failAll → 立即返回 -1（error=close_reason）；
- 超时语义真实生效（0 = timeout），不再依赖「网络 read 以后返回」。
- shell-v2 解析改为 `ShellV2Protocol`（持久缓冲），消除旧实现「单包内遇到 partial frame 直接
  丢弃」的隐患；frame 解析/结果映射语义不变。

## 9. close / wakeup semantics

- `AdbStream::close()`：仅当 peer 未先 CLSE 且未发过 CLSE 时发送 CLSE（协议去重）；
  state→Closed；从 registry 注销；**notify slot cv**（唤醒阻塞 reader）。
- `AdbStreamHub::shutdown()`：stopping → failAll("connection closing") → `conn->shutdownSocket()`
  （`::shutdown(fd, SHUT_RDWR)` 唤醒阻塞在 SSL_read 的 RX）→ join RX → guard.alive=false。
- RX fatal error：failAll("connection failed: …") → 所有 stream Failed + 全员唤醒 → RX 退出。
- 已真机验证：`close() returned in 1ms`；`blocked read woke after 1ms, result=-1`（见 §14）。
- 连接拆除顺序保证：`tls_.reset()` 只在 RX owner join 之后执行（无并发 SSL_* 调用）。

## 10. TX serialization audit

- 结论：此前 `writePacket` **无** connection-wide 串行化；本轮加入
  `AdbConnection::writePacket` 内 `tx_mutex_`（整包 encode + transport write 持锁）。
- 覆盖全部 post-ONLINE 写路径：hub OPEN / stream WRTE / dispatcher OKAY 信用 /
  CLSE / one-shot 与 interactive 一致。
- RX = exactly one owner；TX = packet writes serialized（按任务 §15 原则，未引入 TX worker）。
- 未把 TX mutex 扩到与 read 互斥（AT.V3-2 曾尝试读+写共用 g_io_mutex → 死锁，已证否）；
  读线程只在 slot 队列等待，写不依赖读。

## 11. one-shot integration

`AdbClient::runShell` 重写为 hub 消费者（§16 of task）：

```text
openStream("shell,v2,raw:<cmd>", ack_writes=false, 5000ms)   ← OPEN/OKAY 全经 dispatcher
loop: stream->read(4096, 5000ms) → ShellV2Protocol.feed → stdout/stderr/exit 累积
terminate: remote CLSE → read -1（failed=false 视为正常结束；failed=true → TransportError）
finish: stream->close()
```

- 语义保持：nonzero exit = COMPLETED；transport/protocol 错误与设备 stderr 分离；
- OPEN 5s 超时保留（现在由 hub openStream 统一实现）；
- shell_v2 解码器、result 映射、AT result 语义未重写（§17 要求）。

## 12. Gate A regression（one-shot）

逐个用例真机运行（端点 38793，trusted identity）：

| 用例 | 结果 | 证据 |
|---|---|---|
| a1_echo | PASS | stdout contains hello，exit 0 |
| a2_principal | PASS | `uid=2000(shell)` |
| a3_pwd | PASS | pwd 非空 |
| a4_stderrNonZero | PASS | exit 127，stderr 有 "not found"，COMPLETED |
| a5_connectionReuse | PASS | 3 连发复用同连接 |

`ONESHOT_REGRESSION = 5/5`；`CONNECTION_REUSE = PASS`。

## 13. Gate B regression（persistent single session）

| 用例 | 结果 |
|---|---|
| b1_openActive / b2_writeOutput / b3_cwdContinuity / b4_envContinuity | PASS |
| b5_stdin / b6_ctrlC / b7_ttyAndResize（/dev/pts/*，resize 40x120 生效） | PASS |
| b8_exitCloseReopen（自然 exit → EXITED；重开 OK） | PASS |

`PERSISTENT_REGRESSION = 8/8`；`REMOTE_PTY = PASS`（`/dev/pts/*`，`stty size` 真实）。

新增 reader cleanup focused cases：

| 用例 | 结果 | 记录 |
|---|---|---|
| b9_readerExitAfterClose | PASS | `close() returned in 1ms`；重开立即成功（无 stale reader） |
| b10_blockedReadWakesOnClose | PASS | 阻塞 read 在 close 后 `woke after 1ms, result=-1` |

`READER_EXIT_AFTER_CLOSE = PASS`；`STALE_READER_AFTER_CLOSE = NO`。

## 14. Gate C evidence（concurrent multi-session）

全部在**同一 ONLINE connection** 上、三个 persistent Session 同时 ACTIVE：

| 用例 | 内容 | 结果 |
|---|---|---|
| c1_threeConcurrentSessionsActive | A/B/C 同时 ACTIVE + 并发执行 + 无串扰 | PASS |
| c2_cwdEnvIsolation | A(11,/data/local/tmp) B(22,/system) C(未继承) | PASS |
| c3_stdinIsolation | C `cat` 写 CATSTDIN_MARK；A/B 不可见且存活 | PASS |
| c4_ctrlCIsolation | C `sleep 30` + 0x03；C 恢复，A/B 存活 | PASS |
| c5_resizeIsolation | A 40x120 / B 24x80 各自 stty size 独立 | PASS |
| c6_closeIsolation | close B；A/C 存活，transport 仍 ONLINE | PASS |
| c7_reopenWithOtherStreamsActive | A 存活时 close B 后再开 D（3 会话并存） | PASS |
| c8_naturalExitIsolation | C `exit` → EXITED；A/B 不受影响 | PASS |

`MULTI_SESSION = PASS`；`CONCURRENT_ACTIVE_SESSIONS = 3`。

## 15. Transport multiplexing evidence

c1 logcat（一次运行内）：

```text
[C01] CONNECT_START 127.0.0.1:38793              ×1
[C06] TLS_START                                   ×1（DoHandshake/startTls 两条日志=同一握手）
[C13] ONLINE                                      ×1
[HUB01] RX_OWNER_START（post-ONLINE rx owner=1）   ×1
[HUB02] STREAM_OPEN_TX local=1/2/3                ×3
[HUB03] STREAM_ACTIVE local=1 remote=1600
        STREAM_ACTIVE local=2 remote=1601
        STREAM_ACTIVE local=3 remote=1602          ×3
[HUB04] STREAM_CLOSE_LOCAL local=1/2/3            ×3
```

整类回归（25 个用例同一进程）logcat 汇总：

```text
CONNECT_START = 1     TLS_START = 1     RX_OWNER_START = 1
STREAM_ACTIVE = 60    STREAM_OPEN_FAILED = 0    RX_OWNER_READ_FAIL = 0
```

```text
PHYSICAL_ADB_CONNECTION_COUNT = 1
TLS_HANDSHAKE_COUNT = 1
CONCURRENT_ADB_STREAM_COUNT = 3（≥3 required）
CONNECTION_MULTIPLEXING = VERIFIED
TRANSPORT_RX_OWNER_COUNT = 1
```

## 16. Stability results

| 项目 | 结果 |
|---|---|
| s1_sequentialLifecycle | PASS（10 次 open/exec/close；1 conn / 1 TLS / 10 streams / 0 open fail） |
| s2_concurrentLifecycle | PASS（3 轮 A/B/C 并发 + 不同 close 顺序 + 收尾 open；1 conn / 1 TLS / 10 streams / 0 open fail） |
| 整类运行（25 tests 同进程） | **OK (25 tests)**（AT.V3-2 时代「整类运行会崩溃」已证伪） |
| SIGABRT | NO |
| Deadlock | NO（close/read/写全流程 bounded） |
| Stream crosstalk | NO（c1–c8 隔离断言全过） |

## 17. AT / Contract impact

```text
AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_BACKEND_PUBLIC_CONTRACT_CHANGED = NO（Kotlin API 未改；:at-backend-adb 无代码改动）
ADB_ENGINE_MODIFIED = YES（本轮授权范围；见 §5 文件清单）
```

- `:at-backend-adb` 的 `AdbBackend.kt` **零修改**：JNI 函数签名/语义兼容，
  `AdbTerminalSession` 的单 reader push 模式在新语义下自然获得 bounded close/join。
- Kotlin 公共类型（`AdbEngine` / `AdbInteractiveStream` / `AdbShellResult`）未改。
- 回归全绿（§19）证明未影响 Local / AT / App / Dynamic。

## 18. Remaining ADB runtime gaps（真实状态，未完成项不冒充）

```text
PAIRING_PARITY    = OUT_OF_SCOPE（COMPILED；未真机）
DISCOVERY_PARITY  = OUT_OF_SCOPE（mDNS COMPILED；未真机）
RECOVERY_PARITY   = OUT_OF_SCOPE（COMPILED；未真机）
HUMAN_ADB_CLI     = OUT_OF_SCOPE
ADB_TERMINAL_UI_APK = 未做（headless-only；正式 AndroidTerminal default 仍 = Local）
```

本轮遗留（不阻塞 Gate C）：

1. ADB Engine `AdbConnection::state` 在 transport 失败后不自动转 Offline（JNI 状态投影
   仍可能报 Online 直到显式 disconnect）——本轮维持现状，未改状态机（超出授权范围）。
2. 失去连接不自动重连（recovery 属 OUT_OF_SCOPE）。
3. registry 中残留的 Closed slot 在远端不回应 CLSE 的极端情况下由下次 open/failAll 清理
   （量级极小，未做定时清扫）。
4. pairing/discovery/recovery 真机验证仍待后续任务。

## 19. Final Verdict

```text
AT.V3-2.1 FINAL VERDICT

STATUS = COMPLETE

PREVIOUS_BLOCKER = ADB_ENGINE_SINGLE_CONNECTION_READ_OWNERSHIP
ROOT_CAUSE_CONFIRMED = YES

TRANSPORT_RX_MODEL = SINGLE_CONNECTION_READER
POST_ONLINE_RX_OWNER_COUNT = 1

STREAM_DISPATCHER = PASS
STREAM_REGISTRY = PASS
PER_STREAM_INPUT_QUEUE = PASS
CLOSE_WAKES_READER = PASS（1ms）
CONNECTION_FAILURE_WAKES_STREAMS = PASS（failAll 路径实现；shutdown 实测唤醒）
TX_SERIALIZATION = FIXED（connection-wide tx_mutex_）

ONESHOT_REGRESSION = 5/5
CONNECTION_REUSE = PASS
ONESHOT_TLS_HANDSHAKES = 1（3 连发复用）

PERSISTENT_REGRESSION = 8/8
REMOTE_PTY = PASS（/dev/pts/*，resize 真实）
READER_EXIT_AFTER_CLOSE = PASS
STALE_READER_AFTER_CLOSE = NO

MULTI_SESSION = PASS
CONCURRENT_ACTIVE_SESSIONS = 3
CWD_ENV_ISOLATION = PASS
STDIN_ISOLATION = PASS
CTRL_C_ISOLATION = PASS
RESIZE_ISOLATION = PASS
CLOSE_ISOLATION = PASS
REOPEN_WITH_OTHER_STREAMS_ACTIVE = PASS
NATURAL_EXIT_ISOLATION = PASS

PHYSICAL_ADB_CONNECTION_COUNT = 1
TLS_HANDSHAKE_COUNT = 1
CONCURRENT_ADB_STREAM_COUNT = 3
CONNECTION_MULTIPLEXING = VERIFIED

SEQUENTIAL_LIFECYCLE = 10/10
CONCURRENT_LIFECYCLE = 3/3（整类 25 tests 同进程亦 PASS）
SIGABRT = NO
DEADLOCK = NO
STREAM_CROSSTALK = NO

LOCAL_REGRESSION = 11/11
AT_ENGINE_TESTS = 6/6
APP_DEBUG_REGRESSION = 7/7
HEADLESS_REGRESSION = 1/1
DYNAMIC_SESSION_REGRESSION = 9/9

AT_ENGINE_MODIFIED = NO
AT_CONTRACT_CHANGED = NO
ADB_BACKEND_PUBLIC_CONTRACT_CHANGED = NO
LOCAL_BACKEND_MODIFIED = NO
ADB_ENGINE_MODIFIED = YES

PAIRING_PARITY = OUT_OF_SCOPE
DISCOVERY_PARITY = OUT_OF_SCOPE
RECOVERY_PARITY = OUT_OF_SCOPE
HUMAN_ADB_CLI = OUT_OF_SCOPE

AT_V3_2_GATE_C = CLOSED
ADB_BACKEND_CURRENT_STATUS = VERIFIED / COMPLETE

REAL_DEVICE_VALIDATION = PASS
EVIDENCE = docs/evidence/at_v3_2_1/EVIDENCE.md

NEXT = AT BACKEND PARITY / CLOSURE AUDIT
```