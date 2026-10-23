# Historical BoringSSL Binary Distribution Requirements

**Document date:** 2026-10-23
**Scope:** Distribution of `adb-engine-debug.aar` (contains BoringSSL statically linked)

---

## Status

```text
LICENSE_REQUIREMENTS_DOCUMENTED = YES
OLD_AAR_ALREADY_COMPLIANT = NO
OLD_AAR_LICENSE_ACCOMPANIMENT = NOT_VERIFIED
```

The frozen bundle `dist/AT.V4-CONSUMER-FREEZE-V1/` does NOT contain:
- LICENSE file
- NOTICE file
- THIRD_PARTY_NOTICES.md
- BoringSSL historical license text

This is a gap in the original freeze. The bundle contains only:
- `artifacts/` (8 AAR/JAR files)
- `docs/` (2 documentation files)
- `README.md`
- `SHA256SUMS.txt`

## Requirement

When distributing `adb-engine-debug.aar` (or any AAR containing the historical
BoringSSL static library), the following files MUST accompany the AAR:

| File | Purpose |
|------|---------|
| `LICENSE` | Apache-2.0 license for AT original code |
| `NOTICE` | Project identity + BoringSSL acknowledgment (current + historical) |
| `THIRD_PARTY_NOTICES.md` | Full third-party component documentation |
| `HISTORICAL_BORINGSSL_LICENSE.md` | Pre-2025-02 BoringSSL license text (OpenSSL BSD + SSLeay BSD + ISC + MIT) |

These files are located in the AT V4 repository at:
- `LICENSE` (root)
- `NOTICE` (root)
- `THIRD_PARTY_NOTICES.md` (root)
- `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md`

## Rationale

The historical BoringSSL (pre-2025-02) is statically linked into
`libadbd_engine.so` inside `adb-engine-debug.aar`. The pre-2025-02 license
requires:

1. **OpenSSL BSD:** Retain copyright notices + include license in binary distributions
2. **SSLeay BSD:** Retain copyright notices + Eric Young acknowledgment
3. **ISC:** Retain copyright and permission notice
4. **MIT (fiat):** Include copyright notice and permission notice

A consumer who downloads only the AAR without these license files would not
have access to the required license texts, creating a compliance gap.

## Action Required

Before any public distribution of the frozen AAR:
1. Include the 4 files listed above alongside the AAR
2. Update `SHA256SUMS.txt` to include hashes for the license files
3. Update `README.md` to reference the license files

This does NOT require rebuilding the AAR or modifying the frozen bundle.
The license files are added to the distribution package, not to the AAR itself.

## Note on Old AAR Compliance

The original frozen AAR was created before the BoringSSL license audit.
It was not created with the intent of standalone binary distribution
without license accompaniment. The AAR itself is unchanged and valid.
The compliance gap is in the **distribution packaging**, not in the AAR.
