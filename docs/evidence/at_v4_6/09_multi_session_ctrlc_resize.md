AT.V4-6 — Multi-Session / Ctrl-C / Resize (P13/P14/P15)
Date: 2026-10-22

## P13: Multi-Session
V3 Oracle: V3 V3.3 §5 (m1-m3)
V4 Validator: Product ProgrammaticAdbConsumerTest#multiSessionIsolation
V4 Result: OK (7 tests)
- 3 independent sessions / output isolation / lifecycle isolation / close isolation
```
MULTI_SESSION_PARITY = PASS
```

## P14: Ctrl-C
V3 Oracle: V3 V3.3 §3 (p4 ctrlC)
V4 Validator: Product ProgrammaticAdbConsumerTest#ctrlC
V4 Result: OK (7 tests)
- raw 0x03 / foreground interrupted / session infrastructure valid
```
CTRL_C_PARITY = PASS
```

## P15: Resize
V3 Oracle: V3 V3.3 §3 (p5 resize)
V4 Validator: Product ProgrammaticAdbConsumerTest#resizeIsolation
V4 Result: OK (7 tests)
- PTY resize / multi-session resize isolation
```
RESIZE_PARITY = PASS
```
