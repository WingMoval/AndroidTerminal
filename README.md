# AndroidTerminal

**Android Execution Subsystem** — one-shot command execution and ADB
wireless debugging for Android devices, from an Android app.

## What It Does

- **One-shot shell execution** over ADB wireless debugging (TLS 1.3)
- **ADB identity management** (RSA-2048 keypair, auto-generated on first use)
- **Wireless Debugging pairing** (SPAKE2, AOSP-compatible)
- **Auto-WD permission bootstrap** (first-use manual pairing, then
  automatic `WRITE_SECURE_SETTINGS` self-grant for subsequent sessions)
- **Local backend** (on-device shell execution without ADB)

## Architecture

```text
Consumer / Product (Android App, CLI, AG)
        │
        ▼
AT Engine Public Boundary (:at-engine-api)
        │
        ▼
AT Engine (:at-engine)
   ├─ One-shot Execution
   └─ Persistent Terminal
        │
        ▼
Backend Adapter
   ├─ Local Backend (:at-backend-local)
   └─ ADB Backend (:at-backend-adb)
        │
        ▼
Execution Provider
   ├─ Local Process / PTY
   └─ ADB Engine (:adb-engine) → adbd (TLS 1.3, shell v2)
```

| Module | Role |
|--------|------|
| `:at-engine-api` | Public consumer contract (interfaces only) |
| `:at-engine` | AT Engine implementation (one-shot + persistent) |
| `:at-engine-spi` | Backend SPI (internal) |
| `:at-backend-local` | Local shell execution (no ADB) |
| `:at-backend-adb` | ADB backend adapter |
| `:adb-engine` | ADB engine (pairing, TLS, connection, shell v2) |
| `:at-composition-android` | Android composition root (assembly) |
| `:at-cli` | CLI frontend (optional) |
| `:app` | Android app (host) |
| `:test-host` | Test host (provisional) |

## Version Status

```text
Stable Consumer Baseline = AT.V4-CONSUMER-FREEZE-V1
Freeze Commit            = 7216d0e
Freeze Tag               = AT.V4-CONSUMER-FREEZE-V1
```

The `master` branch restores the V4 freeze BoringSSL dependency
(pre-2025-02, device-verified). A BoringSSL upgrade to `df82113`
(Apache-2.0) was attempted but reverted pending device verification.
See `docs/audit/AT.GITHUB-STABLE-BASELINE-1.md`.

## Build

Prerequisites:
- Android SDK (platform-34+)
- NDK (r26+)
- CMake 3.22.1
- JDK 21

```powershell
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

ABI: `arm64-v8a` (BoringSSL static library is arm64-only).

## License

- **AT original code:** Apache-2.0 (`LICENSE`)
- **BoringSSL (pre-2025-02):** OpenSSL BSD + SSLeay BSD + ISC + MIT
  (`adb-engine/src/main/cpp/third_party/boringssl/LICENSE`)
- **Notices:** `NOTICE`, `THIRD_PARTY_NOTICES.md`
- **BoringSSL compliance:** `docs/legal/boringssl/`

## Consumer Integration

AndroidAgent (AG) consumes the V4 freeze consumer boundary:
- `:at-engine-api` (REQUIRED)
- `:at-composition-android` (REQUIRED)
- `:at-cli` (OPTIONAL)

See `docs/AT_V4_CONSUMER_HANDOFF.md` for the integration guide.
