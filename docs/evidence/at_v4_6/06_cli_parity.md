AT.V4-6 — CLI Parity (P5/P6/P7)
Date: 2026-10-22

## P5: CLI adb shell <cmd> (one-shot)
V3 Oracle: V3 V3.3 §1 + CLI evidence
V4 Validator: Product AdbCliOneShotTest#o1-o15
V4 Result: OK (15 tests), 97.3s
- o1-o7: command resolution/version/principal/stdout/stderr/exit0/nonzero — all PASS
- o8-o12: exit7/quoting/utf8/unsupported — all PASS
- o13: sequential20 — PASS
- o14: connection reuse — PASS
- o15: local shell survives — PASS
SEMANTIC_DELTA = NONE
CLI_ONESHOT_PARITY = PASS

## P6: CLI interactive adb shell
V3 Oracle: V3 V3.3 §3 (p4/p6/p7)
V4 Validator: Product AdbCliInteractiveTest#i1-i10
V4 Result: OK (10 tests), 251s [i8 flaky on batch, PASS on re-run 81s]
- i1: no-arg opens interactive — PASS
- i2: remote principal uid=2000 — PASS
- i3: real PTY tty — PASS
- i4: cwd continuity — PASS
- i5: env continuity — PASS
- i6: foreground stdin — PASS
- i7: ctrl-c remote foreground — PASS
- i8: resize reflected — PASS (flaky on batch, PASS re-run)
- i9: utf8 — PASS
- i10: natural exit — PASS
SEMANTIC_DELTA = NONE
CLI_INTERACTIVE_PARITY = PASS

## P7: CLI pairing surface
V3 Oracle: V3 V3.3 + V4-4.2 pairing evidence
V4 Validator: ConsumerHost AtCliConsumerTest#p1-p3 + V4-4.2 frozen evidence
V4 Result:
- p1_cliPair: CLI pair path verified (V4-4.2 frozen evidence)
- p2_badCode: bad code REJECTED — PASS
- p3_badArgs: arg parsing — PASS
NOTE: Product already paired (V4-4.2). No new pairing for parity.
CLI_PAIRING_PARITY = PASS (EXPLICIT: no new pairing, uses frozen evidence)
