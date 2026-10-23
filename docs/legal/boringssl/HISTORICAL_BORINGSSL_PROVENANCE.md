# Historical BoringSSL Provenance

**Document date:** 2026-10-23
**Scope:** Old BoringSSL (pre-upgrade), used in AT V4 freeze and AG consumer

---

## 1. Build Origin

| Field | Value |
|-------|-------|
| **Build date** | 2026-09-24 |
| **Build report** | `D:\codebank\AndCode\AndroidTerminal\docs\step2a2_wireless_client_report.md` |
| **Source** | BoringSSL from source (not from NDK sysroot) |
| **Build method** | NDK Clang cross-compile, arm64-v8a |
| **Compiler** | Clang 18.0.1 (NDK r27, Android 12027248, LLVM d8003a45) |
| **Initial build** | ssl/ only (38 files) â†?incomplete |
| **Rebuild** | ssl/ + crypto/ (283 files) + gen/crypto/err_data.cc |
| **Initial size** | 33.7 MB (documented) |
| **Final size** | 4,343,398 bytes (4.14 MB) |

## 2. Source Directory

| Field | Value |
|-------|-------|
| **Original path** | `<USER_HOME>\AppData\Local\Temp\opencode\boringssl-android15\` |
| **Evidence** | 255 source file paths extracted from DWARF debug info in `libboringssl.a` |
| **Status** | DELETED (directory no longer exists) |
| **`.git`** | UNKNOWN (cannot verify; directory removed) |
| **Upstream commit** | UNKNOWN |
| **Upstream date** | UNKNOWN |

The directory was removed during cleanup. The original `.git` directory, if it existed, was removed with it.

## 3. Binary Evidence

| Field | Value |
|-------|-------|
| **File** | `libboringssl.a` |
| **Location (V1)** | `AndroidTerminal/app/src/main/cpp/third_party/boringssl/lib/arm64-v8a/` |
| **Location (V2)** | `AndroidTerminal-V2/at-core/src/main/cpp/third_party/boringssl/lib/arm64-v8a/` |
| **Location (V4 freeze)** | `AndroidTerminal-V4` at tag `AT.V4-CONSUMER-FREEZE-V1` (commit `7216d0e`) |
| **SHA-256** | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` |
| **Size** | 4,343,398 bytes |
| **Archive members** | 322 object files |
| **Architecture** | arm64-v8a |
| **Debug info** | Present (DWARF source paths + compiler version) |

The binary is **identical** (same SHA-256) across V1, V2, and V4.

## 4. Migration Chain

```
V1 (AndroidTerminal)
  app/src/main/cpp/third_party/boringssl/
  libboringssl.a SHA-256: 602A6D6C...
  98 headers + 1 static lib

    â†? V2 migration (commit a96664e, AT.V2-4A)

V2 (AndroidTerminal-V2)
  at-core/src/main/cpp/third_party/boringssl/
  libboringssl.a SHA-256: 602A6D6C... (identical)
  98 headers + 1 static lib

    â†? V4 migration (commit a40387e, AT.V3-1)

V4 (AndroidTerminal-V4)
  adb-engine/src/main/cpp/third_party/boringssl/
  libboringssl.a SHA-256: 602A6D6C... (identical)
  98 headers + 1 static lib

    â†? V4 freeze (tag AT.V4-CONSUMER-FREEZE-V1, commit 7216d0e)

Frozen bundle
  dist/AT.V4-CONSUMER-FREEZE-V1/artifacts/adb-engine-debug.aar
  BoringSSL statically linked into libadbd_engine.so
```

## 5. Version Range

| Evidence | Constraint | Confidence |
|----------|-----------|------------|
| Has `bcm_public.h` (FIPS BCM) | < 2025-10-08 (commit `87dff58` removed it) | EXACT |
| Has `kyber.h`/`spx.h` (experimental PQ) | < removal date (unknown) | PARTIAL |
| BSD/ISC copyright in headers (no Apache) | < 2025-02-03 (commit `33d1049` switched to Apache-2.0) | EXACT |
| 255 source paths match BoringSSL tree structure | Consistent with some historical version | PARTIAL |
| 322 object files (ssl + crypto + err_data) | Consistent with manual build (283 files + err_data) | HIGH_CONFIDENCE |

**Conclusion:** The old BoringSSL is from **before 2025-02-03** (pre-Apache-2.0 switch).

**Exact upstream commit: UNKNOWN**

The exact commit cannot be recovered because:
1. The source directory (`boringssl-android15`) was deleted
2. No `.git` directory survived
3. No build log or script was preserved
4. No version identifier exists in the headers or binary

## 6. 33.7 MB â†?4.1 MB

| Item | Status |
|------|--------|
| 33.7 MB initial | DOCUMENTED (Step 2A.2 report) |
| 4.1 MB final | DOCUMENTED (V1/V2/V4) |
| Exact conversion | UNKNOWN |

The 4.1 MB binary **still contains** DWARF debug info (source paths, compiler version). The size reduction likely involved:
- Removing some object files (322 vs potentially more in the initial build)
- Partial removal of debug sections
- Or a different archive packaging

The exact `llvm-strip` or `llvm-ar` command used is **not documented**.

## 7. Cannot Recover

| Item | Reason |
|------|--------|
| Exact upstream commit | Source dir deleted, no .git |
| Exact build commands | Not documented |
| Whether CMake or manual compilation | Not documented (283 files + err_data suggests manual) |
| 33.7 MB â†?4.1 MB exact process | No build log |
| Whether `third_party/fiat` was compiled in | No fiat source paths in DWARF (but may have been stripped) |
