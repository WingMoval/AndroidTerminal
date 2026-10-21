# AT.V4-4.2B.1: UTF-8 Semantic Restoration

> **Date**: 2026-10-21
> **Type**: TEST-ONLY CORRECTION / SEMANTIC TEST RESTORATION
> **Verdict**: PASS

---

## 1. Baseline

- START_HEAD: `b6950af` (AT.V4-4.2B)
- WORKTREE: CLEAN
- V3_MODE: READ_ONLY
- Device: HONOR ALI-AN00 `APLMUT4312005935` (Android 15 / SDK 35)

## 2. Why B.1 Was Necessary

AT.V4-4.2B fixed the B3 build blocker by replacing CJK test data with ASCII.
This removed the compile error but also removed the multibyte UTF-8 validation
semantic that G7 exists to prove.

ASCII = single-byte encoding. It cannot prove:
- Multibyte UTF-8 byte preservation
- Byte-level split inside a multibyte code point
- AT Engine byte-stream transparency for partial UTF-8 sequences

## 3. Why ASCII Was Insufficient

`"PING ASCII_TEST\n"` is all single-byte. The test passed, but it proved
nothing about multibyte UTF-8 handling. The original G7 semantic requires:

1. Full multibyte UTF-8 roundtrip: `PING 中文测试` → byte-exact `PONG 中文测试`
2. Byte-level split INSIDE a multibyte code point (not at character boundary)

## 4. Historical G7 Semantic

V3 verified AT.TERM-0 (`73065c4`): G7 = UTF-8 byte preservation.
- Input: `PING 中文测试` + split UTF-8 → byte-exact `PONG`
- 15/15 PASS on real device

## 5. Unicode Escape Strategy

Kotlin 1.9.24 compiler on this system fails to parse CJK string literals
directly. Solution: use ASCII-safe Unicode escapes in source:

```kotlin
"\u4E2D"  // = 中 at runtime (UTF-8: E4 B8 AD)
"\u6587"  // = 文 at runtime (UTF-8: E6 96 87)
"\u6D4B"  // = 测 at runtime (UTF-8: E6 B5 8B)
"\u8BD5"  // = 试 at runtime (UTF-8: E8 AF 95)
```

Source remains 100% ASCII-safe. Runtime produces correct multibyte UTF-8.

## 6. Runtime UTF-8 Bytes

| Code Point | Character | UTF-8 Bytes |
|------------|-----------|-------------|
| U+4E2D | 中 | E4 B8 AD |
| U+6587 | 文 | E6 96 87 |
| U+6D4B | 测 | E6 B5 8B |
| U+8BD5 | 试 | E8 AF 95 |

## 7. Byte Split Position

Full roundtrip: `"PING \u4E2D\u6587\u6D4B\u8BD5\n"` = 16 bytes
```
50 49 4E 47 20 E4 B8 AD E6 96 87 E6 B5 8B E8 AF 95 0A
P  I  N  G  sp 中(3B)     文(3B)     测(3B)     试(3B)   \n
```

Split case: `"PING \u4E2D\n"` = 9 bytes
```
50 49 4E 47 20 E4 B8 AD 0A
P  I  N  G  sp 中(3B)   \n
```

Split at offset 6:
- Chunk 1: `50 49 4E 47 20 E4` (6 bytes) — "PING " + first byte of 中
- Chunk 2: `B8 AD 0A` (3 bytes) — remaining bytes of 中 + newline

**SPLIT_INSIDE_CODEPOINT = YES** (E4 is the lead byte of U+4E2D)

## 8. Source Diff

Only `g7_utf8BytePreservation` method changed (16 lines → 24 lines).
No other test methods, no production code, no Gradle, no Manifest.

## 9. Build Result

- `:at-shellless-test-host:assembleDebug` : PASS
- `:at-shellless-test-host:assembleDebugAndroidTest` : PASS

## 10. Targeted Device Result

- G7 `g7_utf8BytePreservation` : **1/1 PASS**
- FULL_ROUNDTRIP : PASS
- SPLIT_ROUNDTRIP : PASS

## 11. No-Production-Change Proof

- CHANGED_TEST_FILES = 1
- PRODUCTION_CHANGED = NO
- GRADLE_CHANGED = NO
- MANIFEST_CHANGED = NO
- NATIVE_CHANGED = NO

## 12. Final Verdict

**VERDICT = PASS**

Multibyte UTF-8 validation semantic restored.
Byte-level split inside U+4E2D confirmed.
G7 device test PASS.

## 13. Handoff to V4-4.2

V4-4.2 RETRY scope unchanged. G7 is now semantically complete.

NEXT_OPEN_AUTHORIZED = NO
