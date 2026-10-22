# Third-Party Notices

## BoringSSL

| Field | Value |
|-------|-------|
| **Component** | BoringSSL (vendored static library + headers) |
| **Location** | `adb-engine/src/main/cpp/third_party/boringssl/` |
| **Version** | commit `df821137838169360f82f94fd39cebe803c7c2d6` (2026-10-22, main HEAD) |
| **Source** | Google BoringSSL (https://github.com/google/boringssl). Built from source with NDK 26.1.10909125, Clang 17.0.2, CMake 3.22.1. See `UPSTREAM_VERSION.md` for full build parameters and library hashes. |
| **License** | Apache License, Version 2.0 (official BoringSSL LICENSE from upstream commit). Original OpenSSL copyright notices (Eric Young, Tim Hudson) are preserved in individual header file comments. |
| **Binary distribution** | `libcrypto.a` (30.5 MB) + `libssl.a` (11.7 MB) for arm64-v8a, statically linked into the `adb-engine` AAR. |
| **Required notices** | Retain original copyright notices in headers (satisfied). Include BoringSSL LICENSE file (satisfied: `adb-engine/src/main/cpp/third_party/boringssl/LICENSE`). |
| **Evidence** | `UPSTREAM_VERSION.md` with commit SHA, date, build toolchain, and library SHA-256 hashes. CMakeLists.txt references upstream commit. |
| **Files** | 110 total (106 headers + 2 static libs + LICENSE + UPSTREAM_VERSION.md) |

### License scope note

The BoringSSL LICENSE file in this directory is the official Apache-2.0
license from the upstream BoringSSL repository. This license applies ONLY
to the BoringSSL files under `adb-engine/src/main/cpp/third_party/boringssl/`.
It does NOT re-license any AT original code.

AT original code is licensed under Apache-2.0 per the root `LICENSE` file.

### Historical note

Prior to this update, the vendored BoringSSL was an unversioned binary
migrated from V1 → V2 → V4 without provenance tracking. The old binary
(SHA-256: `602A6D6C...`) still exists in Git history (commit `a40387e`)
and in the frozen bundle `dist/AT.V4-CONSUMER-FREEZE-V1/`. The license
for that historical binary remains UNKNOWN (pre- or post-February 2025
BoringSSL license). This update does NOT retroactively close the license
for historical artifacts.
