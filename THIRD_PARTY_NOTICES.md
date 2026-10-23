# Third-Party Notices

## BoringSSL (Current — master HEAD)

| Field | Value |
|-------|-------|
| **Component** | BoringSSL (vendored static library + headers) |
| **Location** | `adb-engine/src/main/cpp/third_party/boringssl/` |
| **Version** | commit `df821137838169360f82f94fd39cebe803c7c2d6` (2026-10-22, main HEAD) |
| **Source** | Google BoringSSL (https://github.com/google/boringssl). Built from source with NDK 26.1.10909125, Clang 17.0.2, CMake 3.22.1. |
| **License** | Apache License, Version 2.0 |
| **Binary** | `libcrypto.a` (30.5 MB) + `libssl.a` (11.7 MB) for arm64-v8a |
| **Compliance** | `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md` (see also below) |

## BoringSSL (Historical — V4 freeze, AG consumer)

| Field | Value |
|-------|-------|
| **Component** | BoringSSL (vendored static library + headers) |
| **Location** | Git history (commit `a40387e`), freeze tag `AT.V4-CONSUMER-FREEZE-V1` (commit `7216d0e`) |
| **Version** | UNKNOWN (pre-2025-02-03; exact commit unrecoverable) |
| **Source** | Google BoringSSL (https://github.com/google/boringssl). Built with NDK r27, Clang 18.0.1 on 2026-09-24. |
| **SHA-256** | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` (libboringssl.a, 4,343,398 bytes) |
| **License** | Pre-2025-02 BoringSSL: OpenSSL BSD + SSLeay BSD + ISC + MIT |
| **License text** | `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` (SHA-256: `E5DFA8A7A5F456425E9ADE48B72CF4C67410EE299F8EB10DEE28589DDD4A5A58`) |
| **Binary** | `libboringssl.a` (4.1 MB, arm64-v8a, 322 objects) statically linked into `adb-engine-debug.aar` |
| **Headers** | 98 files (44 Google ISC, 34 OpenSSL BSD, 13 other; 0 Apache) |
| **Compliance** | CLOSED_WITH_LIMITATIONS (see `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md`) |
| **Provenance** | `docs/legal/boringssl/HISTORICAL_BORINGSSL_PROVENANCE.md` |

### License scope note

The current BoringSSL (df82113) is licensed under Apache-2.0.
The historical BoringSSL (pre-2025-02) is licensed under the pre-switch
BoringSSL license (OpenSSL BSD + SSLeay BSD + ISC + MIT).

These licenses apply ONLY to the BoringSSL files. They do NOT re-license
any AT original code. AT original code is licensed under Apache-2.0 per
the root `LICENSE` file.

### Historical license closure

The historical BoringSSL license has been audited and documented:

- **License regime:** Pre-2025-02-03 (OpenSSL BSD + SSLeay BSD + ISC + MIT)
- **Evidence:** 98/98 headers have copyright notices (0 Apache, 50 BSD/ISC, 103 SSLeay refs)
- **Redistribution requirements:** All satisfied (copyright retained, notices in NOTICE file)
- **Limitation:** Exact upstream commit UNKNOWN
- **Verdict:** CLOSED_WITH_LIMITATIONS

See `docs/legal/boringssl/` for the full archive:
- `HISTORICAL_BORINGSSL_PROVENANCE.md` — build origin, migration chain, version range
- `HISTORICAL_BORINGSSL_LICENSE.md` — pre-2025-02 official license text
- `HISTORICAL_BORINGSSL_COMPLIANCE.md` — compliance matrix and redistribution obligations
