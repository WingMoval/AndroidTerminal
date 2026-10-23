# Third-Party Notices

## BoringSSL

| Field | Value |
|-------|-------|
| **Component** | BoringSSL (vendored static library + headers) |
| **Location** | `adb-engine/src/main/cpp/third_party/boringssl/` |
| **Version** | UNKNOWN (pre-2025-02-03; exact upstream commit unrecoverable) |
| **Source** | Google BoringSSL (https://github.com/google/boringssl). Built with NDK r27, Clang 18.0.1 on 2026-09-24. |
| **SHA-256** | `602A6D6C6DA1C5017A6C19C7EEE3F40C5653D200E2DA0B22B6238DA214F865D2` (libboringssl.a, 4,343,398 bytes) |
| **License** | Pre-2025-02 BoringSSL: OpenSSL BSD + SSLeay BSD + ISC + MIT |
| **License text** | `adb-engine/src/main/cpp/third_party/boringssl/LICENSE` |
| **Binary** | `libboringssl.a` (4.1 MB, arm64-v8a, 322 objects) statically linked into `adb-engine-debug.aar` |
| **Headers** | 98 files (49 Google ISC, 34 OpenSSL BSD, 11 SSLeay, 3 other, 1 no notice; 0 Apache) |
| **Compliance** | CLOSED_WITH_LIMITATIONS (see `docs/legal/boringssl/HISTORICAL_BORINGSSL_COMPLIANCE.md`) |
| **Provenance** | `docs/legal/boringssl/HISTORICAL_BORINGSSL_PROVENANCE.md` |

### License scope note

The BoringSSL files are licensed under the pre-2025-02 BoringSSL license
(OpenSSL BSD + SSLeay BSD + ISC + MIT). This license applies ONLY to the
BoringSSL files under `adb-engine/src/main/cpp/third_party/boringssl/`.
It does NOT re-license any AT original code. AT original code is licensed
under Apache-2.0 per the root `LICENSE` file.

### License closure

- **License regime:** Pre-2025-02-03 (OpenSSL BSD + SSLeay BSD + ISC + MIT)
- **Evidence:** 97/98 headers have copyright notices (0 Apache, 49 Google ISC, 34 OpenSSL BSD, 47 SSLeay refs)
- **Redistribution requirements:** All satisfied (copyright retained, notices in NOTICE file)
- **Limitation:** Exact upstream commit UNKNOWN
- **Verdict:** CLOSED_WITH_LIMITATIONS

See `docs/legal/boringssl/` for the full archive:
- `HISTORICAL_BORINGSSL_PROVENANCE.md` — build origin, migration chain, version range
- `HISTORICAL_BORINGSSL_LICENSE.md` — pre-2025-02 official license text (reference copy)
- `HISTORICAL_BORINGSSL_COMPLIANCE.md` — compliance matrix and redistribution obligations
- `HISTORICAL_BORINGSSL_DISTRIBUTION_REQUIREMENTS.md` — AAR distribution license requirements

### Historical upgrade note

A BoringSSL upgrade to commit `df82113` (Apache-2.0, 2026-10-22) was
attempted in commit `d1e5883` (AT.GITHUB-BORINGSSL-UPDATE-0). The upgrade
passed build and non-device tests but did NOT complete device verification.
It was reverted in this round (AT.GITHUB-STABLE-BASELINE-1) to maintain the
proven V4 baseline. The upgrade evidence is preserved in:
- `docs/audit/AT.GITHUB-BORINGSSL-UPDATE-0.md`
- `docs/audit/AT.GITHUB-CRYPTO-BACKEND-1.md`
