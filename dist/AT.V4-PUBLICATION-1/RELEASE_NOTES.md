# AndroidTerminal v0.4.0

**Release Tag:** `v0.4.0`  
**Release Type:** Stable  
**Freeze Identity:** `AT.V4-CONSUMER-FREEZE-V1`  
**Date:** 2026-10-23

---

## What This Is

This is the **first public release** of AndroidTerminal — an Android
execution subsystem providing one-shot shell command execution and ADB
wireless debugging from an Android app.

The SDK / Consumer artifacts are taken from the **immutable V4 consumer
freeze bundle** (`dist/AT.V4-CONSUMER-FREEZE-V1/`). They are NOT rebuilt
from the current master HEAD.

```text
SDK / Consumer artifacts source:
  7216d0e8131e92e8914ce7c8443e1668b1d53aca

Android APK source:
  0332674dd6d751620b191097b070f10561259417

The current master branch is the active V4 development mainline
and may be ahead of both release asset source commits.
```

---

## Downloads

### End Users

Download and install the APK directly on your Android device:

| File | Description |
|------|-------------|
| `AndroidTerminal-v0.4.0-arm64-v8a.apk` | Android app (arm64-v8a) |

### Developers

| File | Description |
|------|-------------|
| `AndroidTerminal-v0.4.0-Consumer.zip` | Prebuilt AAR/JAR SDK + integration guide |

---

## Android App

| Item | Value |
|------|-------|
| APK Source Commit | `0332674dd6d751620b191097b070f10561259417` |
| APK SHA-256 | `3388F93B8181E7EDCA0D462B424B027EA136482468F6228F215B06BFE8DB60ED` |
| Signing Certificate SHA-256 | `9c252f31d92375110f4dede218414b112e9f69937115a06444d5693b86551a93` |
| versionName | 0.4.0 |
| versionCode | 2 |
| ABI | arm64-v8a |

---

## Developer SDK

| Item | Value |
|------|-------|
| SDK / Consumer Source Commit | `7216d0e8131e92e8914ce7c8443e1668b1d53aca` |
| Freeze Identity | `AT.V4-CONSUMER-FREEZE-V1` |

### Consumer Artifacts

| File | Type | Role |
|------|------|------|
| `at-engine-api.jar` | JAR | **REQUIRED** — public consumer contract (interfaces) |
| `at-composition-android-debug.aar` | AAR | **REQUIRED** — Android assembly root |
| `at-cli-debug.aar` | AAR | OPTIONAL — CLI frontend |
| `at-engine-debug.aar` | AAR | internal (DefaultATEngine) |
| `at-engine-spi.jar` | JAR | internal (backend SPI) |
| `at-backend-local-debug.aar` | AAR | internal (Local backend) |
| `at-backend-adb-debug.aar` | AAR | internal (ADB backend) |
| `adb-engine-debug.aar` | AAR | internal (ADB lifecycle, TLS, pairing) |

### Source Project Integration

If you have the AT source project:

```kotlin
implementation(project(":at-engine-api"))
implementation(project(":at-composition-android"))
// Optional:
implementation(project(":at-cli"))
```

### Prebuilt AAR/JAR Integration

For external consumers without AT source modules, add the AAR/JAR files
to your project's `libs/` directory:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

The internal modules (`at-engine`, `at-engine-spi`, `at-backend-local`,
`at-backend-adb`, `adb-engine`) are transitive dependencies of
`at-composition-android`. Do NOT depend on them directly
(CONSUMER_BOUNDARY_VIOLATION).

See `docs/CONSUMER_HANDOFF.md` for the full integration guide.

---

## Validated Capabilities

The following were **implemented and device-verified** on the V4 freeze:

| Capability | Status |
|------------|--------|
| ADB wireless debugging (TLS 1.3) | DEVICE VERIFIED |
| One-shot shell execution | DEVICE VERIFIED |
| Persistent shell execution | DEVICE VERIFIED |
| ADB identity management (RSA-2048) | DEVICE VERIFIED |
| Wireless Debugging pairing (SPAKE2) | DEVICE VERIFIED |
| mDNS service discovery (NsdManager) | DEVICE VERIFIED |
| First-use manual pairing | DEVICE VERIFIED |
| WRITE_SECURE_SETTINGS self-grant bootstrap | DEVICE VERIFIED |
| Post-bootstrap Auto-WD | DEVICE VERIFIED |
| Connection recovery (bounded, no replay) | DEVICE VERIFIED |
| Local backend (on-device shell) | DEVICE VERIFIED |

---

## First-Use Requirements

1. Device must be running Android with Wireless Debugging available
2. First connection: manually enable Wireless Debugging on the device,
   then pair using the pairing code displayed on the device
3. After first pairing, the app self-grants `WRITE_SECURE_SETTINGS`
   for automatic Wireless Debugging in subsequent sessions
4. RSA-2048 identity is auto-generated on first use (stored in app data)
5. mDNS discovery finds the device automatically after WD is enabled

This is NOT a zero-touch first-use experience. The initial pairing
requires user interaction with the device.

---

## Supported Platform

| Item | Value |
|------|-------|
| Android minSdk | 26 (Android 8.0) |
| Android targetSdk | 34 (Android 14) |
| ABI | arm64-v8a only |
| Build NDK | r27 (Clang 18.0.1) |

Note: The NDK version is a build toolchain requirement, not a runtime
requirement for the consumer device.

---

## Known Limitations

- arm64-v8a only (no x86, no armeabi-v7a)
- Single-device connection (no multi-device)
- Blocking I/O on ADB thread (not on main thread in production)

---

## Licensing

| Component | License |
|-----------|---------|
| AT original code | Apache-2.0 (`licenses/LICENSE`) |
| BoringSSL (pre-2025-02) | OpenSSL BSD + SSLeay BSD + ISC + MIT (`licenses/HISTORICAL_BORINGSSL_LICENSE.md`) |

Full third-party documentation: `licenses/THIRD_PARTY_NOTICES.md`

---

## SHA-256 Verification

```powershell
# Verify all files
Get-Content SHA256SUMS.txt | ForEach-Object {
    $parts = $_ -split '\s+'
    $expected = $parts[0]
    $file = $parts[1]
    if (Test-Path $file) {
        $actual = (Get-FileHash $file -Algorithm SHA256).Hash
        if ($actual -ne $expected) { Write-Host "FAIL: $file" -ForegroundColor Red }
    } else {
        Write-Host "MISSING: $file" -ForegroundColor Yellow
    }
}
Write-Host "Verification complete"
```

---

## Source Identity

```text
SDK / Consumer artifacts source:
  7216d0e8131e92e8914ce7c8443e1668b1d53aca
  (AT.V4-CONSUMER-FREEZE-V1)

Android APK source:
  0332674dd6d751620b191097b070f10561259417

Both belong to AndroidTerminal v0.4.0 Release,
but originate from different commits.
```

---

## This Package

This publication package is a **license-supplemented wrapper** around the
original immutable freeze bundle. The AAR/JAR artifacts are byte-identical
to the original freeze. License files are added to satisfy redistribution
requirements for the BoringSSL static library.

- Original freeze bundle: `dist/AT.V4-CONSUMER-FREEZE-V1/` (immutable)
- This package: `dist/AT.V4-PUBLICATION-1/` (distribution wrapper)
