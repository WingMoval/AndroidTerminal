AT.V4-5 — CLI Conformance (Gate H)
Date: 2026-10-22

## Gate H: CLI shell-visible adb
```
h_cliShellVisibleAdb = PASS
Path:
  1. Consumer opens a Local persistent session (localEngine, /system/bin/sh)
  2. Injects the shell-visible `adb` function via :at-cli LocalCliBootstrap
     (writeScript + sourceLine) — reusing the canonical CLI embedding path
  3. Runs `adb shell id` in the Local session
  4. Output contains uid=2000(shell)
CLI_CONFORMANCE = PASS
```

## Constraints honored
Consumer does NOT:
- import :adb-engine
- construct AdbRuntime
- construct backends
- reimplement the CLI bridge protocol

It reuses the canonical CLI embedding path:
- `AdbCliBridgeServer(filesDir, execution)` — same-UID UDS, native atcli_env
- `LocalCliBootstrap(CLI_MAIN_CLASS)` — shell function bootstrap
- `ATCliExecution` (host SPI) — routes CLI into the composition adbEngine

## CLI_CONFORMANCE = PASS
