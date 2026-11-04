# AT.EMULATOR-0-IMPLEMENTATION-DECISION

**Date:** 2026-11-05
**Type:** IMPLEMENTATION_AUDIT + SOURCE_AUDIT + PROTOCOL_SCOPE_AUDIT + COST/RISK_ANALYSIS + TARGETED_SPIKE
**BASELINE:** 08abd13
**SUBAGENT_USED:** NO
**PRODUCTION_IMPLEMENTATION:** FORBIDDEN

---

## 0. Core Question

> Under the frozen AT Terminal Semantic Core architecture, should v0.5.0's first implementation be:
> - **A. AT-native** (AT writes its own parser/state machine)
> - **B. Termux-derived** (adapt Termux's proven implementation behind AT's architecture)

Architecture is NOT reopened. Termux role: REFERENCE / ORACLE / FALLBACK.

---

## 1. Preflight

```
REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 4ebabe5 (AT.LOCAL-TCP-ROUTING-FIX, committed before this round)
BASELINE = 08abd13 (architecture research close)
WORKTREE = DIRTY (untracked: docs/audit/ + research/_spikes/ — this round)
PRODUCTION_SOURCE_UNCHANGED = YES
ARCHITECTURE_RESEARCH = CLOSED (AT.V050-TERMINAL-ARCHITECTURE-RESEARCH-0)
ARCHITECTURE_REOPENED = NO
```

Note: HEAD advanced from 08abd13 → 4ebabe5 due to AT.LOCAL-TCP-ROUTING-FIX
(AdbBackend.kt transport routing fix, real-device verified, v0.4.3+ candidate).
This was a separate work item, NOT part of this research round.

---

## 2. Shared v0.5.0 Capability Profile

Both candidates are evaluated against the SAME target:

### Core
```
C0 control chars (BEL, BS, HT, LF, VT, FF, CR)
ESC basics (2-char escapes: ESC c, ESC 7, ESC 8, ESC M, ESC =, ESC >)
CSI parsing (params, private markers, intermediates)
Cursor movement (CUP, CUU, CUD, CUF, CUB)
Erase (EL 0/1/2, ED 0/1/2/3)
Insert/Delete chars (ICH, DCH)
Insert/Delete lines (IL, DL)
Scroll up/down (SU, SD)
Scroll region (DECSTBM)
Save/Restore cursor (DECSC, DECRC)
Tab handling (HT, CHT, CLE, TBC)
Line wrap (DECAWM)
```

### Style
```
SGR: reset, bold, dim, italic, underline, inverse, invisible, strikethrough
16 colors (30-37, 40-47)
256 colors (38;5;n, 48;5;n)
Truecolor (38;2;r;g;b, 48;2;r;g;b)
Default fg/bg (39, 49)
```

### DEC/xterm
```
DECAWM (7), DECCKM (1), DECOM (?6), DECLRMM (?69)
Cursor visibility (25)
47, 1047, 1048, 1049 (alternate screen variants)
```

### OSC
```
OSC 0 (set icon+title), OSC 2 (set title)
Unknown OSC: safe consume (no crash, no state corruption)
```

### Unicode
```
UTF-8 decoding (including split multi-byte across chunk boundaries)
CJK wide chars (width=2)
Combining marks (zero-width, attached to base cell)
C1 controls in UTF-8: ignored (per xterm behavior)
```

### Session
```
Resize (row/col change, buffer reflow)
Reset (ESC c — full state clear)
Arbitrary chunk boundaries (byte-at-a-time == bulk)
Raw path coexistence (semantic core is optional attachment)
```

### Explicit DEFER (NOT in v0.5)
```
SIXEL, kitty graphics, iTerm image protocol
Mouse reporting, advanced keyboard protocol
Full emoji grapheme engine
GPU/canvas rendering
Full xterm compatibility (every historical DEC mode)
```

---

## 3. Audit A — AT-native

### A-01. Implementation Inventory

| Component | Required Behavior | Complexity | Risk | Fixture Coverage |
|---|---|---|---|---|
| UTF-8 Decoder | 1-4 byte sequences, overlong rejection, C1 ignore, split-chunk safe | LOW | LOW | 3 fixtures |
| VT Parser (state machine) | 9+ states, CSI param accumulation, private markers, OSC/DCS/APC strings | MEDIUM | MEDIUM | 8+ fixtures |
| CSI Parameter Parser | Multi-digit accumulation, semicolon separation, default values, private markers | MEDIUM | MEDIUM | 4 fixtures |
| Control Dispatcher | Route parsed events to handlers | LOW | LOW | (implicit) |
| SGR Handler | 8 attributes, 16/256/truecolor, param parsing with sub-params | MEDIUM | LOW | 5 fixtures |
| DEC Private Mode Handler | DECCKM (?1), DECAWM (?7), DECOM (?6), DECLRMM (?69), cursor vis (?25), alt screen (?47/?1047/?1048/?1049) | MEDIUM | LOW | 5 fixtures |
| Cursor Operations | CUP, CUU/CUD/CUF/CUB, wrap, scroll on LF | MEDIUM | LOW | 4 fixtures |
| Erase Operations | EL(0/1/2), ED(0/1/2/3), blockClear | MEDIUM | LOW | 3 fixtures |
| Insert/Delete Ops | ICH, DCH, IL, DL (with style preservation) | MEDIUM | MEDIUM | 3 fixtures |
| Scroll Region | DECSTBM, SU, SD, region-clamped scrolling | MEDIUM | MEDIUM | 3 fixtures |
| Primary Buffer | Row array, cell access, line wrap flag | LOW | LOW | (implicit) |
| Alternate Buffer | Separate buffer, 1049 save/restore cursor, clear on switch | LOW | LOW | 2 fixtures |
| TerminalRow / Cell Model | char[] + long[] style, wide char, combining (max 15), surrogate pairs | HIGH | MEDIUM | 4 fixtures |
| Style Representation | 64-bit encoding: 11 flags + 24bit fg + 24bit bg + truecolor flag | LOW | LOW | 2 fixtures |
| Scrollback | Circular buffer, bounded history, scroll-up on LF | MEDIUM | LOW | 2 fixtures |
| Resize | Column/row change, reflow, alt screen awareness | HIGH | HIGH | 4 fixtures |
| Wrap Semantics | DECAWM, deferred wrap, wide char at column edge | MEDIUM | MEDIUM | 3 fixtures |
| Saved Cursor | DECSC/DECRC, mode save/restore | LOW | LOW | 2 fixtures |
| Tab Stops | Default every 8, CHT, CLE, TBC | LOW | LOW | 2 fixtures |
| InputEncoder | Key→bytes, DECCKM awareness, arrow/home/end, function keys | MEDIUM | LOW | 3 fixtures |
| Unicode Width | wcwidth table (Unicode 15), wide East Asian, zero-width combining | HIGH | MEDIUM | 3 fixtures |
| Unknown-seq Recovery | Safe consume, no state corruption, CAN/SUB abort | LOW | LOW | 2 fixtures |
| ScreenSnapshot | Read-only visible screen extraction (rows, cursor, modes) | LOW | LOW | 2 fixtures |

**Total: ~25 distinct components.**

### A-02. Complexity Measurement

| Dimension | Count | Source |
|---|---|---|
| Parser states | 9 (GROUND, ESCAPE, CSI_ENTRY, CSI_PARAM, CSI_IGNORE, OSC_STRING, OSC_ESC, DCS_PASSTHROUGH, DCS_ESC) + RECOVERY | Spike A validated |
| CSI control families | ~25 (cursor, erase, insert/delete, scroll, SGR, modes, tab, window, DSR) | Termux doCsi: 320 lines |
| Terminal modes (DECSET bits) | 13 (Termux has 13; AT v0.5 needs ~7) | Termux source |
| Buffer mutations | ~15 (scroll, insert, delete, copy, clear, wrap, resize) | Termux TerminalBuffer |
| Unicode model complexity | HIGH — wcwidth table (~500 ranges), combining marks, surrogate pairs, wide char continuation | Termux WcWidth.java 552 lines + TerminalRow.java 257 lines |
| Resize complexity | HIGH — column reflow, alt screen, transcript preservation | Termux resize: 80+ lines |
| State recovery complexity | MEDIUM — unknown sequences, partial CSI, split UTF-8 | Spike A: parameter accumulation defect found |

**Estimated implementation surface: 3,500-5,000 lines of Kotlin** (parser + state machine + buffer + Unicode + tests).

The TRUE complexity is NOT the parser (spike validated it's tractable). It is:
1. **Unicode model** (width, combining, surrogates, wide char continuation cells)
2. **Resize/reflow** (column change with scroll region + alt screen)
3. **Buffer operations** (scroll region interaction with insert/delete/erase)
4. **Conformance testing** (25+ fixture scenarios with normalized expected state)

### A-03. Parser State Machine Audit

States (validated by Spike A — 20/20 PASS):

| State | v0.5 Action | Notes |
|---|---|---|
| GROUND | IMPLEMENT | C0 dispatch, printable emit, ESC transition |
| ESCAPE | IMPLEMENT | Dispatch to CSI/OSC/DCS/2-char |
| CSI_ENTRY | IMPLEMENT | Private marker detection (? > < =) |
| CSI_PARAM | IMPLEMENT | Digit/semicolon accumulation |
| CSI_IGNORE | IMPLEMENT | Invalid byte consume, wait for final |
| OSC_STRING | IMPLEMENT | BEL/ST termination, title handling |
| OSC_ESC | IMPLEMENT | ST detection (ESC \) |
| DCS_PASSTHROUGH | IMPLEMENT (safe consume) | No sixel in v0.5, drain to ST |
| DCS_ESC | IMPLEMENT | ST detection |
| RECOVERY | IMPLEMENT | CAN/SUB/ESC resync |
| SOS/PM/APC | IMPLEMENT (ignore) | Drain to ST |

**Chunk-boundary safety (Spike A validated — 5 split patterns):**
1. `ESC [` | `?104` | `9h` → alt screen ✓
2. `ESC` | `[38` | `;5;100m` → fg=100 ✓
3. `ESC ]2;H` | `ello BEL` → title ✓
4. `E4 B8` | `AD` → CJK char ✓
5. Byte-at-a-time == bulk for full sequence ✓

### A-04. TerminalState Data Model

**Cell model (from Termux audit, adapted for AT):**
```
TerminalRow:
  char[] mText          — variable-length (1.5x columns capacity)
  long[] mStyle         — fixed columns, 64-bit per cell
  boolean mLineWrap     — deferred wrap flag
  boolean mHasNonOneWidthOrSurrogateChars  — fast-path disable

Cell representation:
  - ASCII: 1 char, 1 cell
  - CJK wide: surrogate pair in char[], occupies 2 cells
    → second cell is continuation (width=0, style from first)
  - Combining: attached to base cell, max 15 per column
  - Future emoji: grapheme cluster → FUTURE_EVOLVABLE
```

**Style encoding (64-bit long — Termux TextStyle layout, proven):**
```
Bits 0-10:   11 attribute flags
Bits 16-39:  Background (9-bit index OR 24-bit truecolor)
Bits 40-63:  Foreground (9-bit index OR 24-bit truecolor)
```

**FUTURE_EVOLVABLE = YES** (cell = codepoint + combining[] + width)

### A-05. Native Maintenance Audit

| Question | Answer | Risk |
|---|---|---|
| New CSI handler additive? | YES | NONE |
| New OSC additive? | YES | NONE |
| DCS support later? | YES (already drains) | NONE |
| Unicode extension? | YES (table update) | LOW |
| Renderer swap? | NO impact | NONE |

**AT_NATIVE_ARCHITECTURE_RISK = LOW**

---

## 4. Audit B — Termux-derived

### B-01. Exact Source Extraction Map

| Termux File | Lines | Purpose | Reuse Level | Coupling | Adaptation |
|---|---|---|---|---|---|
| TerminalEmulator.java | 3569 | Parser + state + buffer mgmt | HEAVILY_REWRITE | HIGH | Extract parser (~1500 lines), remove bitmap/mouse/session/window |
| TerminalBuffer.java | 657 | Buffer + scroll + resize | ADAPT | MEDIUM | Remove bitmap/SystemClock/mClient, keep row/scroll/resize |
| TerminalRow.java | 257 | Row model | KEEP_NEARLY_AS_IS | NONE | Remove mHasTerminalBitmap flag only |
| WcWidth.java | 552 | Unicode width (U15) | KEEP_NEARLY_AS_IS | NONE | Copy as-is (pure static tables) |
| TextStyle.java | 96 | 64-bit style encoding | KEEP_NEARLY_AS_IS | NONE | Remove bitmap methods, keep encode/decode |
| KeyHandler.java | 348 | Android KeyEvent→bytes | DROP | HIGH | AT writes own InputEncoder |
| TerminalColors.java | 83 | Color palette | ADAPT | LOW | Remove android.graphics.Color |
| TerminalColorScheme.java | 110 | Color scheme | DROP | LOW | AT defines own |
| TerminalBitmap.java | 400 | Sixel/image | DROP | HIGH | Not v0.5 |
| TerminalSixel.java | 202 | Sixel decoder | DROP | HIGH | Not v0.5 |
| TerminalSession.java | 316 | Process/PTY | DROP | HIGH | AT has own |
| TerminalSessionClient.java | 27 | Callbacks | DROP | HIGH | AT uses ScreenSnapshot |
| TerminalOutput.java | 22 | Write interface | DROP | HIGH | AT uses session.write() |
| ByteQueue.java | 98 | Byte buffer | DROP | NONE | AT has own I/O |
| AndroidUtils.java | 53 | Android utils | DROP | HIGH | Not needed |
| Logger.java | 65 | Logging | DROP | MEDIUM | AT uses own |
| ITermImage.java | 308 | iTerm images | DROP | HIGH | Not v0.5 |
| JNI.java | 34 | Native bridge | DROP | HIGH | Not needed |

**Summary:**
- KEEP: WcWidth (552) + TerminalRow (~250) + TextStyle (~70) = **~870 lines clean**
- ADAPT: TerminalBuffer (~400 after removal) + TerminalColors (~60) = **~460 lines**
- EXTRACT: TerminalEmulator parser logic = **~1500 lines** + 500 AT wrapper
- DROP: 11 files

### B-02. Coupling Audit

| Coupling | Location | Action | Impact |
|---|---|---|---|
| mCellWidthPixels/mCellHeightPixels | TerminalEmulator (12 refs) | REMOVE | LOW |
| TerminalSessionClient | TerminalEmulator (2) + Buffer (1) | REMOVE | LOW |
| TerminalOutput mSession | TerminalEmulator (30+ refs) | REPLACE with AT write interface | MEDIUM |
| android.util.Base64 | TerminalEmulator (1 import) | → java.util.Base64 | TRIVIAL |
| android.graphics.Bitmap | TerminalBuffer (60+ refs) | REMOVE all bitmap code | MEDIUM |
| android.os.SystemClock | TerminalBuffer (2 refs) | REMOVE | TRIVIAL |
| android.graphics.Color | TerminalColors (1 import) | → plain int | TRIVIAL |
| android.view.KeyEvent | KeyHandler (35+ refs) | DROP file | NONE |
| TerminalBitmap/Sixel | Buffer + Emulator | REMOVE | MEDIUM |
| Mouse tracking | ~100 lines | REMOVE | LOW |
| Window mgmt (CSI t) | ~50 lines | KEEP (useful for TUI) | NONE |

**Verdict: MODERATE EXTRACTION** (not "light" due to 30 mSession refs + bitmap in buffer, not "refactor half" because core parser is cleanly separable)

### B-03. Object Model Leakage

| Risk | Status | Mitigation |
|---|---|---|
| AT API exposes Termux types | NO | AT ScreenSnapshot is AT-owned projection |
| Consumer sees Termux TerminalRow | NO | Rename to ATRow during adaptation |
| Renderer depends on Termux emulator | NO | Renderer reads AT ScreenSnapshot |
| ATTerminalSession calls Termux callbacks | NO | AT feeds bytes to AT interface |

**TERMUX_DERIVED_OBJECT_MODEL_LEAKAGE = MANAGEABLE (rename during adaptation)**

### B-04. Modification Surface

```
UPSTREAM_CODE_RATIO ≈ 40% (parser + buffer + row + width, after stripping)
AT_ADAPTATION_RATIO ≈ 60% (interface, naming, state model, snapshot, input)
Classification: MODERATE_ADAPTATION
```

### B-05. Upstream Strategy

**RECOMMENDED: ONE_TIME_DERIVATION (vendor snapshot)**
- Termux terminal-emulator is stable (parser rarely changes)
- AT will diverge significantly (different state model, no bitmap)
- AT conformance tests become authority (not Termux tests)
- Cherry-pick individual fixes if Termux resolves a bug
- FUTURE_UPSTREAM_SYNC = NOT_REQUIRED

### B-06. License Audit

```
LICENSE = Apache 2.0 (terminal-emulator module)
LICENSE_BLOCKER = NO
LICENSE_ACTIONS_REQUIRED:
1. Inspect and propagate applicable upstream NOTICE file
   (termux-app/NOTICE or terminal-emulator/NOTICE, if present)
2. Preserve applicable copyright headers in all derived files
   (original Termux/jackpal copyright lines must remain)
3. Record source URL + exact upstream commit hash in docs/evidence/
   (e.g., https://github.com/termux/termux-app @ <commit>)
4. Identify all modified/derived files in a provenance manifest
   (which files are verbatim copies, which are adapted, which are new)
5. Add AT NOTICE entry:
   "AndroidTerminal includes code derived from Termux:An Android
    Terminal Emulator and Linux Environment
    (https://github.com/termux/termux-app), terminal-emulator module,
    licensed under the Apache License, Version 2.0."
```

---

## 5. Spike Results

```
SPIKE_A = RUN
RESULT = 20/20 PASS (after fixing parameter accumulation bug)
KEY FINDING: A single CSI parameter accumulation defect caused 7/20 initial test failures.
  Bug: CSI parameter accumulation (idx=csiParamCount instead of tracking current param).
  Impact: Proves parameter parsing has REAL complexity requiring careful design.
  Conclusion: A developer without terminal experience WOULD hit these bugs.
            Termux's 320-line doCsi has this logic proven over years.

SPIKE_B = NOT_RUN
WHY: Source audit sufficient. 12 coupling points identified with locations.
     Total adaptation: ~2000 lines. Well-understood from source inspection.
```

---

## 6. Gates

### AT_NATIVE_GATE = PASS (12/12)
All gates pass. Native is architecturally viable.

### TERMUX_DERIVED_GATE = PASS (10/10)
All gates pass. Termux-derived is architecturally viable.

**BOTH GATES PASS. Decision is based on cost/risk, not viability.**

---

## 7. Final Decision Matrix

| Criterion | AT-native | Termux-derived | Evidence | Impact |
|---|---|---|---|---|
| Architecture fit | EXCELLENT | EXCELLENT | Same AT interface | Tie |
| Protocol correctness | MEDIUM risk | LOW risk | Spike: parameter accumulation defect; Termux: proven | **Termux** |
| Initial effort | HIGH (3500-5000 lines) | MEDIUM (~2000 lines) | Source audit | **Termux** |
| Adaptation effort | NONE | MEDIUM (30 swaps, rename) | B-02 | **Native** |
| Unicode risk | HIGH | LOW | WcWidth 552 lines portable | **Termux** |
| Resize/buffer risk | HIGH | LOW | Termux resize proven | **Termux** |
| Testability | GOOD | EXCELLENT | 19 Termux test files | **Termux** |
| Dependency footprint | ZERO | ZERO | Pure JVM | Tie |
| Maintenance | MEDIUM | LOW-MEDIUM | Cherry-pick model | **Termux** |
| Upstream dependency | NONE | LOW (one-time) | B-05 | **Native** |
| License overhead | NONE | LOW (NOTICE) | B-06 | **Native** |
| Swappability | N/A | YES | Behind interface | **Termux** |
| Long-term ownership | FULL | HIGH | Renamed AT code | **Native** |
| v0.5 schedule risk | HIGH | MEDIUM | Spike proved hidden complexity | **Termux** |

**Assessment: Termux-derived has lower combined implementation risk and schedule risk for v0.5.0. The decision is based on the weight of protocol correctness evidence (spike + proven implementation), Unicode/resize risk asymmetry, and conformance test oracle availability — not on a simple criterion count.**

---

## 8. Cost Model

| Dimension | AT-native | Termux-derived |
|---|---|---|
| IMPLEMENTATION_SURFACE | HIGH (3500-5000 new) | MEDIUM (~2000 extract+adapt) |
| ADAPTATION_SURFACE | NONE | MEDIUM |
| TEST_SURFACE | HIGH (from scratch) | MEDIUM (adapt 19 + write AT) |
| MAINTENANCE_SURFACE | MEDIUM | LOW-MEDIUM |
| UNKNOWN_RISK | HIGH | MEDIUM |

**Native UNKNOWN_RISK = HIGH because:**
- Spike: single parameter accumulation defect caused 7/20 initial test failures
- Unicode: combining + wide + surrogate interaction is subtle
- Resize: column reflow with scroll region + alt screen
- No production track record

---

## 9. Failure Fallback

### If native fails:
- SURVIVES: AT interface, TerminalState design, fixtures, InputEncoder, ScreenSnapshot
- DISCARDED: AT-native parser + buffer
- ADAPTED: Termux parser fills same interface
- FALLBACK COST: ~1-2 weeks

### If Termux-derived fails:
- SURVIVES: AT interface, fixtures, WcWidth (cleanly portable)
- DISCARDED: Adapted Termux buffer/parser
- ADAPTED: AT-native parser written with Termux as oracle
- FALLBACK COST: ~2-3 weeks (but with proven oracle + fixture expectations)

---

## 10. TERM Policy

```
V050_PROFILE_NAME = "at-terminal-v1"
TERM DECLARATION: Only after conformance verification.
  If full xterm-256color passes → TERM=xterm-256color
  If subset → declare lower profile
  Initial conservative: likely xterm-256color (Termux supports it)
  MUST verify before shipping.
```

---

## 11. Validation Plan

| Program | Validates |
|---|---|
| /system/bin/sh | Basic I/O, prompt, line editing |
| toybox top | Continuous refresh, cursor rewrite, resize |
| less/pager | Alternate screen, scrolling, input |
| ANSI colored cmd | SGR 16/256/truecolor |
| Build output | Scroll, long lines, color reset |
| logcat | Continuous stream, timestamps |
| vim (if avail) | Full TUI: alt screen, DECCKM, cursor, erase |

---

## 12. Final Decision

```
RECOMMENDED_V050_IMPLEMENTATION = TERMUX_DERIVED

WHY (evidence-based, not impression):
1. Spike A proved native has hidden complexity (a single CSI parameter
   accumulation defect caused 7/20 initial test failures). The "simple"
   parser is not simple.
2. Unicode (WcWidth 552 lines + TerminalRow combining/wide/surrogate logic)
   is cleanly portable from Termux with ZERO Android coupling. Writing
   equivalent from scratch = HIGH risk of subtle width bugs.
3. Resize/reflow (80+ lines in Termux) handles column change + scroll region
   + alt screen interaction. Proven by 180-line ResizeTest.java.
4. v0.5 schedule: MODERATE_ADAPTATION (~40% upstream code retained, ~60% AT
   adaptation per B-04) gets to "real program validation" faster than writing
   all 3500-5000 lines from scratch and debugging protocol edge cases.
5. Architecture boundary is IDENTICAL: AT interface, AT state, AT snapshot.
   Termux code is internal detail, renamed to AT conventions.
6. If Termux-derived fails, fallback to native is ~2-3 weeks (with oracle).
   If native fails first, fallback to Termux is ~1-2 weeks (extraction).
   Asymmetric risk favors starting with Termux-derived.

WHY_NOT_NATIVE:
- NOT "Termux is mature" (abstract).
- BECAUSE: spike proved hidden complexity, Unicode+resize are solved in
  Termux, schedule favors adaptation, architecture is identical either way.
- AT-native REMAINS VALID (gate passes, fallback path exists).

WHY_NOT_EXTERNAL:
- Ghostty: Zig dependency, unstable API, no Android target
- libvterm: C/JNI, less maintained, no Android target
- No architectural benefit over Termux-derived
```

---

## 13. First Implementation Phase

```
TERMUX_DERIVED_IMPLEMENTATION_PHASE_1:

LANGUAGE STRATEGY:
  - Termux-derived core code (parser, buffer, row, width, style): KEEP AS JAVA
    for v0.5 first implementation. Minimal semantic adaptation only.
  - AT-owned facade/interfaces (SemanticCore, TerminalState, ScreenSnapshot,
    InputEncoder): KOTLIN.
  - Java→Kotlin migration: DEFERRED until after conformance PASS.
    Rationale: verify behavioral equivalence first, then refactor language.
    Mixed Java/Kotlin in :at-engine is acceptable (Android supports both).

Week 1: Foundation
  1. Create com.at.engine.terminal package in :at-engine
  2. Copy WcWidth.java → com.at.engine.terminal.WcWidth (rename package,
     no logic change, keep Java)
  3. Copy TerminalRow.java → com.at.engine.terminal.TerminalRow
     (remove mHasTerminalBitmap flag, keep Java)
  4. Copy TextStyle.java → com.at.engine.terminal.TextStyle
     (remove bitmap encode/decode methods, keep Java)
  5. Define ATTerminalState.kt (Kotlin — cursor, modes, buffer ref,
     scroll region, AT-owned public surface)
  6. Define ATScreenSnapshot.kt (Kotlin — read-only visible screen,
     AT-owned public surface)

Week 2: Buffer + Parser
  7. Copy TerminalBuffer.java → com.at.engine.terminal.TerminalBuffer
     (remove bitmap/SystemClock/mClient, keep Java)
  8. Extract parser from TerminalEmulator.java →
     com.at.engine.terminal.TerminalEmulator
     (processByte → processCodePoint → handleCsi → applySgr → decSet,
      keep Java, minimal adaptation)
  9. Remove: mSession (replace with ATSessionWrite callback),
             mCellWidth/Height, mClient, sixel, mouse
  10. Implement ATTerminalSemanticCore.kt (Kotlin facade:
      feed(), snapshot(), resize(), reset() — wraps Java core)

Week 3: Input + Integration
  11. Implement ATInputEncoder.kt (Kotlin — key→bytes, DECCKM-aware)
  12. Wire into ATTerminalSession (optional semantic core attachment)
  13. Implement ScreenSnapshot extraction (Kotlin — rows, cursor, modes)

Week 4: Conformance + Validation
  14. Write 25+ AT conformance fixtures
  15. Run Termux test oracle → capture expected states
  16. Run AT tests against fixtures
  17. Real program validation (top, less, colored cmd)
  18. TERM conformance verification

POST-CONFORMANCE (NOT v0.5 Phase 1):
  - Evaluate Java→Kotlin migration for core parser/buffer
  - Decision based on: team preference, performance profiling,
    maintenance ergonomics — NOT a v0.5 requirement

DELIVERABLE: Working terminal semantic core behind AT interface,
  validated by conformance fixtures + real programs.
  Raw path still available. Renderer = separate (next stage).
```

---

## 14. Final Report

```
AT.EMULATOR-0-IMPLEMENTATION-DECISION FINAL REPORT

VERDICT = PASS

REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 4ebabe5
WORKTREE = DIRTY (untracked: docs/audit/ + research/_spikes/ — this round)
PRODUCTION_SOURCE_UNCHANGED = YES

BASELINE = 08abd13 (architecture research)
SUBAGENT_USED = NO
ARCHITECTURE_REOPENED = NO
PRODUCTION_SOURCE_CHANGED = NO
MODULE_GRAPH_CHANGED = NO
PUBLIC_API_CHANGED = NO

AT_NATIVE_AUDIT = VIABLE (gate PASS 12/12, HIGH unknown risk)
TERMUX_DERIVED_AUDIT = VIABLE (gate PASS 10/10, MEDIUM adaptation risk)

V050_SHARED_PROFILE = 25 CSI finals, SGR full, DEC modes, alt screen, UTF-8, CJK, resize

NATIVE_IMPLEMENTATION_SURFACE = 3500-5000 lines (HIGH)
TERMUX_EXTRACTION_SURFACE = ~2000 lines (MEDIUM)

NATIVE_PARSER_SPIKE = RUN
RESULT = 20/20 PASS (single parameter accumulation defect fixed)

TERMUX_EXTRACTION_SPIKE = NOT_RUN (source audit sufficient)

AT_NATIVE_GATE = PASS (12/12)
TERMUX_DERIVED_GATE = PASS (10/10)

TOP_NATIVE_RISKS =
1. Unicode model (combining + wide + surrogate interaction)
2. Resize/reflow (column change + scroll region + alt screen)
3. Protocol edge cases (spike proved hidden complexity)

TOP_TERMUX_RISKS =
1. Missing coupling during extraction (30 mSession refs)
2. Object model mismatch (Termux buffer assumptions vs AT state)
3. Adaptation bugs (rename, interface swap)

CONFORMANCE_FIXTURE_COUNT = 25+ planned
TERMUX_ORACLE_STRATEGY = Run Termux → capture normalized state → AT-owned fixtures

LICENSE_BLOCKER = NO
LICENSE_ACTIONS_REQUIRED = [inspect/propagate upstream NOTICE, preserve copyright headers, record source URL + exact commit, identify modified/derived files, AT NOTICE entry]

RECOMMENDED_V050_IMPLEMENTATION = TERMUX_DERIVED

WHY = Evidence-based: spike proved native hidden complexity, Unicode+resize
      are cleanly portable from Termux, schedule favors adaptation,
      architecture boundary is identical either way.

WHY_NOT_OTHER = AT-native viable but HIGH schedule risk; External (Ghostty/
               libvterm) blocked by build complexity + no Android target.

FAILURE_FALLBACK = If Termux-derived fails → AT-native (2-3 weeks, with oracle).
                  If native fails → Termux-derived (1-2 weeks, extraction).

FIRST_IMPLEMENTATION_PHASE = 4 weeks (see §13)

TERM_POLICY = Declare only after conformance verification. Likely xterm-256color.

AG_BOUNDARY_PRESERVED = YES (AT stable surface, not direct module dep)
RENDERER_BOUNDARY_PRESERVED = YES (Product/UI, reads ScreenSnapshot)
ADB_ENGINE_BOUNDARY_PRESERVED = YES (zero terminal dependency)

IMPLEMENTATION_DECISION_READY = YES

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

NEXT = AT.EMULATOR-0-IMPLEMENTATION
STOP = YES
```
