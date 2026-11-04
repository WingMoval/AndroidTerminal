# AT.EMULATOR-GAP-RESEARCH-0

**Date:** 2026-11-04
**Type:** TARGETED_RESEARCH + SOURCE_AUDIT + ISOLATED_FEASIBILITY_VALIDATION
**BASELINE:** AT.EMULATOR-HISTORICAL-RECONCILIATION-0
**SUBAGENT_USED:** NO

---

## 1. Preflight

```
PRODUCT_REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 37b33e3
WORKTREE = CLEAN (only new docs/audit/ file)
RESEARCH_ROOT = D:\codebank\AndCode\AndroidTerminal\research
```

### Research Source Inventory

| Source | Local Path | Status |
|---|---|---|
| Ghostty (libghostty-vt) | `%TEMP%\AT.EMULATOR-GAP-RESEARCH-0\ghostty` | DOWNLOADED_THIS_ROUND (shallow) |
| Termux terminal-emulator | `%TEMP%\AT.EMULATOR-GAP-RESEARCH-0\termux-app\terminal-emulator` | DOWNLOADED_THIS_ROUND (sparse) |
| Historical research docs | `AndroidTerminal\research\*` | LOCAL_EXISTING |
| LADB / ADB source | `AndroidTerminal\research\LADB` | LOCAL_EXISTING (not emulator-relevant) |

No Ghostty/Termux/xterm.js/VTE/Alacritty source existed in the local research directory.

---

## 2. Source Provenance

| PROJECT | LOCAL_PATH | ORIGIN | COMMIT | TAG | LICENSE | SOURCE_STATUS |
|---|---|---|---|---|---|---|
| Ghostty | `%TEMP%\...\ghostty` | github.com/ghostty-org/ghostty | HEAD of main (shallow) | 1.4.1-dev | **MIT** | DOWNLOADED_THIS_ROUND |
| Termux terminal-emulator | `%TEMP%\...\termux-app\terminal-emulator` | github.com/termux/termux-app | HEAD of master (shallow, sparse) | — | **Apache 2.0** | DOWNLOADED_THIS_ROUND |

PROVENANCE: COMPLETE for both (git repos with origin).

---

## 3. OQ-EM-01 — VT/ANSI Compatibility Scope

### Target Programs on Android (adb shell / local PTY)

| Program | Available? | Key VT Features Used |
|---|---|---|
| /system/bin/sh (mksh) | YES | Basic: LF, CR, BS, BEL, simple SGR colors |
| bash (if installed) | NO (stock) | Same as sh + prompt colors |
| vim/vi (if installed) | NO (stock) | Full: cursor, alt screen, SGR, resize, keypad |
| less (toybox) | YES (toybox less) | Alt screen, cursor, SGR, scroll region |
| top (toybox) | YES (toybox top) | Alt screen, cursor, SGR, periodic redraw |
| nano | NO (stock) | Alt screen, cursor, SGR, key bindings |
| tmux client | NO (stock) | Full xterm + tmux-specific |
| Colored CLI (git, grep --color) | YES | SGR 16/256 colors |
| Progress bars (curl, wget) | YES | CR, BS, SGR, no alt screen |
| Build output (gradle) | YES | SGR, heavy scroll, no alt screen |
| logcat | YES | Plain text + timestamp, no VT |

### V050_MINIMUM_TERMINAL_PROFILE

Based on what is **actually available** on a stock Android device via ADB shell or local PTY:

**REQUIRED_V050:**

| Category | Sequences | Evidence |
|---|---|---|
| Control chars | NUL, BEL, BS, HT, LF, VT, FF, CR | All shells/programs |
| Printable | UTF-8 text | All output |
| CSI cursor | CUU, CUD, CUF, CUB, CNL, CPL, CHA, CUP, HVP | toybox top, less |
| CSI erase | ED (0/1/2), EL (0/1/2) | All TUIs, progress bars |
| CSI insert/delete | IL, DL, ICH, DCH | vim (if installed), less |
| CSI scroll | SU, SD, r (scroll region) | less, top |
| CSI SGR | 0, 1, 3, 4, 5, 7, 8, 9, 22, 23, 24, 27, 29, 30-37, 39, 40-47, 49, 90-97, 100-107, 38;5;n, 48;5;n, 38;2;r;g;b, 48;2;r;g;b | Colors everywhere |
| CSI misc | h/l (private mode set/reset), s/u (save/restore cursor) | Alt screen, cursor save |
| DEC private | 1049 (alt screen), 25 (cursor visibility), 7 (auto-wrap), 6 (origin mode), 1 (application cursor) | top, less, vim |
| OSC | 0/2 (title), 7 (cwd) | Shell prompts, vim |
| Color | 16 ANSI + 256 indexed + truecolor (24-bit) | git, gradle, modern tools |
| Screen | Wrap, scroll region, alt screen, resize | top, less |
| Unicode | UTF-8 decode, East Asian Width (CJK full-width), basic combining | Android locale support |

**DEFERRED_V050:**

| Category | Reason |
|---|---|
| Mouse tracking (X10, SGR, urxvt) | No mouse on Android terminal |
| Kitty keyboard protocol | No keyboard protocol needed for v0.5 |
| Kitty graphics / sixel | No image display in v0.5 |
| Hyperlinks (OSC 8) | No link target in v0.5 renderer |
| Bracketed paste | No paste in v0.5 (input is programmatic) |
| Focus in/out | No window focus concept |
| Sixel / terminal bitmap | No image support |
| tmux control mode | No tmux on device |
| Reverb / semantic content | No a11y in v0.5 |

**NOT_REQUIRED:**
- VT100/220 hardware-specific (no hardware)
- Line printer controls (LPM)
- Printer escape sequences

### OQ-EM-01 Verdict

```
OQ_EM_01_VERDICT = RESOLVED
V050_MINIMUM_TERMINAL_PROFILE = "xterm-256color minus mouse/graphics/keyboard-protocol"
REQUIRED_SEQUENCE_SET = CSI cursor+erase+insert+scroll+SGR+mode, DEC 1049/25/7/6/1, OSC 0/2/7, 16+256+truecolor, alt screen, UTF-8+CJK width
DEFERRED_SEQUENCE_SET = mouse, kitty protocol, graphics, hyperlinks, bracketed paste, sixel, tmux control
TARGET_PROGRAM_COVERAGE = sh, toybox top/less, colored CLI, build output, logcat (all stock Android)
CONFIDENCE = HIGH
```

---

## 4. OQ-EM-02 — Implementation Approach

### Candidate A: libghostty-vt (Ghostty)

| Aspect | Finding |
|---|---|
| Independence | C-ABI, designed for embedding. BUT build system is Zig 0.17.0+ |
| Dependencies | translate-c (build helper), libxev (lazy), vaxis/z2d (lazy, for rendering). **VT core has NO external C deps** |
| C ABI | `ghostty_terminal_new/free/vt_write/resize/snapshot`, grid_ref, screen, style, modes, unicode, formatter, search, osc, sgr |
| Screen/State API | Opaque `ghostty_terminal*` + `ghostty_grid_ref` for cell traversal. Formatter can output plain text/HTML/VT |
| Android/NDK | **NOT an officially supported target.** Cross-compile via `zig cc` with `aarch64-linux-android` target is THEORICALLY possible but UNVERIFIED |
| Build toolchain | **Zig 0.17.0 required.** CMake + GhosttyZigCompiler.cmake wrapper. NOT standard NDK toolchain |
| ABI feasibility | C-ABI means linkable from JNI. BUT: requires Zig to BUILD the .so. NDK alone cannot build it |
| Thread model | Single-threaded. Caller feeds bytes via `ghostty_terminal_vt_write()`. No internal threads |
| Memory | Caller provides allocator (or default). `ghostty_terminal_free()` cleans up |
| API stability | **EXPLICITLY UNSTABLE.** Header says: "WARNING: This is an incomplete, work-in-progress API. It is not yet stable and is definitely going to change." |
| License | MIT |
| Upstream activity | Very active (v1.4.1-dev) |
| Binary size | Unknown (no Android build exists). VT core likely 1-3 MB |
| Test corpus | Extensive (Zig test suite) but NOT in C form |

**CRITICAL FINDING:** "Has C ABI" ≠ "can be built with NDK alone." Ghostty's build system REQUIRES Zig 0.17.0. The CMake cross-compile example uses `zig cc` as the compiler, not clang/NDK. Building for Android would require:
1. Installing Zig 0.17.0 on the build machine
2. Using `ZIG_TARGET=aarch64-linux-android`
3. Potentially patching for Android-specific issues (no verified Android target exists)
4. API may break between versions (explicitly unstable)

### Candidate B: Termux terminal-emulator (adapted)

| Aspect | Finding |
|---|---|
| Independence | Pure Java. Only Android import: `android.util.Base64` (trivially replaceable) |
| Language | Java (Kotlin-interoperable) |
| Size | TerminalEmulator.java: 3569 lines. TerminalBuffer.java: 769 lines. ~15 core files |
| Parser | Full VT/ANSI parser (CSI, DEC, OSC, SGR, cursor, erase, scroll, alt screen, SGR 256+truecolor) |
| Screen model | `TerminalRow[]` (circular buffer) + `char[]` per row + `long[]` style per row. 64-bit style encoding (16 flags + 24 fg + 24 bg) |
| Unicode | `WcWidth.java` (East Asian Width). Max 15 combining chars per cell |
| Resize | Supported (TerminalBuffer.resize) |
| Alt screen | Supported |
| Scrollback | Circular buffer (configurable history) |
| Test suite | 20+ test classes (TerminalTest, ScreenBufferTest, ResizeTest, DecSetTest, UnicodeInputTest, etc.) |
| Android deps | ONLY `android.util.Base64` (for SIXEL - can be removed) |
| License | **Apache 2.0** (permissive, compatible with any use) |
| Maintenance | Active (Termux project) |
| JNI cost | **ZERO** (pure Java/Kotlin, runs on Dalvik/ART) |
| Binary size impact | **ZERO** (Java/Kotlin, no native code) |
| ScreenState access | Direct Java object access (rows, cells, styles, cursor) |
| V4 boundary | Can be placed inside `:at-engine` as Java/Kotlin. No NDK needed. No new native dependency |

### Candidate C: Self-written minimal emulator

| Aspect | Assessment |
|---|---|
| Parser states | ~15-20 parser states (ground, escape, CSI, OSC, DEC, DCS...) |
| Screen behavior | Cursor, erase, scroll, insert, delete, alt screen, wrap |
| SGR | 16+256+truecolor = ~50 SGR handlers |
| Unicode complexity | UTF-8 decode + wcwidth + combining = significant |
| Test corpus requirement | 50+ test sequences minimum for correctness |
| Maintenance burden | HIGH (VT spec is complex, xterm quirks are numerous) |
| xterm quirks | Private modes, legacy sequences, error recovery |
| Security/robustness | Malicious input handling, buffer overflow prevention |
| Time estimate | 2-4 weeks for "minimum correct" + 2 weeks for test corpus |
| Future cost | Must keep up with xterm extensions, new sequences |

### IMPLEMENTATION_CANDIDATE_MATRIX

| Criterion | A: Ghostty | B: Termux (adapted) | C: Self-written |
|---|---|---|---|
| Protocol correctness | HIGH (mature) | HIGH (mature, production) | MEDIUM (risk) |
| v0.5 scope coverage | FULL (superset) | FULL (superset) | MUST DESIGN |
| Android compatibility | UNVERIFIED (Zig build) | **PROVEN** (runs on Android) | **PROVEN** (Java) |
| Build integration | COMPLEX (Zig + CMake + NDK) | **TRIVIAL** (Java source in module) | **TRIVIAL** (Kotlin source) |
| Runtime dependencies | Zig toolchain (build) | **NONE** | **NONE** |
| Binary size impact | 1-3 MB .so | **0** (Java) | **0** (Kotlin) |
| JNI/FFI cost | JNI bridge + C ABI | **NONE** | **NONE** |
| ScreenState accessibility | Via grid_ref C API | **Direct Java fields** | **Direct Kotlin fields** |
| Test corpus | Zig tests (not portable) | **20+ Java test classes** | Must write |
| Unicode maturity | HIGH | HIGH (WcWidth) | Must implement |
| Maintenance burden | MEDIUM (upstream API unstable) | LOW (stable, small) | HIGH (own) |
| License | MIT | **Apache 2.0** | Own (MIT/Apache) |
| Upstream activity | Very active | Active | N/A |
| V4 boundary preservation | RISK (NDK in :at-engine) | **CLEAN** (Java in :at-engine) | **CLEAN** (Kotlin in :at-engine) |
| Long-term full Terminal | YES (superset) | YES (can extend) | YES (can extend) |
| API stability RISK | **HIGH** (explicitly unstable) | LOW | LOW |

### OQ-EM-02 Verdict

```
OQ_EM_02_VERDICT = RESOLVED
CANDIDATE_A = libghostty-vt (MIT, C-ABI, Zig build, UNSTABLE API, unverified Android)
CANDIDATE_B = Termux terminal-emulator adapted (Apache 2.0, pure Java, proven, stable)
CANDIDATE_C = Self-written minimal (full control, high cost, high risk)
RECOMMENDED_APPROACH = B (Termux adapted) with internal interface for future swap
WHY = 
  1. PROVEN on Android (Termux runs it in production)
  2. ZERO build complexity (Java/Kotlin, no NDK, no Zig)
  3. ZERO binary size impact
  4. Direct ScreenState access (Java objects, no JNI)
  5. 20+ test classes for regression
  6. Apache 2.0 = permissive, no copyleft
  7. Preserves V4 physical boundary (stays in :at-engine as Java)
  8. Internal TerminalEmulator interface allows future swap to Ghostty if needed
  9. 3569-line parser is manageable to audit and minimally modify
KNOWN_COST = 
  - Remove/replace android.util.Base64 (SIXEL path)
  - Audit and potentially trim to v0.5 scope
  - Add AT-specific adapter layer
  - Termux is Java; may prefer Kotlin for consistency (mechanical)
KNOWN_RISK = 
  - Termux project may diverge (mitigated: fork and maintain internally)
  - Java performance for very high-throughput streams (mitigated: acceptable for v0.5)
CONFIDENCE = HIGH
```

**NOTE:** `RECOMMENDED_APPROACH ≠ FINAL_IMPLEMENTATION_DECISION.` Controller decides.

---

## 5. OQ-EM-03 — ScreenState Internal Model

### Termux Data Model (from source)

```
TerminalBuffer
├── TerminalRow[] mLines (circular buffer, size = screen + history)
├── int mScreenRows, mColumns (visible area)
├── int mActiveTranscriptRows (scrollback count)
├── int mScreenFirstRow (circular buffer index)
└── TerminalRow per row:
    ├── char[] mText (UTF-16 code units, max combining appended)
    ├── int mSpaceUsed
    ├── long[] mStyle (64-bit per cell: 16 flags + 24 fg + 24 bg)
    ├── boolean mLineWrap
    └── boolean mMayBeWide (CJK/full-width indicator)

TerminalEmulator (parser + state)
├── TerminalBuffer buffer
├── int cursorX, cursorY
├── boolean cursorVisible
├── int savedCursorX, savedCursorY
├── long savedStyle
├── boolean inAlternateBuffer
├── int scrollbackLimit
├── TerminalColorScheme colors
└── ... modes, tab stops, scroll region, etc.

TextStyle (64-bit encoding)
├── bits 0-15: flags (bold, italic, underline, blink, inverse, invisible, strikethrough, protected, dim, truecolor_fg, truecolor_bg, bitmap)
├── bits 16-39: foreground (9-bit index OR 24-bit truecolor)
└── bits 40-63: background (9-bit index OR 24-bit truecolor)
```

### Ghostty Data Model (from headers)

```
ghostty_terminal (opaque)
├── screen (ghostty_screen: grid of cells)
├── scrollback (ring buffer)
├── cursor (row, col, style, visibility)
├── modes (bitmask: alt_screen, origin, wrap, insert, app_cursor, etc.)
├── style (current SGR state)
└── grid_ref (read-only cell access for traversal)

ghostty_cell (via grid_ref)
├── codepoint (or bitmap ref)
├── width (1 or 2)
└── style (fg, bg, effects bitmask)
```

### SCREEN_MODEL_CONCEPT_MATRIX

| Concept | Termux | Ghostty | Required for v0.5? |
|---|---|---|---|
| rows/cols | mScreenRows, mColumns | size | YES |
| main buffer | TerminalRow[] (visible) | screen grid | YES |
| alternate buffer | inAlternateBuffer + separate buffer | alt screen | YES |
| scrollback | Circular buffer (mLines beyond screen) | ring buffer | YES |
| line representation | char[] + long[] style | grid of cells | YES |
| cell representation | char (UTF-16) + 64-bit style | codepoint + style struct | YES |
| codepoint/grapheme | char (UTF-16 surrogate pairs) | codepoint (u32) | YES |
| display width | WcWidth (0/1/2) | width (1/2) | YES |
| combining chars | Appended to char[] (max 15) | ? (not clear from headers) | YES |
| continuation cell | Implicit (width-2 char occupies 2 cols) | Explicit (width field) | YES |
| fg/bg color | 24-bit (index or truecolor) | color struct | YES |
| bold/dim/italic/underline/blink/inverse/strikethrough | 7 bits in style | effects bitmask | YES |
| cursor row/col | cursorX, cursorY | cursor point | YES |
| cursor visible | cursorVisible | visibility | YES |
| cursor shape/blink | NO | ? | DEFER |
| modes (wrap/origin/insert/app_cursor) | Individual booleans | bitmask | YES |
| bracketed paste | NO | mode | DEFER |
| scroll region | top/bottom margins | scroll region | YES |
| saved cursor | savedCursorX/Y + style | saved state | YES |
| tab stops | HashSet<Integer> | ? | YES |
| dirty/damage tracking | NO (full re-render) | render state (incremental) | NICE-TO-HAVE |
| resize/reflow | Buffer.resize() | reflow on resize | YES |
| immutable snapshot | NO | ghostty_snapshot (encode/restore) | YES (for Agent future) |

### Consumer Requirements

**Human Renderer (v0.5):**
- Fast row/cell traversal: iterate rows, iterate cells per row
- Style information: fg, bg, bold, underline, inverse
- Cursor: position + visibility
- Resize consistency: after resize, model is coherent
- NO dirty tracking needed initially (full re-render on each update is fine for v0.5)

**Future Agent Observer (DEFERRED):**
- Plain text snapshot: "give me the visible screen as text"
- Cursor position
- Optional scrollback (last N lines)
- NO structured cell API needed initially (plain text suffices)

### Candidate Models

**Candidate A: Adapt Termux model (row-based, circular buffer)**
- Memory: `rows × cols × (2 bytes char + 8 bytes style)` per cell = 10 bytes/cell. 80×24 = 19.2 KB visible. + scrollback.
- Update: Modify char[]/style[] in place. O(1) per cell.
- Resize: Copy rows to new buffer. O(rows×cols).
- Unicode: UTF-16 (surrogate pairs for >U+FFFF). Combining appended.
- Renderer: Direct row iteration. Fast.
- Snapshot: Copy buffer (O(rows×cols)) or serialize.
- Library coupling: LOW (self-contained Java).
- API exposure risk: LOW (internal fields, adapter wraps).

**Candidate B: Ghostty-native state + C adapter**
- Memory: Opaque C struct. Unknown layout.
- Update: Feed bytes via `ghostty_terminal_vt_write()`. O(input).
- Resize: `ghostty_terminal_resize()`.
- Unicode: Handled internally.
- Renderer: Traverse via `ghostty_grid_ref`. JNI per cell (SLOW).
- Snapshot: `ghostty_snapshot` encode/restore.
- Library coupling: HIGH (C ABI, JNI, Zig build).
- API exposure risk: MEDIUM (opaque, but C types in JNI).

**Candidate C: Row-based with runs (compressed style)**
- Memory: Fewer style words (runs of same style). ~30-50% less.
- Update: More complex (run splitting/merging).
- Resize: Same as A.
- Unicode: Same as A.
- Renderer: Must expand runs (slightly slower).
- Snapshot: Same.
- Library coupling: LOW.
- API exposure risk: LOW.

### OQ-EM-03 Verdict

```
OQ_EM_03_VERDICT = RESOLVED
SCREEN_STATE_REQUIRED_SEMANTICS = 
  rows×cols grid, alt screen, scrollback (circular), cursor (pos+vis),
  7 SGR flags, 16+256+truecolor fg/bg, scroll region, tab stops,
  saved cursor, UTF-8→UTF-16, CJK width (1/2), combining (max 15),
  line wrap flag, resize coherence
CANDIDATES = 
  A: Adapt Termux row-based model (char[]+long[] per row, circular buffer)
  B: Ghostty C-native state + JNI adapter
  C: Row-based with style runs (compressed)
RECOMMENDED_INTERNAL_MODEL = A (Termux adapted: char[] + long[] style per row, circular buffer)
  Rationale: proven, simple, fast, directly accessible from Java/Kotlin,
  no JNI, no native build, 20+ test classes for validation
PUBLIC_API_IMPLICATION = NOT_DECIDED (internal model ≠ public API; Contract-1 after implementation)
CONFIDENCE = HIGH
```

---

## 6. OQ-EM-04 — Physical Module Placement

### Current Graph (confirmed from source)

```
:at-engine-api (no deps) — public contract
:at-engine-spi (→ api) — backend SPI
:at-engine (→ api, spi) — engine implementation
:at-backend-local (→ api, spi)
:at-backend-adb (→ api, spi, :adb-engine)
:at-composition-android (→ all above)
:app (→ api, composition, :at-cli)
:at-cli (→ api)
:adb-engine (no deps)
:at-architecture-gate (no deps, enforces rules)
```

### Placement Candidates

**A: Emulator inside :at-engine (Java/Kotlin source)**

| Aspect | Assessment |
|---|---|
| Dependency direction | :at-engine already has Kotlin. Adding Java/Kotlin emulator source = NO new deps |
| Build impact | ZERO (no new plugin, no NDK, no CMake) |
| Android plugin | :at-engine is currently a JVM module (no Android). Emulator must be JVM-compatible (Termux IS, minus Base64) |
| NDK requirement | NONE |
| Architecture gate | NO change needed (no new module, no new dependency) |
| API leak risk | LOW (emulator is internal impl, not exposed via :at-engine-api) |
| Backend coupling | NONE (emulator is transport-agnostic) |
| Consumer visibility | NONE (consumer only sees :at-engine-api) |
| Testability | HIGH (unit test in :at-engine test source) |
| Publishing impact | ZERO (internal module, not published) |

**B: Separate :at-emulator module**

| Aspect | Assessment |
|---|---|
| Dependency direction | :at-engine → :at-emulator (new dependency) |
| Build impact | New module in settings.gradle. New build.gradle |
| Android plugin | JVM module (like :at-engine) |
| NDK requirement | NONE (if Java/Kotlin) |
| Architecture gate | **MUST UPDATE** (new module, new dependency rule) |
| API leak risk | LOW (internal module) |
| Backend coupling | NONE |
| Consumer visibility | NONE (not in consumer path) |
| Testability | HIGH (isolated) |
| Publishing impact | ZERO |
| V4 boundary | CLEAN (new internal module, no consumer impact) |

**C: Native module with NDK (for Ghostty)**

| Aspect | Assessment |
|---|---|
| Dependency direction | :at-engine → :at-emulator-native (new) |
| Build impact | NDK + CMake in new module. Zig toolchain for Ghostty |
| Android plugin | **REQUIRES com.android.library** (NDK needs Android plugin) |
| NDK requirement | YES (or Zig cross-compile) |
| Architecture gate | **MUST UPDATE** (new module, NDK rule) |
| API leak risk | MEDIUM (JNI surface) |
| Backend coupling | NONE |
| Consumer visibility | NONE |
| Testability | MEDIUM (native test complexity) |
| Publishing impact | ZERO |
| V4 boundary | **RISK** (Android plugin in engine layer, NDK build complexity) |

### OQ-EM-04 Verdict

```
OQ_EM_04_VERDICT = RESOLVED
PLACEMENT_CANDIDATES = 
  A: Inside :at-engine (simplest, no gate change)
  B: Separate :at-emulator module (cleaner separation, gate update)
  C: Native module (only if Ghostty chosen)
RECOMMENDED_PLACEMENT = A (inside :at-engine) for v0.5.0
  Rationale:
  1. Termux emulator is pure Java — fits naturally in :at-engine (JVM module)
  2. ZERO build system change
  3. ZERO architecture gate change
  4. Emulator is an INTERNAL implementation detail of AT Engine
  5. V3 Canonical says "Emulator 属于 AT Engine" — physically in :at-engine IS correct
  6. If later we want isolation, can extract to :at-emulator (B) without breaking
  7. Option C (native) is only needed if Ghostty is chosen (not recommended)
WHY = Simpler is better for v0.5.0 first implementation. No new module = no gate
      update = less risk. The emulator is ~5000 lines of Java, not a separate
      architectural component.
PHYSICAL_BOUNDARY_RISK = NONE (no new module, no new dependency, no NDK)
ARCHITECTURE_GATE_CHANGE_REQUIRED = NO
CONFIDENCE = HIGH
```

---

## 7. Cross-Question Consistency

```
VT scope (OQ-01): xterm-256color minus mouse/graphics
      ↓
Implementation (OQ-02): Termux adapted (covers full xterm-256color)
      ↓
Screen model (OQ-03): Termux row-based model (naturally produced by Termux parser)
      ↓
Placement (OQ-04): Inside :at-engine (Java, no NDK, no gate change)
```

**CONSISTENCY CHECK:**
- VT scope ⊂ Termux capability? **YES** (Termux supports full xterm-256color + more)
- Termux model ⊂ recommended ScreenState? **YES** (it IS the Termux model)
- Java in :at-engine compatible with V4 DAG? **YES** (no new deps)
- Consumer sees only :at-engine-api? **YES** (emulator is internal)
- Raw preview preserved? **YES** (emulator coexists, doesn't replace)

**CROSS_QUESTION_CONFLICTS = NONE**

---

## 8. License Audit

| Source | LICENSE | USAGE_MODE | OBLIGATIONS | COMPATIBILITY_RISK |
|---|---|---|---|---|
| Termux terminal-emulator | **Apache 2.0** | Adapt/fork internal | Preserve copyright + license notice | **NONE** (permissive, no copyleft) |
| Ghostty libghostty-vt | **MIT** | Link (if chosen) | Preserve copyright + license notice | **NONE** (permissive) |

Both are permissive. Apache 2.0 is preferred for Android (patent grant). No copyleft risk.

---

## 9. Historical Corrections

```
HISTORICAL_CORRECTION = 
  1. 4A.7 "ADOPT libghostty-vt as reference/primary" — this was a RECOMMENDATION
     based on 2026-09-29 research. The API was (and remains) explicitly UNSTABLE.
     The build system (Zig 0.17.0) was not fully assessed for Android feasibility
     at that time. This does NOT invalidate the recommendation as historical
     context, but the current evidence shows Termux is the lower-risk path.
  2. 4A.7 "keep an internal TerminalEmulator interface so the lib is swappable"
     — this principle is PRESERVED. The recommended approach (Termux adapted)
     still uses an internal interface, allowing future swap.
```

---

## 10. Build Spike Results

```
BUILD_SPIKES_RUN = 0 (not needed)
```

Static analysis was sufficient:
- Termux: Pure Java, only `android.util.Base64` import. Can be compiled in any JVM/Android module.
- Ghostty: Requires Zig 0.17.0. No verified Android target. CMake cross-compile uses `zig cc`.

No build spike was necessary to determine feasibility.

---

## 11. Final Verdict

```
AT.EMULATOR-GAP-RESEARCH-0 FINAL REPORT

VERDICT = PASS

PRODUCT_REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 37b33e3
WORKTREE = CLEAN (only new docs/audit/ file)

RESEARCH_ROOT = D:\codebank\AndCode\AndroidTerminal\research
SUBAGENT_USED = NO
LOCAL_RESEARCH_SOURCES_AUDITED = 0 emulator sources existed locally
EXTERNAL_SOURCES_DOWNLOADED = 2 (Ghostty shallow, Termux sparse)
BUILD_SPIKES_RUN = 0

OQ_EM_01_STATUS = RESOLVED
V050_TERMINAL_PROFILE = xterm-256color minus mouse/graphics/keyboard-protocol
OQ_EM_01_CONFIDENCE = HIGH

OQ_EM_02_STATUS = RESOLVED
IMPLEMENTATION_CANDIDATES = Ghostty(C) / Termux-adapted(Java) / Self-written
RECOMMENDED_IMPLEMENTATION = Termux terminal-emulator adapted (Apache 2.0, pure Java)
OQ_EM_02_CONFIDENCE = HIGH

OQ_EM_03_STATUS = RESOLVED
SCREEN_STATE_CANDIDATES = Termux row-based / Ghostty C-native / Style-runs
RECOMMENDED_INTERNAL_MODEL = Termux row-based (char[] + long[] style, circular buffer)
OQ_EM_03_CONFIDENCE = HIGH

OQ_EM_04_STATUS = RESOLVED
PLACEMENT_CANDIDATES = Inside :at-engine / Separate module / Native module
RECOMMENDED_PLACEMENT = Inside :at-engine (no new module, no gate change)
OQ_EM_04_CONFIDENCE = HIGH

LICENSE_AUDIT = PASS
SOURCE_PROVENANCE = COMPLETE

CROSS_QUESTION_CONFLICTS = NONE

V3_CANONICAL_VIOLATION_FOUND = NO
V4_PHYSICAL_BOUNDARY_VIOLATION_FOUND = NO

PRODUCTION_SOURCE_CHANGED = NO
PRODUCT_BUILD_FILES_CHANGED = NO
PRODUCT_MODULE_GRAPH_CHANGED = NO
PUBLIC_API_CHANGED = NO
SPI_CHANGED = NO

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

IMPLEMENTATION_DECISION_READY = YES

NEXT = AT.EMULATOR-0-IMPLEMENTATION-DECISION
STOP = YES
```
