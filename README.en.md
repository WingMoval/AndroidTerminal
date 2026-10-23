# AndroidTerminal

[简体中文](README.md) | [English](README.en.md)

**AndroidTerminal** is an Android terminal application that also includes a reusable Android execution engine (AT Engine). It provides local shell execution and remote command execution over ADB wireless debugging (TLS 1.3).

## Download & Install

| User Type | Entry Point | Status |
|-----------|-------------|--------|
| General Users | APK | Coming soon |
| Developers | [SDK v0.4.0](https://github.com/WingMoval/AndroidTerminal/releases/tag/v0.4.0) | Released |

## Quick Start

1. Install the AndroidTerminal app
2. Enable **Wireless Debugging** on your device (Developer Options → Wireless Debugging)
3. Open AndroidTerminal, tap **Pair**, and enter the pairing code shown on your device
4. After successful pairing, the app automatically establishes an ADB connection
5. Type and execute commands in the terminal

> **First use requires manual pairing.** After pairing, the app self-grants `WRITE_SECURE_SETTINGS` for automatic Wireless Debugging (Auto-WD) in subsequent sessions — no re-pairing needed.

## Core Features

| Feature | Description | Status |
|---------|-------------|--------|
| Local Shell | On-device shell execution (no ADB) | Verified |
| Wireless ADB | ADB wireless debugging (TLS 1.3) | Verified |
| SPAKE2 Pairing | AOSP-compatible wireless debugging pairing | Verified |
| mDNS Discovery | NsdManager-based ADB service discovery | Verified |
| Persistent Session | Interactive terminal (input/output/resize/Ctrl-C) | Verified |
| Auto-WD | Automatic Wireless Debugging after first pairing | Verified |
| Connection Recovery | Bounded auto-reconnect (no replay) | Verified |
| Identity Management | RSA-2048 keypair, auto-generated on first use | Verified |

## Architecture

```text
Consumer / Product (Android App, CLI, AG)
        │
        ▼  AT Engine Public Boundary (:at-engine-api)
        ▼
        AT Engine (:at-engine)
   ├─ One-shot Execution
   └─ Persistent Terminal
        │
        ▼  Backend Adapter
   ├─ Local Backend (:at-backend-local)
   └─ ADB Backend (:at-backend-adb)
        │
        ▼  Execution Provider
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
| `:app` | Android app (product host) |

## Developer Integration

### Source Project

```kotlin
implementation(project(":at-engine-api"))
implementation(project(":at-composition-android"))
// Optional:
implementation(project(":at-cli"))
```

### Prebuilt AAR/JAR

Add the AAR/JAR files from the Release to your project's `libs/` directory:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

Internal modules (`at-engine`, `at-engine-spi`, `at-backend-local`, `at-backend-adb`, `adb-engine`) are transitive dependencies of `at-composition-android`. Do NOT depend on them directly (CONSUMER_BOUNDARY_VIOLATION).

Full integration guide: [Consumer Handoff](docs/handoff/AT_V4_CONSUMER_HANDOFF.md)

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

ABI: `arm64-v8a` (BoringSSL static library is arm64-only)

## Known Limitations

- arm64-v8a only (no x86, no armeabi-v7a)
- Single-device connection (no multi-device)
- Blocking I/O on ADB thread (not on main thread in production)
- First use requires manual pairing (not zero-touch)

## License

| Component | License |
|-----------|---------|
| AT original code | Apache-2.0 ([`LICENSE`](LICENSE)) |
| BoringSSL (pre-2025-02) | OpenSSL BSD + SSLeay BSD + ISC + MIT ([Historical License](docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md)) |

- Project notices: [`NOTICE`](NOTICE)
- Third-party notices: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- BoringSSL compliance: [`docs/legal/boringssl/`](docs/legal/boringssl/)

## Version

```text
Current public release: v0.4.0
Stable consumer baseline: AT.V4-CONSUMER-FREEZE-V1 (7216d0e)
```
