# AT.V3-2 — ADB Backend Vertical Slice Evidence

## 真机验证（设备 APLMUT4312005935 / ALI-AN00 / Android 15）

### Gate A — One-shot（PASS）

| Test | 判定 | 关键证据 |
|---|---|---|
| a1_echo | PASS | `echo hello` → stdout contains hello, exit 0 |
| a2_principal | PASS | `id` → `uid=2000(shell)`（ADB shell principal） |
| a3_pwd | PASS | remote shell pwd 非空 |
| a4_stderrNonZero | PASS | 不存在命令 → exit 127, stderr 有 "not found", COMPLETED |
| a5_connectionReuse | PASS | 3 次 one-shot 复用同一连接 |

### Gate A transport evidence（a5 logcat）

```
AndroidTerminalADB: state=Online tls=1 OPEN local_id=1 → OKAY → WRTE → CLSE（echo REUSE_0）
AndroidTerminalADB: state=Online tls=1 OPEN local_id=2 → OKAY → WRTE → CLSE（echo REUSE_1）
AndroidTerminalADB: state=Online tls=1 OPEN local_id=3 → OKAY → WRTE → CLSE（echo REUSE_2）
→ TLS handshake count = 1（无重连/重 CNXN）；3 条串行 stream
```

### Gate B — Persistent Remote PTY（PASS）

| Test | 判定 | 关键证据 |
|---|---|---|
| b1_openActive | PASS | openSession → ACTIVE |
| b2_writeOutput | PASS | `echo ADB_PERSIST_OK` → 输出 |
| b3_cwdContinuity | PASS | cd /data/local/tmp → pwd 保持 |
| b4_envContinuity | PASS | export AT_ADB_TEST=123 → echo 123 |
| b5_stdin | PASS | cat + ADB_STDIN_TEST → 回显 |
| b6_ctrlC | PASS | sleep 30 + 0x03 → shell ACTIVE + AFTER_CTRL_C |
| b7_ttyAndResize | PASS | `tty` → `/dev/pts/*`；resize(40,120) → `stty size` = 40 120 |
| b8_exitCloseReopen | PASS | exit / close / reopen 新 session |

### Gate C — Multi-session（BLOCKED）

- 第二个并发 interactive open → `Failure(reason=ADB interactive open failed)`
- 随后进程崩溃（BoringSSL SIGABRT in `SSL_read`）
- 根因：`AdbInteractiveStream::read` 阻塞无超时 + 单 AdbConnection 单 reader 所有权
- 记录为 ADB Engine 传输限制，非 AT Contract 问题

## 说明

- 逐用例运行（非整类），避免共享连接 reader 残留。
- 详细报告见 `docs/implementation/AT.V3-2_ADB_Backend_Vertical_Slice.md`。