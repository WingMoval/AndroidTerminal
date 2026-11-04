# AT.V050-TERMINAL-ARCHITECTURE-RESEARCH-0

**Date:** 2026-11-04
**Type:** ARCHITECTURE_RESEARCH + SOURCE_AUDIT + TERMINAL_SEMANTICS_BOUNDARY_REVIEW
**BASELINE:** 32829c6
**SUBAGENT_USED:** NO
**PRODUCTION_IMPLEMENTATION:** FORBIDDEN

---

## 0. Central Principle

```
DO NOT DESIGN AT AROUND TERMUX.

AT requirements → AT Engine reuse boundary → Terminal semantic architecture
→ implementation abstraction → choose AT-native / Termux-derived / other
```

Termux role: **protocol behavior reference, mature implementation reference, test oracle, third-party fallback.**
NOT: AT architecture template.

---

## 1. Preflight

```
REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 32829c6
WORKTREE = DIRTY (M at-backend-adb/.../AdbBackend.kt — pre-existing, NOT this round)
BASELINE = 32829c6 ✓
```

---

## 2. Historical Baseline (PRESERVED)

```
AT.EMULATOR-GAP-RESEARCH-0 = CLOSED
TERMUX_DERIVED = PREFERRED_MATURE_THIRD_PARTY + VALIDATED_FALLBACK
FINAL_IMPLEMENTATION_CHOICE = NOT_FROZEN
```

This round re-examines the **architecture boundary from AT's own requirements**, not the implementation choice.

---

## 3. Existing AT Architecture (NOT REOPENED)

```
AG = Intelligence / AT = Execution

AT Engine
├─ One-shot Execution (ATOneShotBackend)
└─ Persistent Execution (ATPersistentBackend → ATTerminalSession)

Backend-neutral: Local Backend / ADB Backend (→ ADB Engine)
ADB Engine = independent capability, NOT part of Terminal
```

---

## 4. TA-01 — What Is the Reusable Terminal Core?

### Data Flow

```
raw PTY/ADB bytes
  ↓
UTF-8 decode → codepoints
  ↓
VT/ANSI parser → semantic events (cursor move, SGR, erase, mode set, ...)
  ↓
Terminal state machine → mutates TerminalState
  ↓
TerminalState (UI-neutral, consumer-agnostic)
  ↓
┌─────────────────────────────────────────┐
│ Human Renderer (Product/UI)             │
│ Agent Observation Adapter (Future/DEFER)│
│ Test Oracle (Test)                      │
│ Raw Byte Passthrough (Debug)            │
└─────────────────────────────────────────┘
```

### Component Classification (Reuse-Value Test)

| Component | Human | AG | CLI/Test | Backend-neutral | UI-neutral | Layer |
|---|---:|---:|---:|---:|---:|---|
| UTF-8 Decoder | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| VT/ANSI Parser | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| Terminal State Machine | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| TerminalState (buffer, cursor, modes, scrollback) | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| Screen Snapshot (read-only copy of visible screen) | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| Render Model (font metrics, pixel positions, dirty rects) | ✓ | ✗ | ✗ | ✗ | ✗ | **UI_ONLY** |
| Renderer (Android Canvas/Compose drawing) | ✓ | ✗ | ✗ | ✗ | ✗ | **UI_ONLY** |
| Input Encoder (KeyEvent → bytes for terminal) | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| Scrollback (bounded history) | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |
| Terminal Modes (alt screen, wrap, origin, app cursor) | ✓ | ✓ | ✓ | ✓ | ✓ | ENGINE_CORE |

**Key finding from Termux source audit:** Termux's `TerminalEmulator` constructor takes `cellWidthPixels`, `cellHeightPixels`, and a `TerminalSessionClient` callback. This **couples presentation metrics and consumer callbacks into the semantic core**. AT should NOT replicate this coupling.

### TA-01 Verdict

```
TA_01_STATUS = RESOLVED
RECOMMENDED_REUSABLE_TERMINAL_CORE =
  UTF-8 decoder + VT parser + state machine + TerminalState + ScreenSnapshot + InputEncoder + Scrollback
  (all UI-neutral, backend-neutral, consumer-agnostic)
EXCLUDED_FROM_ENGINE =
  Render Model, Renderer, font metrics, pixel positions, selection, IME, theme
```

---

## 5. TA-02 — TerminalState vs ScreenState vs RenderModel

### Formal Separation

```
TerminalState (ENGINE)
  = the complete semantic state of a terminal
  = cells (codepoint + style), cursor, modes, buffers, scrollback,
    scroll region, tab stops, saved cursor, wrap state
  = UI-NEUTRAL, CONSUMER-AGNOSTIC
  = "what is the terminal showing, semantically"

ScreenSnapshot (ENGINE)
  = an immutable, read-only view of the visible portion of TerminalState
  = used for: Agent observation, test assertions, debug
  = "give me the visible screen as data"

RenderModel (UI)
  = font metrics, pixel positions, glyph runs, dirty rectangles
  = Android-specific (Canvas/Compose/View)
  = "how to paint the screen on this device"
```

### Termux Comparison (reference, not template)

Termux conflates these: `TerminalBuffer` (state) has `TerminalRow.mStyle` (semantic) but the `TerminalEmulator` also knows `cellWidthPixels` (presentation). `TerminalSessionClient` callbacks couple the parser to its consumer.

AT should keep these strictly separate:
- `TerminalState` knows nothing about pixels, fonts, or Android.
- `RenderModel` is built FROM `TerminalState` by a UI-layer adapter.
- Parser feeds bytes → mutates `TerminalState`. No callbacks to UI during parsing.

```
TA_02_STATUS = RESOLVED
TERMINAL_STATE_DEFINITION = complete semantic state (cells, cursor, modes, buffers, scrollback) — UI-neutral
SCREEN_STATE_TERM = RENAME → "ScreenSnapshot" (read-only view of visible TerminalState)
RENDER_MODEL_DEFINITION = font/pixel/selection data — belongs to Product/UI
UI_DEPENDENCY_IN_ENGINE = NO
```

---

## 6. TA-03 — Emulator Architecture (Semantic Core)

### AT's "Terminal Emulator" = Terminal Semantic Core

NOT a single class. A structured subsystem:

```
Terminal Semantic Core (AT Engine internal)
├─ VTDecoder (UTF-8 → codepoints)
├─ VTParser (codepoints → semantic events)
├─ StateMachine (events → TerminalState mutations)
├─ TerminalState (the state)
├─ InputEncoder (keys/bytes → terminal input bytes)
└─ ScreenSnapshot (read-only visible screen extraction)
```

### Ownership

```
ATTerminalSession (persistent session)
  └─ owns one Terminal Semantic Core instance
       └─ owns TerminalState

Who feeds bytes? → ATTerminalSession reads from backend, feeds to Semantic Core
Who owns parser state? → Semantic Core (internal to AT Engine)
Who owns terminal state? → Semantic Core / TerminalState
Who owns resize? → ATTerminalSession.resize() → Semantic Core.resize()
Who owns reset? → Semantic Core.reset() (ESC c)
Who observes state? → Consumer via ScreenSnapshot or events (NOT direct field access)
```

### Protocol Authority

Parser MUST follow: ECMA-48, DEC VT behavior, xterm extensions.
NO AT-specific escape protocol. `ESC[?1049h` = xterm alt-screen, period.

```
TA_03_STATUS = RESOLVED
EMULATOR = "Terminal Semantic Core" (structured subsystem, not single class)
OWNERSHIP = ATTerminalSession owns one instance
PROTOCOL = ECMA-48 + DEC + xterm (no AT-specific extensions)
```

---

## 7. TA-04 — Implementation Direction Re-evaluation

### Candidate A: AT-native Terminal Semantic Core

AT writes its own parser + state machine in Kotlin.
- Termux/Ghostty as **test oracle** (compare output)
- Full architectural control
- Must implement: ~20 parser states, ~50 SGR handlers, cursor, erase, scroll, alt screen, wrap, resize, Unicode width
- Risk: protocol edge cases, xterm quirks
- Benefit: no external object-model leakage, clean AT ownership

### Candidate B: Termux-derived behind AT architecture

Adapt Termux's parser + buffer into AT's architecture.
- **Strip**: `cellWidthPixels`, `cellHeightPixels`, `TerminalSessionClient` callback coupling
- **Keep**: parser logic, buffer model, SGR handling, resize, alt screen
- **Wrap**: behind AT's `TerminalSemanticCore` interface
- Risk: must audit 3569 lines, remove Android coupling, ensure no hidden UI assumptions
- Benefit: proven parser, 20+ test classes as oracle

### Candidate C: External core (Ghostty/libvterm) behind adapter

- Ghostty: requires Zig 0.17.0, API explicitly unstable, no verified Android build
- libvterm: C, no Android target, less maintained
- Risk: build complexity, API instability, JNI overhead
- Benefit: mature parser, large test suite

### Decision Matrix

| Criterion | A: AT-native | B: Termux-derived | C: External (Ghostty) |
|---|---|---|---|
| Architecture ownership | **FULL** | HIGH (after stripping) | MEDIUM (opaque C) |
| Protocol correctness | MUST VERIFY | **PROVEN** | **PROVEN** |
| Future extensibility | **FULL** | HIGH | MEDIUM |
| Build complexity | **NONE** | LOW (Java) | **HIGH** (Zig/NDK) |
| Dependency footprint | **ZERO** | LOW (internal) | MEDIUM (.so) |
| Android coupling | **NONE** | MUST STRIP | N/A (native) |
| Human consumer suitability | ✓ | ✓ | ✓ |
| Agent consumer suitability | ✓ | ✓ | ✓ |
| Testability | **FULL** (Kotlin tests) | HIGH (adapt tests) | MEDIUM (JNI) |
| Unicode maturity | MUST IMPLEMENT | **PROVEN** (WcWidth) | **PROVEN** |
| Maintenance cost | **HIGH** (own) | LOW (stable) | MEDIUM (upstream) |
| Swap-ability | N/A (it IS the impl) | **YES** (behind interface) | **YES** (behind adapter) |
| External object-model leakage | **NONE** | LOW (after stripping) | MEDIUM (C types) |

### TA-04 Verdict

```
TA_04_STATUS = RESOLVED (architecture) / OPEN (implementation)

AT_NATIVE = architecturally cleanest, highest long-term ownership, highest initial cost
TERMUX_DERIVED = proven parser, lower risk, requires stripping UI coupling, still swappable
OTHER = Ghostty blocked by Zig/unstable API; libvterm not Android-targeted

RECOMMENDED_IMPLEMENTATION_DIRECTION = OPEN
  (Architecture is stable; implementation choice can be made at AT.EMULATOR-0-IMPLEMENTATION-DECISION)
  Both A and B are architecturally valid behind the same AT TerminalSemanticCore interface.
  B (Termux-derived) remains the lower-risk first implementation.
  A (AT-native) remains valid for teams preferring full ownership.

FINAL_IMPLEMENTATION_FROZEN = NO
```

---

## 8. TA-05 — Shell ↔ Terminal Boundary

```
Shell Execution (ONE-SHOT + PERSISTENT)
  │
  ├─ one-shot: command → stdout/stderr/exit (NO Emulator needed)
  │
  └─ persistent: byte-stream session
        │
        ├─ Raw bytes (ALWAYS available — debug oracle, regression)
        │
        └─ Terminal Semantic Core (attached consumer of the byte stream)
              └─ TerminalState
```

Key confirmations:
- One-shot does NOT need Emulator. ✓ (V3 Canonical §9: "通常不需要 Terminal Emulator")
- Persistent raw shell remains usable WITHOUT Emulator. ✓ (current ATTerminalSession IS raw)
- Emulator is a **semantic consumer** of the byte stream, not a replacement. ✓
- Shell contract (bytes in/out, resize, close) is INDEPENDENT of Renderer. ✓
- Raw path is PRESERVED alongside Emulator. ✓ (PRE-EMULATOR FREEZE §8)

```
TA_05_STATUS = RESOLVED
SHELL_TERMINAL_BOUNDARY =
  Shell byte-stream contract is independent and always available.
  Terminal Semantic Core is an OPTIONAL semantic layer attached to persistent sessions.
  Raw path coexists; never replaced.
```

---

## 9. TA-06 — Input Side Boundary

### TerminalInputEncoder

Converts "user intent" into "bytes to send to terminal":

```
Human key event / Agent command / Test input
  ↓
TerminalInputEncoder (ENGINE_CORE)
  ↓
bytes → ATTerminalSession.write()
```

What it handles:
- Plain text → UTF-8 bytes
- Ctrl-C → 0x03, Ctrl-D → 0x04, Ctrl-Z → 0x1A
- Arrow keys → `ESC[A`/`ESC[B`/`ESC[C`/`ESC[D` (or application mode: `ESC O A`)
- Home/End → `ESC[H`/`ESC[F` (or app mode variants)
- Function keys → xterm `ESC [ 1 ~` etc.
- Application cursor mode awareness (DECCKM)

Where it belongs: **ENGINE_CORE** (terminal semantics, not Android UI).
- Human UI detects key events → calls InputEncoder → gets bytes → writes to session.
- Agent sends text → InputEncoder → bytes → writes to session.
- The encoder knows terminal modes (app cursor, app keypad) because it reads TerminalState.

Android-specific concerns (KeyEvent → semantic key, IME, soft keyboard) stay in **UI**.

```
TA_06_STATUS = RESOLVED
INPUT_SEMANTICS = TerminalInputEncoder belongs to ENGINE_CORE
  (converts semantic input to terminal bytes, mode-aware)
UI_INPUT = Android KeyEvent/IME detection belongs to Product/UI
  (detects what key was pressed, calls encoder)
```

---

## 10. TA-07 — TERM / Capability Advertisement

### Who owns TERM?

```
Backend (Local PTY / ADB PTY) → sets TERM env var for the shell process
Terminal Semantic Core → determines what it actually supports
Consumer → does NOT set TERM (it's not the terminal)
```

### v0.5.0 TERM Strategy

The Semantic Core supports: xterm-256color subset (per OQ-EM-01).
The shell's `TERM` should advertise what the **emulator** actually handles.

```
TERM_POLICY_OWNER = Terminal Semantic Core (determines supported capability set)
V050_TERM_STRATEGY = "xterm-256color" if full v0.5 scope implemented
                     "vt100" or "ansi" if subset (avoid overclaim)
CAPABILITY_OVERCLAIM_RISK = MEDIUM
  Mitigation: TERM value must match actual parser capability.
  If parser handles xterm-256color sequences → TERM=xterm-256color.
  If subset → use lower TERM. Test with `tput` and known TUI apps.
```

---

## 11. TA-08 — Future Agent Consumption

Agent does NOT read Renderer/View. Agent reads TerminalState via an adapter:

```
TerminalState
  ↓
Agent Observation Adapter (DEFERRED — not v0.5)
  ↓
{
  visibleText: "line1\nline2\n..."    // plain text, no styling
  cursor: { row: 5, col: 12 }
  scrollbackTail: "..."               // optional, last N lines
  modes: { altScreen: true, ... }     // optional
}
```

This adapter is a **thin projection** of TerminalState. It does NOT expose:
- Styled cells (unless Agent explicitly requests)
- Renderer data
- Internal buffer layout

The TerminalState design must make this projection **trivial** (iterate rows, extract text + cursor).

```
TA_08_STATUS = RESOLVED (boundary) / DEFERRED (implementation)
AGENT_CONSUMPTION = via Agent Observation Adapter (thin projection of TerminalState)
NOT = direct View/Renderer access
v0.5: adapter interface designed but NOT implemented
```

---

## 12. TA-09 — Physical Placement

Re-evaluated from semantic boundary (not inherited from last round):

| Candidate | Build | Gate | Isolation | Reuse | Extraction |
|---|---|---|---|---|---|
| A: Inside :at-engine | NONE | NONE | LOW | HIGH | Later |
| B: Separate :at-terminal-core module | 1 new module | 1 gate rule | **HIGH** | HIGH | **Immediate** |
| C: Native module | NDK/Zig | 2+ rules | HIGH | HIGH | N/A |

**Analysis:**
- The Terminal Semantic Core is a **logically distinct subsystem** within AT Engine.
- It has ZERO dependency on backends, ADB, or Android.
- It IS the reusable core that Human/AG/Test all consume.
- Physical isolation (B) makes its independence **compile-time enforceable**.
- But (A) is simpler for v0.5.0 first implementation.

**Recommendation:** Start with (A) for v0.5.0. The architecture boundary is the same either way. Extract to (B) when:
- The core grows beyond ~5K lines
- A second consumer (AG) needs to depend on it directly
- Testing isolation becomes painful

```
TA_09_STATUS = RESOLVED
RECOMMENDED_PLACEMENT_V050 = A (inside :at-engine, package com.at.engine.terminal)
FUTURE_EXTRACTION = B (separate module) when justified
NO_NEW_MODULE_V050 = YES
```

---

## 13. Renderer Boundary (Explicit)

```
RENDERER_LAYER = PRODUCT_UI

RATIONALE:
1. Renderer is Android-specific (Canvas/Compose/View, font metrics, pixel layout)
2. Agent does NOT consume Renderer
3. CLI/test does NOT consume Renderer
4. Renderer is backend-neutral but NOT consumer-neutral (only Human uses it)
5. Renderer reads TerminalState (via ScreenSnapshot or direct iteration)
   but does NOT own terminal semantics
6. V3 Canonical §12: "Android Renderer 不属于 AT Engine" — CONFIRMED by reuse-value test
```

---

## 14. Architecture Candidates

### Architecture A (RECOMMENDED)

```
AT Engine
├─ Execution Core (one-shot + persistent session lifecycle)
└─ Terminal Semantic Core (internal)
   ├─ VTDecoder
   ├─ VTParser
   ├─ StateMachine
   ├─ TerminalState
   ├─ ScreenSnapshot
   ├─ InputEncoder
   └─ Scrollback

Product (Human Terminal)
├─ Presentation Adapter (TerminalState → RenderModel)
└─ Renderer (Android drawing)

Future AG
└─ Agent Observation Adapter (TerminalState → plain/structured observation)
```

**Why:** Clean separation. Engine owns semantics. Product owns presentation. AG owns intelligence. No cross-layer leakage.

### Architecture B (alternative)

```
AT Engine
├─ Execution Core
└─ TerminalSession (owns backend stream)

:at-terminal-core (separate module)
├─ VTDecoder / Parser / StateMachine / TerminalState
└─ (pure JVM, no Android, no backend)

Product
├─ :at-terminal-renderer (Android)
└─ Presentation Adapter

AG
└─ Agent Adapter (depends on :at-terminal-core directly)
```

**Why:** Stronger physical isolation. AG can depend on terminal core without depending on AT Engine execution. But adds module complexity for v0.5.0.

### Compatibility Gate (both pass)

```
FUTURE_COMPATIBILITY_GATE = PASS
  1. Local and ADB use same terminal semantics ✓
  2. Emulator does not know backend ✓
  3. Backend does not know Renderer ✓
  4. Renderer does not own terminal semantics ✓
  5. Terminal state is Android-UI-neutral ✓
  6. Human and AG can both consume terminal semantics ✓
  7. Parser implementation can be swapped ✓
  8. Termux-derived fallback remains possible ✓
  9. AT-native implementation remains possible ✓
  10. No v0.5 limitation leaks into public API ✓
  11. Shell byte-stream contract remains independently usable ✓
  12. ADB Engine remains independent ✓
  13. Future AT.CONTRACT-1 can hide implementation choice ✓
```

---

## 15. v0.5.0 Scope

```
v0.5.0
├─ Terminal Semantic Core
│  ├─ VT parser (xterm-256color subset per OQ-EM-01)
│  ├─ TerminalState (buffer, cursor, modes, scrollback)
│  ├─ ScreenSnapshot (visible screen as data)
│  ├─ InputEncoder (mode-aware key→bytes)
│  └─ Resize / reset semantics
│
├─ Presentation Adapter (minimal)
│  └─ TerminalState → text rendering (replace raw preview)
│
├─ Minimal Human Renderer
│  └─ Text-based display (NOT full Termux clone)
│  └─ Purpose: validate Terminal capability, NOT product terminal
│
└─ Real interactive validation
   └─ toybox top, less, colored commands, vim (if available)
```

**NOT in v0.5.0:**
- Full Termux product features (splits, tabs, copy/paste, search)
- Agent Observation Adapter (interface designed, not implemented)
- Mouse, graphics, kitty protocol
- GPU/canvas rendering
- Session persistence across app death

---

## 16. v0.6 Boundary

```
v0.6 mainline = ADB Engine capability expansion
  (sync, push/pull, forward/reverse, track-devices, install)

v0.5 architecture does NOT block v0.6:
  - ADB Engine remains independent module
  - Terminal Semantic Core has zero ADB dependency
  - Backend abstraction unchanged
```

---

## 17. Final Verdict

```
AT.V050-TERMINAL-ARCHITECTURE-RESEARCH-0 FINAL REPORT

VERDICT = PASS

REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 32829c6
WORKTREE = DIRTY (pre-existing AdbBackend.kt modification, NOT this round)

BASELINE = 32829c6
SUBAGENT_USED = NO
SOURCES_AUDITED = Termux terminal-emulator (source), Ghostty (source), AT v0.4.2 (source)
EXTERNAL_SOURCES_DOWNLOADED = 0 (reused from prior round)
PROTOTYPE_RUN = NO

TA_01_REUSABLE_CORE =
  VT parser + state machine + TerminalState + ScreenSnapshot + InputEncoder + Scrollback
  (all UI-neutral, backend-neutral, consumer-agnostic)

TA_02_TERMINAL_STATE =
  TerminalState = complete semantic state (UI-neutral)
  ScreenSnapshot = read-only visible view (replaces "ScreenState")
  RenderModel = UI-layer (font/pixel/selection)
  UI_DEPENDENCY_IN_ENGINE = NO

TA_03_EMULATOR_ARCHITECTURE =
  "Terminal Semantic Core" = structured subsystem (decoder+parser+state+input)
  Owned by ATTerminalSession. Protocol = ECMA-48+DEC+xterm.

TA_04_IMPLEMENTATION_DIRECTION = OPEN
  (Architecture stable; AT-native and Termux-derived both valid behind same interface)

TA_05_SHELL_TERMINAL_BOUNDARY =
  Shell byte-stream independent. Emulator = optional semantic layer. Raw path preserved.

TA_06_INPUT_SEMANTICS =
  TerminalInputEncoder = ENGINE_CORE (mode-aware key→bytes)
  Android KeyEvent/IME = Product/UI

TA_07_TERM_POLICY =
  TERM owned by Semantic Core. v0.5: xterm-256color if full scope, else lower.

TA_08_AGENT_CONSUMPTION =
  Via Agent Observation Adapter (thin projection). NOT via Renderer. DEFERRED.

TA_09_PHYSICAL_PLACEMENT =
  v0.5: inside :at-engine (package com.at.engine.terminal). Future: extract to module.

AT_ENGINE_TERMINAL_COMPONENTS =
  VTDecoder, VTParser, StateMachine, TerminalState, ScreenSnapshot, InputEncoder, Scrollback

PRODUCT_UI_COMPONENTS =
  Presentation Adapter, Renderer, font metrics, selection, IME, theme

TERMUX_ARCHITECTURE_COPIED = NO
TERMUX_ROLE = REFERENCE / ORACLE / FALLBACK
AT_NATIVE_REMAINS_POSSIBLE = YES
TERMUX_DERIVED_REMAINS_POSSIBLE = YES
RENDERER_IN_AT_ENGINE = NO
FUTURE_COMPATIBILITY_GATE = PASS

V050_RECOMMENDED_SCOPE =
  Terminal Semantic Core + minimal presentation adapter + minimal human renderer
  + real interactive validation (top, less, colored CLI)

PUBLIC_API_FREEZE = NO

PRODUCTION_SOURCE_CHANGED = NO
BUILD_CHANGED = NO
MODULE_GRAPH_CHANGED = NO
PUBLIC_API_CHANGED = NO
SPI_CHANGED = NO

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

IMPLEMENTATION_DECISION_READY = YES (architecture) / NO (implementation choice)

NEXT = AT.EMULATOR-0-IMPLEMENTATION-DECISION
STOP = YES
```
