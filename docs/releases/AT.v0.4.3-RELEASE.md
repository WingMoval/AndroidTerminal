# AndroidTerminal v0.4.3 — Release Notes

**Date:** 2026-11-05
**Status:** RELEASED
**Type:** PATCH_RELEASE (routing fix)

## Version

```
versionName = 0.4.3
versionCode = 5
package     = com.androidterminal.app
```

## Base

```
BASE_RELEASE = v0.4.2
  TAG_OBJECT = fd8fb7c
  TAG_TARGET = 925e7ac
  PRODUCTION_RELEASE_COMMIT = 6ed902a
FIX_COMMIT   = 4ebabe5 (AT.LOCAL-TCP-ROUTING-FIX)
```

## Fixed

ADB one-shot execution now routes through AdbRuntime's canonical transport selector, ensuring Local TCP sessions use `executeShellLocalhost` while preserving the existing Wireless execution path.

**Root cause:** `AdbOneShotBackend.execute()` called `AdbEngine.executeShell()` directly (Wireless-only JNI primitive), bypassing the canonical `AdbRuntime.executeShell()` LOCAL/WIRELESS router. Local ADB one-shot commands were incorrectly routed through the Wireless path.

**Fix:** `AdbEngine.executeShell(...)` → `AdbRuntime.executeShell(...)` in `AdbBackend.kt` (1 file, 1 line + comments).

## Validation

Local startup, one-shot, command semantics, transport reuse, restart, live Local transport loss (`adb usb`), identity continuity and Local independence from Wireless Debugging verified through AndroidAgent consumer validation (AG.AT-V043-COMPAT-VALIDATION-0, head cd5d190).

Wireless execution primitive unchanged from v0.4.2. Historical v0.4.2 Wireless real-device validation applies. No new Wireless defect observed.

## Scope

- No new transport
- No new pairing semantics
- No new Local TCP semantics
- No new Wireless behavior
- No new CLI protocol
- No new PTY behavior
- No new recovery algorithm
- No native change
- No public API change
