# AT.V4-6 — Device Parity and Consumer Freeze

**Date:** 2026-10-22
**Baseline:** HEAD 532f816 (worktree CLEAN)
**Status:** COMPLETE

---

## Goal

Final acceptance and freeze of AndroidTerminal V4:
1. V3 → V4 final real-device capability parity (SEMANTIC_DELTA = NONE)
2. Publish formal V4 Consumer Handoff
3. Generate deliverable V4 AAR bundle
4. Freeze V4 Consumer Boundary
5. Create AT.V4-CONSUMER-FREEZE-V1 tag

No new features. No architecture changes. No production code changes.

## Baseline

```
HEAD = 532f816 (AT.V4-5: close consumer conformance and architecture enforcement)
WORKTREE = CLEAN
AT.V4-CONSUMER-FREEZE-V1 = NOT_PRESENT (before this task)
```

## Parity Methodology

Each capability P1-P18 was verified by:
1. Citing the V3 verified semantic (from V3 V3.3 closure evidence @ 75d5832)
2. Running the V4 equivalent test on the current final version (532f816)
3. Comparing semantics (SEMANTIC_DELTA = NONE)

Device: HONOR ALI-AN00 APLMUT4312005935 (Android 15 / SDK 35 / arm64-v8a)
Method: Manual `adb install -r` + direct `am instrument` (no UTP)

## V3 Oracle Methodology

V3 frozen source @ 75d5832 + V3 V3.3 closure evidence
(`AndroidTerminal-V3/docs/evidence/at_v3_3/EVIDENCE.md`) serve as the
parity oracle. V3 verified semantics for one-shot, persistent, CLI,
multi-session, transport-loss, and cross-mode are all cited in the
parity matrix.

## Device Strategy

- Manual `adb install -r` (NOT Gradle UTP — preserves identity)
- Direct `adb shell am instrument` for all tests
- 3 independent identities preserved (Product / ConsumerHost / ConformanceHost)
- Wireless Debugging initially OFF (enables Auto-WD parity P10)

## Parity Matrix Summary

All 18 gates PASS:

| Gate | Capability | Result |
|------|-----------|--------|
| P1 | Local One-Shot | PASS |
| P2 | Local Persistent | PASS |
| P3 | ADB One-Shot | PASS |
| P4 | ADB Persistent | PASS |
| P5 | CLI adb shell <cmd> | PASS |
| P6 | CLI interactive | PASS |
| P7 | CLI pairing surface | PASS (EXPLICIT: frozen evidence) |
| P8 | First-Use Bootstrap | PASS |
| P9 | Identity | PASS (3 stable) |
| P10 | Auto-WD final | PASS |
| P11 | Connection reuse | PASS |
| P12 | Recovery / transport loss | PASS |
| P13 | Multi-session | PASS |
| P14 | Ctrl-C | PASS |
| P15 | Resize | PASS |
| P16 | Natural exit / return | PASS |
| P17 | Runtime ownership | PASS |
| P18 | Restart | PASS |

V3_V4_DEVICE_PARITY = PASS
V3_V4_SEMANTIC_DELTA = NONE

## Artifact Bundle

Bundle: `dist/AT.V4-CONSUMER-FREEZE-V1/`

8 artifacts (6 AAR + 2 JAR):
- at-engine-api.jar (JAR, 23079B)
- at-engine-spi.jar (JAR, 1851B)
- at-engine-debug.aar (AAR, 2939B)
- at-backend-local-debug.aar (AAR, 133778B, libat_local_backend.so)
- at-backend-adb-debug.aar (AAR, 8861B)
- adb-engine-debug.aar (AAR, 1261778B, libadbd_engine.so)
- at-composition-android-debug.aar (AAR, 31197B)
- at-cli-debug.aar (AAR, 196881B, libatcli_env.so)

ARTIFACT_DEPENDENCY_CLOSURE = PASS
NATIVE_ARTIFACT_CLOSURE = PASS
AAR_BUNDLE_CONSUMER_BUILD = PASS (ConformanceHost as artifact-consumer proof)
ARTIFACT_SELF_CONTAINMENT = PASS

SHA256SUMS.txt = PRESENT

## Consumer Handoff

`docs/handoff/AT_V4_CONSUMER_HANDOFF.md` — formal entry point for:
- AndroidTerminal Product
- AndroidAgent Android Host
- Other Android Consumers

Covers: frozen architecture, initialization, local/ADB execution,
first-use bootstrap, identity, CLI, error semantics, ownership, AG handoff.

## Freeze Semantics

AT.V4-CONSUMER-FREEZE-V1 freezes the V4 CONSUMER BOUNDARY, not the AT
Contract. Future V5 may evolve the public API. This tag marks:
- V4 physical architecture COMPLETE
- Consumer dependency boundary FROZEN
- V4 device parity ACCEPTED
- Consumer handoff ACCEPTED
- Artifact bundle ACCEPTED

## Known Deferred Work

```
AT.EMULATOR-0        = DEFERRED (Terminal Emulator)
AT.ARTIFACT-BUNDLE-0 = DEFERRED (Maven publication)
AG.INTEGRATION-0     = DEFERRED (AndroidAgent integration)
AT.CONTRACT-1        = DEFERRED (final API freeze)
```

## Final Verdict

```
AT.V4-6 = COMPLETE
V4 = COMPLETE
V4_PHYSICAL_CLOSURE = CLOSED
V4_DEVICE_PARITY = CLOSED
V4_CONSUMER_BOUNDARY = FROZEN
CONSUMER_HANDOFF = PUBLISHED
V4_AAR_BUNDLE = PUBLISHED
RECOMMENDED_NEXT = POST_V4_CONTROLLER_DECISION
NEXT_OPEN_AUTHORIZED = NO
```
