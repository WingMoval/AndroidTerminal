# Third-Party Notices

## BoringSSL

| Field | Value |
|-------|-------|
| **Component** | BoringSSL (vendored static library + headers) |
| **Location** | `adb-engine/src/main/cpp/third_party/boringssl/` |
| **Version** | UNKNOWN (no version identifier in headers or binary; migrated from V1 → V2 → V4 without version tracking) |
| **Source** | Google BoringSSL (https://github.com/google/boringssl). The `libboringssl.a` binary is identical (SHA-256: `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2`) across V1, V2, and V4. The exact upstream commit or release tag could not be determined from available evidence. |
| **License** | Apache License 2.0 (per BoringSSL official LICENSE). Original OpenSSL copyright notices (Eric Young, Tim Hudson) are preserved in individual header files under a BSD-style notice. |
| **Binary distribution** | `libboringssl.a` (arm64-v8a, 4.14 MB) is statically linked into the `adb-engine` AAR. |
| **Required notices** | Retain original copyright notices in headers (satisfied: 97/98 headers have copyright). Include BoringSSL LICENSE file (satisfied: `adb-engine/src/main/cpp/third_party/boringssl/LICENSE`). |
| **Evidence** | Header copyright notices (97 files with `Copyright (c) 2014/2017, Google Inc.` or `Copyright (C) 1995-1998 Eric Young`). CMakeLists.txt comment: "BoringSSL vendored static library + headers (migrated from V2)". V2 commit `a96664e` (AT.V2-4A): "extract AT Core from AT.apk". V4 commit `a40387e` (AT.V3-1): "extract and validate independent ADB engine". |
| **Files** | 99 total (97 headers + 1 static lib + 1 LICENSE added this round) |

### License scope note

The BoringSSL LICENSE file in this directory is the official BoringSSL
license (Apache-2.0 with original OpenSSL BSD-style copyright preserved
in headers). This license applies ONLY to the BoringSSL files under
`adb-engine/src/main/cpp/third_party/boringssl/`. It does NOT re-license
any AT original code.

AT original code is licensed under Apache-2.0 per the root `LICENSE` file.
