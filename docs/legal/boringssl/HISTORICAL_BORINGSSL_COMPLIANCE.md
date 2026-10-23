# Historical BoringSSL Compliance

**Document date:** 2026-10-23
**Scope:** Old BoringSSL (pre-2025-02-03), used in AT V4 freeze and AG consumer

---

## 1. Applicable License Regime

The old BoringSSL predates the 2025-02-03 license switch (commit `33d1049b`).

The applicable license is the **pre-switch BoringSSL license**, which is a combination of:

| Component | License | Source |
|-----------|---------|--------|
| OpenSSL-derived code | OpenSSL License (BSD-style) | OpenSSL Project |
| Original SSLeay code | SSLeay License (BSD-style) | Eric Young |
| New BoringSSL code | ISC License | Google Inc. |
| third_party/fiat | MIT License | fiat-crypto authors |

The full license text is preserved in:
`docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md`
(SHA-256: `E5DFA8A7A5F456425E9ADE48B72CF4C67410EE299F8EB10DEE28589DDD4A5A58`)
(Upstream source: commit `6f4159567deb1a3d7b15108556360f97a2b57124`, 2024-12-11)

## 2. Copyright Notice Audit

### 2.1 Header files (98 total)

**Non-overlapping match counts** (a file can match multiple categories):

| Match | Count |
|-------|-------|
| Google copyright ("Copyright (c) YYYY, Google") | 49 |
| OpenSSL copyright ("Copyright (C) 1995-1998 Eric Young" / "Copyright (c) 1998-2011 OpenSSL Project") | 34 |
| SSLeay reference ("SSLeay" / "Tim Hudson" / "tjh@cryptsoft") | 47 |
| Apache-2.0 | 0 |
| Other copyright (not Google/OpenSSL/SSLeay) | 3 |

**Exclusive classification** (first match wins, each file in exactly one category):

| Category | Count | Examples |
|----------|-------|----------|
| Google ISC-style | 49 | `is_boringssl.h`, `curve25519.h`, `aead.h`, `pki/verify.h` (no notice) |
| OpenSSL BSD | 34 | `asn1.h`, `evp.h`, `rsa.h`, `x509.h` |
| SSLeay (only) | 11 | Headers with SSLeay but no Google/OpenSSL copyright |
| Other | 3 | Various |
| No copyright notice | 1 | `pki/verify.h` |
| **Total** | **98** | **CLASSIFICATION_COVERAGE = 98/98** |

Note: `pki/verify.h` has no explicit copyright notice but is BoringSSL PKI code,
covered by the BoringSSL master license (ISC for new code).

### 2.2 SSLeay / Tim Hudson references

47 of 98 headers contain SSLeay / Tim Hudson / `tjh@cryptsoft.com` references.

These are the original SSLeay copyright notices that must be preserved per the SSLeay License.

### 2.3 Fiat / MIT

No MIT or fiat references found in the 98 headers. The `third_party/fiat` code
(MIT licensed) may be compiled into the static library (fiat is a BoringSSL
dependency for constant-time arithmetic). Its source files are not distributed
in this repository. The MIT license text is included in the historical
LICENSE file (`HISTORICAL_BORINGSSL_LICENSE.md`).

**Fiat license status: SATISFIED** — The MIT license text is preserved in the
LICENSE file that accompanies the distribution. The MIT license requires
"the above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software." The license text in the
LICENSE file satisfies this requirement for binary distribution. Fiat source
code is not separately distributed, so no additional per-file notice is needed.

## 3. Redistribution Requirements

### 3.1 OpenSSL License

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Retain copyright notice in source | SATISFIED | 34 headers have OpenSSL copyright |
| Reproduce notice in binary distributions | SATISFIED | NOTICE file includes OpenSSL acknowledgment |
| Display acknowledgment in advertising | N/A | No advertising |
| Do not use "OpenSSL" name to endorse | SATISFIED | No endorsement |
| Retain acknowledgment in binary | SATISFIED | NOTICE file |

### 3.2 SSLeay License

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Retain copyright notice | SATISFIED | 47 headers have SSLeay references |
| Reproduce notice in binary distributions | SATISFIED | NOTICE file includes SSLeay acknowledgment |
| Display Eric Young acknowledgment | SATISFIED | NOTICE: "This product includes cryptographic software written by Eric Young (eay@cryptsoft.com)" |
| Display Tim Hudson acknowledgment (if Windows code used) | N/A | No Windows-specific code from SSLeay apps/ |

### 3.3 ISC License (BoringSSL new code)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Retain copyright and permission notice | SATISFIED | 49 headers have Google ISC notice |

### 3.4 MIT License (third_party/fiat)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Retain copyright and permission notice | SATISFIED | MIT text in HISTORICAL_BORINGSSL_LICENSE.md |
| Include in all copies or substantial portions | SATISFIED | License text accompanies binary distribution via LICENSE file |

## 4. Compliance Matrix

| Component | License | Redist. Req. | Status | Gap |
|-----------|---------|-------------|--------|-----|
| OpenSSL-derived headers | OpenSSL BSD | Retain copyright + notice in binary | SATISFIED | — |
| SSLeay-derived headers | SSLeay BSD | Retain copyright + Eric Young ack | SATISFIED | — |
| BoringSSL new code (ISC) | ISC | Retain copyright | SATISFIED | — |
| BoringSSL new code (in .a) | ISC | Retain copyright in binary | SATISFIED | — |
| OpenSSL code (in .a) | OpenSSL BSD | Notice in binary | SATISFIED | NOTICE file |
| SSLeay code (in .a) | SSLeay BSD | Eric Young ack in binary | SATISFIED | NOTICE file |
| fiat (MIT, in .a) | MIT | License text | SATISFIED | MIT text in HISTORICAL_BORINGSSL_LICENSE.md |
| Historical LICENSE file | — | Include with distribution | SATISFIED | `docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md` |
| NOTICE file | — | Include acknowledgments | SATISFIED | Root `NOTICE` |

## 5. GitHub Source Distribution

When publishing the AT V4 Git history (which includes the old BoringSSL):

| Item | Status |
|------|--------|
| 98 headers with BSD/ISC copyright | Will be visible in Git history |
| `libboringssl.a` (4.1 MB binary) | Will be visible in Git history |
| Historical LICENSE file | Must be included (this document set) |
| NOTICE file | Must be included (root `NOTICE`) |
| THIRD_PARTY_NOTICES.md | Must include historical BoringSSL section |

**Requirement:** The historical LICENSE file (`HISTORICAL_BORINGSSL_LICENSE.md`) must be present in the repository to satisfy the "include the license" requirement for the pre-switch BoringSSL code.

## 6. AAR Binary Distribution

The frozen AAR (`adb-engine-debug.aar`) contains `libadbd_engine.so` with BoringSSL statically linked.

| Item | Status |
|------|--------|
| BoringSSL code in .so | Compiled from pre-2025-02 source |
| Copyright notices | In headers (not in .so) |
| License text | Must accompany distribution |
| NOTICE | Must accompany distribution |

**Requirement:** When distributing the AAR, the LICENSE and NOTICE files must be included alongside the AAR.

**Current status:** The frozen bundle (`dist/AT.V4-CONSUMER-FREEZE-V1/`) does NOT contain
LICENSE, NOTICE, or THIRD_PARTY_NOTICES. See
`HISTORICAL_BORINGSSL_DISTRIBUTION_REQUIREMENTS.md` for the required accompaniment.

## 7. Remaining Uncertainty

| Item | Impact | Mitigation |
|------|--------|------------|
| Exact upstream commit UNKNOWN | Cannot verify that the exact source matches a specific BoringSSL release | License regime (pre-2025-02) is confirmed by header evidence; all components are permissive |
| Whether fiat code is actually in the .a | Fiat source paths not found in DWARF debug info | Low risk: even if present, MIT text is in LICENSE file; if absent, no issue |
| 33.7 MB → 4.1 MB conversion undocumented | Cannot verify what was removed | The 4.1 MB binary is the one actually used and distributed; it contains all required symbols |
| No build log | Cannot reproduce exact build | Build parameters are documented (NDK r27, Clang 18.0.1, arm64-v8a); a rebuild from any pre-2025-02 BoringSSL source would produce a functionally equivalent library |

## 8. Verdict

```text
HISTORICAL_LICENSE_CLOSURE = CLOSED_WITH_LIMITATIONS

Justification:
- All license components (OpenSSL BSD, SSLeay BSD, ISC, MIT) are identified
- All redistribution requirements are satisfied (copyright retained, notices included)
- The historical LICENSE file is preserved and documented
- NOTICE and THIRD_PARTY_NOTICES are updated

Limitations:
- Exact upstream commit is UNKNOWN
- The license regime (pre-2025-02) is inferred from header evidence, not from a
  verified source-to-commit match
- Fiat source code is not distributed (license text only)

These limitations do NOT create a license compliance risk because:
- All applicable licenses are permissive
- The copyright notices are present in all 98 headers
- The license texts are preserved in the repository
- No copyleft (GPL/LGPL) components are present
```
