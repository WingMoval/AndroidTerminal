# AT.EMULATOR-HISTORICAL-RECONCILIATION-0

**Date:** 2026-11-04
**Type:** HISTORICAL_RESEARCH / ARCHITECTURE_RECONCILIATION
**IMPLEMENTATION:** FORBIDDEN
**FINAL_ARCHITECTURE_DECISION:** FORBIDDEN

---

## 1. Preflight

```
REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 64384ab
WORKTREE = CLEAN
LATEST_RELEVANT_TAG = v0.4.2
```

### Current Module Graph (BUILD_GRAPH)

```
:adb-engine              (no deps) — ADB Engine
:at-engine-api           (no deps) — Public consumer contract
:at-engine-spi           → api — Backend SPI
:at-engine               → api + spi — Engine implementation
:at-backend-local        → api + spi — Local Backend
:at-backend-adb          → api + spi + :adb-engine — ADB Backend
:at-composition-android  → api + spi + engine + local + adb + :adb-engine
:app                     → api + composition + :at-cli — Human UI
:at-cli                  → api — CLI bridge
:test-host, :adb-only-test-host, :at-shellless-test-host,
:at-cli-consumer-host, :at-consumer-conformance-host, :at-architecture-gate
```

---

## 2. Historical Source Inventory

| Stage | Doc | Location |
|---|---|---|
| Pre-project survey | step4a0_human_agent_terminal_architecture_survey.md | `AndroidTerminal/docs/` |
| Research master (4A.1-4A.8) | human_agent_terminal_research_maste.md | `AndroidTerminal/docs/` |
| 4A.7 Architecture | human_agent_terminal_architecture.md | `AndroidTerminal/docs/architecture/` |
| 4B MVP plan | step4b0_mvp_implementation_plan.md | `AndroidTerminal/docs/implementation/` |
| V3 Canonical | AndroidTerminal_Canonical_V1.md | `V4-LOCAL-TCP-042/docs/architecture/` |
| PRE-EMULATOR FREEZE | AT.PRE-EMULATOR-FREEZE.md | `V4-LOCAL-TCP-042/docs/freeze/` |
| V4 Physical Freeze | AT.V4-0_Physical_Architecture_Freeze.md | `V4-LOCAL-TCP-042/docs/architecture/` |
| V4 Master SSOT | AT.V4_MASTER_SSOT.md | `V4-LOCAL-TCP-042/docs/` |
| PERSIST-0 | AT.PERSIST-0_Local_Persistent_Vertical_Slice.md | `V4-LOCAL-TCP-042/docs/implementation/` |

---

## 3. Project-start Research Findings (4A.0, 2026-09-27)

**Layer model (CONFIRMED in 4A.1):**

```
Layer D: UI / Client (DISPOSABLE) — rendering, input, IME
Layer C: Session Runtime (PERSISTENT) — process lifecycle, attach/detach
Layer B: Terminal Emulator Core (PLATFORM-INDEPENDENT) — VT parser, screen grid
Layer A: PTY / Process / Transport — PTY or stream; read/write; resize
```

**Key definitions from 4A.0:**

- Terminal Emulator = "core, reusable" — VT/ANSI parser, screen grid (char+attrs), scrollback, alt screen, Unicode. **Independent of transport.**
- Screen State = "grid of (char, attributes) + scrollback + cursor + modes." **Independent of BOTH transport AND UI.** Agent can read without UI.
- Session Runtime = "Persistent session, process lifecycle, attach/detach, multi-session, output buffering/broadcast, resize propagation, session state query, reconnect/recovery."
- **Session Runtime OWNS the Emulator** (tmux model: server owns session, client disposable).
- Human/Agent = "clients" of Session Manager (both disposable).
- One-shot = "convenience wrapper over persistent" (later revised in 4A.7 Two-Path).
- PTY ≠ Emulator: PTY = kernel I/O + termios; Emulator = user-space VT state machine.

**Q27 answer:** "TerminalEmulator = Core (VT parser + screen, independent C++ class); TerminalSession = owns ADB stream + Emulator + process state; SessionManager = manages multiple Sessions; Human UI + Agent API = pluggable clients."

---

## 4. 4A Final Findings (H1-H14, 4A.7 synthesis)

| ID | Hypothesis | Final Status |
|---|---|---|
| H1 | Emulator independent of UI (reusable core) | **CONFIRMED** |
| H2 | Session independent of Activity | **CONFIRMED** |
| H3 | Agent & Human share ADB Runtime | **REFINED by 4A.8** (share AT Core model, not same instance) |
| H4 | Agent & Human share SAME TerminalSession | **DEFERRED** (separate sessions default; explicit attach) |
| H5 | Both one-shot AND interactive needed | **REFINED by 4A.8** (one-shot for AGA v0; interactive deferred) |
| H6 | ADB Runtime = transport, NOT Session Server | **CONFIRMED** |
| H7 | Screen state essential for Agent interactive observation | **RESOLVED** (not for one-shot; IS for interactive) |
| H8 | Skills = primary extension, not new Tools | **REFINED by 4A.8** (Tool Registry needed; Skills = instructions) |
| H9 | Screen state = pure data, independent of transport+UI | **CONFIRMED** |
| H10 | PTY = smart pipe; Emulator = state machine; ADB host sees frames only | **CONFIRMED** |
| H11 | Session Runtime owns Emulator (not UI) | **CONFIRMED FOR FULL TERMINAL / DEFERRED** |
| H12 | Agent observation: screen state + optional raw stream | **REFINED by 4A.8** |
| H13 | Explicit input ownership/handoff for shared session | **DEFERRED** |
| H14 | Read-only observer clients | **DEFERRED** |

**Two-Path Model (4A.7 DECISION, OQ-08):**
- ExecSession → `shell,v2,raw` (one-shot: separate out/err + exit; NO emulator)
- TerminalSession → `shell,v2,pty` (interactive: merged + VT parse + Ctrl-C + resize)
- Both share Layer A + SessionManager. Independent low-level paths.

**4A.8 revision (2026-10-02):** One-shot is sufficient for AGA v0. Interactive PTY / full TerminalSession / shared terminal = **DEFERRED** (not cancelled). Priority changed, not technical validity.

---

## 5. 4B Planning Findings

From step4b0_mvp_implementation_plan.md (PLANNING ONLY, priority SUPERSEDED by 4A.8/4B.R1):

- Layer placement: D (clients) / C (Session Runtime in Foreground Service) / B (TerminalEmulator, libghostty-vt preferred) / A (ADB Runtime EXISTS)
- Implementation language: **libghostty-vt (C-ABI, zero-dep)** as preferred Layer B; keep internal TerminalEmulator interface (swappable)
- TerminalSession ownership: "owns one Layer B Emulator + one Layer A pty stream"
- Session Runtime lifetime: MVP = in-app Foreground Service; Future = device-side helper
- Two-Path execution: ExecSession (raw, no emulator) + TerminalSession (pty, with emulator)
- Human-Agent: shared Session Runtime, SEPARATE sessions default, EXPLICIT attach

**STATUS:** Design reference only. Priority SUPERSEDED. Technical content NOT invalidated.

---

## 6. Architecture Reframing Findings

The `human_agent_terminal_architecture.md` (4A.7) is the "Architecture Reframing" document.

**Early definition:** "Terminal Emulator = human-facing presentation" — this was the **PRE-4A.7** framing.

**4A.7 correction:** Terminal Emulator is NOT "human-facing presentation." It is a **platform-independent core** (Layer B) that produces Screen State. The **Renderer** (Layer D) is what is human-facing.

**V3 Canonical §12 confirms:** "TERM.A0.5 已经修订 TERM.A0 原来的 Presentation 边界，将 Emulator / Screen State 移入 AT Engine."

**STATUS of early "Emulator = presentation":** `SUPERSEDED_BY_V3_CANONICAL`

---

## 7. V3 Canonical Findings (AUTHORITATIVE)

From `AndroidTerminal_Canonical_V1.md`:

**AT Engine definition (§5):**
> "独立于具体 Human UI、Agent、ADB 协议和具体 Shell 的可复用 Terminal Execution Capability."

Contains:
```
AT Engine
├─ One-shot Execution
└─ Persistent Terminal
   ├─ TerminalSession
   ├─ Terminal Emulator
   ├─ Screen State
   └─ Session Lifecycle
```

**§12 (Terminal Emulator & Screen State) — FROZEN:**
> "Terminal Emulator / Screen State / cursor / terminal modes / scrollback **属于 AT Engine**. Android Renderer **不属于** AT Engine."

Data path:
```
Program → PTY/remote PTY → raw bytes → Terminal Emulator → Screen State
Screen State → Android Human Renderer / Future Agent Observer
```

**§11 (TerminalSession):**
> "一个正在活着的终端." Aggregates Backend instance + Terminal Emulator + Screen State + terminal size + lifecycle + exit/error. **NOT** three serial modules; a logical aggregate.

**§9 (One-shot):** "通常不需要 Terminal Emulator." Still goes through Backend.

**§17 (Human-Agent Shared Session):**
> "当前不假设 Human 和 Agent 默认共享 TerminalSession." = **DEFERRED**
> DebugBridge (AT.DEBUG-0) verified HUMAN_DEBUG_SHARED_SESSION only. HUMAN_AGENT_SHARED_SESSION = DEFERRED/NOT_STARTED.

**§3.3 (Contract freeze timing):**
> "当前生产代码尚不存在正式 TerminalSession、Terminal Emulator、Screen State 和统一 Interactive Backend，因此 Contract 必须经过实际实现验证."

**FROZEN in V3 Canonical:**
- AT Engine / ADB Engine separation (consumer→provider)
- One-shot + Persistent as two execution semantics
- Terminal Emulator + Screen State → AT Engine
- Android Renderer → Product/Human UI (NOT AT Engine)
- Backend Adapter pattern
- Shell ≠ Terminal (Shell is launch target)
- AG does not own AT (AG uses AT via TerminalTool)

**NOT FROZEN in V3 Canonical:**
- Final AT public API shape
- TerminalSession final API
- Emulator implementation (language, library, structure)
- ScreenState internal data structure
- VT/ANSI compatibility scope
- Multi-session public API
- Attach/handoff API
- Contract-1 timing (after Emulator implementation)

---

## 8. PRE-EMULATOR FREEZE Findings

From `AT.PRE-EMULATOR-FREEZE.md` (tag: AT.PRE-EMULATOR-FREEZE-V1):

**Why allowed to enter Emulator phase:**
```
PERSISTENT_EXECUTION_CAPABILITY = MATURE_ENOUGH_FOR_EMULATOR
```
All byte-stream I/O, lifecycle, resize, cleanup, multi-session, connection reuse, transport loss verified on real paths.

**Explicitly NOT Frozen (§10):**
```
TERMINAL_EMULATOR              = NOT_STARTED
SCREEN_STATE                   = NOT_STARTED
STRUCTURED_TERMINAL_OBSERVATION = NOT_STARTED
AT_CONTRACT_STATUS             = DRAFT
AT_CONTRACT_FREEZE_AUTHORIZED  = NO
```

**Raw Preview role (§8) — FROZEN:**
```
RAW_TERMINAL_UI = RAW TERMINAL PREVIEW / DEV PREVIEW
```
Role: Regression Oracle / Debug View / Raw Byte Evidence Path.
**NOT** a formal Emulator / Screen State / product renderer.
Emulator work must NOT delete it initially.

**Change Control (§14):** Emulator implementation inconvenience must NOT modify frozen execution layer. Only genuine contract gap allows modification.

---

## 9. V4 Physical Boundary Findings

From `AT.V4-0_Physical_Architecture_Freeze.md` + `AT.V4_MASTER_SSOT.md`:

**V4 does NOT change (§2, V4-0):**
- Execution semantics (one-shot / persistent / Local / ADB / CLI)
- ADB protocol, PTY semantics, Pairing, Recovery, Identity, CLI
- AT Engine / ADB Engine / Backend canonical responsibilities
- AdbRuntime UNIQUE lifecycle ownership

**V4 DOES change (physical only):**
- Module split: `:at-engine-api` + `:at-engine-spi` + `:at-engine` (Candidate B)
- Consumer boundary: Normal consumer depends ONLY on `:at-engine-api` + `:at-composition-android`
- Composition ownership: `:at-composition-android` wires all backends
- Architecture gate: `:at-architecture-gate` enforces boundaries at build time

**V4 semantic delta: NONE.** (V3_V4_SEMANTIC_DELTA = NONE, confirmed by V4-6 device parity.)

**Consumer dependency prohibitions:**
- Consumer MUST NOT depend on `:at-engine` (implementation)
- Consumer MUST NOT depend on `:at-backend-local` / `:at-backend-adb`
- Consumer MUST NOT depend on `:adb-engine`
- Consumer MUST NOT import backend SPI types

---

## 10. Current Source Reality (v0.4.2 @ 64384ab)

**Who owns persistent session?**
`:at-engine` (`DefaultATEngine.openSession()` → `ATPersistentBackend.openSession()`). The `ATTerminalSession` interface (in `:at-engine-api`) is the public contract. Backend implementations (`LocalPersistentBackend`, `AdbPersistentBackend`) provide the actual stream.

**Who owns lifecycle?**
`ATSessionState` enum: OPENING → ACTIVE → EXITED/FAILED/CLOSED. The session owns its lifecycle. No DETACHED/REATTACHING states exist (attach/detach = DEFERRED).

**Where do output bytes enter UI?**
`ATSessionEvent.Data(bytes)` → listener callback → `AppTerminal` → `MainActivity` (RAW text append to TextView). **No parsing, no screen grid, no VT interpretation.**

**Where is raw preview?**
`MainActivity` IS the raw preview. Comment: "This is a RAW terminal preview (no Terminal Emulator / Screen State)."

**Resize path (UI → backend):**
`MainActivity` → `AppTerminal.resize(rows, cols)` → `ATTerminalSession.resize(rows, cols)` → `ATPersistentBackend.resize()` → (Local: `ioctl(TIOCSWINSZ)` / ADB: `kIdWindowSizeChange` frame).

**Input path (UI → backend):**
`MainActivity` → `AppTerminal.write(bytes)` → `ATTerminalSession.write(bytes)` → `ATPersistentBackend.write()` → (Local: `write(fd)` / ADB: stdin frame).

**Unified session abstraction?**
YES. Both Local and ADB persistent go through `ATTerminalSession` (same interface, same events, same lifecycle).

**Any Emulator / ScreenState 雏形?**
**NO.** Grep for `TerminalEmulator`, `ScreenState`, `VTParser` across entire repo: **zero matches in production code.** The concept exists only in docs.

---

## 11. Session Runtime Terminology Reconciliation

**EARLIEST definition (4A.0/4A.7):**
"Session Runtime = Layer C (PERSISTENT): owns session state, process lifecycle, attach/detach, multi-session, output buffering, resize propagation, reconnect/recovery. **Owns the Emulator.**"

**CURRENT equivalent:**
The "Session Runtime" responsibilities are **PARTIALLY_ABSORBED** into:
- `ATTerminalSession` (lifecycle, write/read, resize, close) — in `:at-engine-api`
- `ATPersistentBackend` implementations (actual stream I/O) — in backends
- `ATSessionEvent` (output broadcast) — in `:at-engine-api`
- `AdbRuntime` (ADB lifecycle specifically) — in `:adb-engine`

**What is NOT yet implemented (was in "Session Runtime"):**
- Attach/detach
- Multi-client / observer
- Input ownership / handoff
- Screen state query
- Reconnect/reattach
- Background task manager
- Session state across transport loss (beyond "DEAD")

**VERDICT:** `PARTIALLY_ABSORBED`. The core lifecycle + byte I/O is implemented as `ATTerminalSession`. The full "Session Runtime" (tmux-like server with multi-client, attach, observer) is NOT built and NOT explicitly named in current source. The concept was intentionally simplified: current `ATTerminalSession` is a **byte-stream session**, not a full session server.

**DATA FLOW vs OWNERSHIP distinction:**
- DATA FLOW: raw bytes → (future: Emulator → ScreenState → Renderer)
- OWNERSHIP: ATTerminalSession owns the stream; (future: would own Emulator)
- These are NOT the same. Current session does NOT own an Emulator because none exists.

---

## 12. Emulator Terminology Reconciliation

| Term | Earliest Definition | Latest Canonical | Current Source | Status |
|---|---|---|---|---|
| Terminal Emulator | "Core, reusable C++ class" (4A.0) | "属于 AT Engine" (V3 §12) | Does NOT exist | NOT_STARTED |
| Screen State | "Grid of (char,attrs) + scrollback + cursor + modes" (4A.1) | "属于 AT Engine" (V3 §12) | Does NOT exist | NOT_STARTED |
| Renderer | "Android View renders screen" (4A.3) | "Android Renderer 不属于 AT Engine" (V3 §12) | `MainActivity` TextView (RAW) | IMPL (raw only) |
| Terminal View | "Layer D, disposable" (4A.3) | "Product / Human UI" (V3) | `MainActivity` | IMPL (raw) |
| Presentation | "Emulator = presentation" (pre-4A.7) | SUPERSEDED (Emulator ≠ presentation) | N/A | SUPERSEDED |
| Raw Preview | "Debug/Dev view of raw bytes" (PRE-EMULATOR §8) | "Regression Oracle / Debug View" (FROZEN role) | `MainActivity` | IMPL (FROZEN role) |

---

## 13. Decision Matrix

| Question | Category | Evidence |
|---|---|---|
| Emulator belongs to which layer | **A** (HISTORICALLY_FROZEN) | V3 §12: "属于 AT Engine" |
| Renderer belongs to which layer | **A** (HISTORICALLY_FROZEN) | V3 §12: "Android Renderer 不属于 AT Engine" |
| ScreenState ownership | **A** (HISTORICALLY_FROZEN) | V3 §12: "属于 AT Engine" |
| Session ownership | **A** (HISTORICALLY_FROZEN) | V3 §11: TerminalSession aggregates Emulator+ScreenState |
| Session Runtime meaning | **B** (SUPERSEDED) | Absorbed into ATTerminalSession + backends; full runtime DEFERRED |
| PTY vs Emulator | **A** (HISTORICALLY_FROZEN) | H10 CONFIRMED: PTY=smart pipe, Emulator=state machine |
| Local vs ADB semantic relationship | **A** (HISTORICALLY_FROZEN) | Same AT Engine, different Backends |
| One-shot vs persistent relationship | **A** (HISTORICALLY_FROZEN) | Two execution semantics; one-shot "通常不需要 Emulator" |
| Agent ordinary Shell observation | **A** (HISTORICALLY_FROZEN) | stdout/stderr/exit sufficient (H7) |
| Agent interactive observation | **F** (DEFERRED) | Screen state needed; NOT_STARTED |
| Human-Agent shared session | **F** (DEFERRED) | V3 §17: DEFERRED; DebugBridge = HUMAN_DEBUG only |
| Raw preview role | **A** (HISTORICALLY_FROZEN) | PRE-EMULATOR §8: Regression Oracle / Debug View |
| Resize ownership | **A** (HISTORICALLY_FROZEN) | Session→Backend→provider (verified) |
| Input ownership | **A** (HISTORICALLY_FROZEN basic) | write(bytes) on session; handoff DEFERRED |
| Terminal modes | **E** (STILL_OPEN) | No definition beyond "terminal modes" in V3 |
| Cursor | **E** (STILL_OPEN) | Named in V3 §12 but no API/structure defined |
| Scrollback | **E** (STILL_OPEN) | Named in V3 §12 but no size/structure defined |
| Alternate screen | **E** (STILL_OPEN) | Named in 4A research but no scope defined |
| Unicode | **E** (STILL_OPEN) | Named but no scope (UTF-8? width? combining?) |
| ScreenState internal representation | **E** (STILL_OPEN) | V3 says "pure data" but no structure |
| VT/ANSI compatibility scope | **E** (STILL_OPEN) | xterm? VT220? full xterm.js? |
| Implementation language | **E** (STILL_OPEN) | 4A.7 suggested libghostty-vt (C); V3 does NOT freeze |
| Implementation library | **E** (STILL_OPEN) | libghostty-vt preferred in 4A.7; NOT frozen in V3 |
| Module placement | **E** (STILL_OPEN) | "属于 AT Engine" but which module? :at-engine? new module? |
| Public API exposure | **E** (STILL_OPEN) | ScreenState API / observation API = NOT FROZEN |
| Contract-1 timing | **A** (HISTORICALLY_FROZEN) | AFTER Emulator implementation (PRE-EMULATOR §11) |

---

## 14. Supersession Graph

```
EARLY RESEARCH (4A.0, 2026-09-27)
  "Emulator = human-facing presentation" (implicit)
  "One-shot = convenience wrapper over persistent"
  ↓
4A.7 FINAL (2026-09-29)
  CORRECTED: Emulator = platform-independent core (Layer B)
  CORRECTED: Two-Path Model (independent paths, not wrapper)
  ADDED: H1-H14, Two-Path, Session Runtime owns Emulator
  ↓
4A.8 RE-BASELINE (2026-10-02)
  PRIORITY CHANGED: one-shot first for AGA v0; interactive DEFERRED
  H3 REFINED: share AT Core model, not same runtime instance
  H4/H5/H11/H12/H13/H14: DEFERRED (not invalidated)
  ↓
V3 CANONICAL (2026-10-10)
  FROZEN: Emulator + ScreenState → AT Engine
  FROZEN: Renderer → Product/Human UI
  FROZEN: AT Engine ≠ ADB Engine (consumer→provider)
  NOT FROZEN: implementation details, final API, contract
  RENAMED: "Session Runtime" concept simplified to ATTerminalSession
  ↓
PRE-EMULATOR FREEZE (2026-10-20)
  FROZEN: execution baseline (byte-stream, lifecycle, resize, etc.)
  NOT_STARTED: TERMINAL_EMULATOR, SCREEN_STATE, STRUCTURED_OBSERVATION
  FROZEN: Raw preview role = Regression Oracle
  ↓
V4 PHYSICAL CLOSURE (2026-10-21 → 2026-10-22)
  PHYSICAL ONLY: module split, consumer boundary, composition
  SEMANTIC_DELTA = NONE
  ↓
CURRENT v0.4.2 SOURCE (64384ab)
  ATTerminalSession = byte-stream session (verified)
  NO Emulator / ScreenState code exists
  Raw preview = MainActivity (TextView append)
```

**Conflicts resolved:**

| OLD | NEW | SUPERSEDED_BY | WHY |
|---|---|---|---|
| "One-shot = wrapper over persistent" | "Two independent paths" | 4A.7 Two-Path | Technical: raw vs pty are different ADB modes |
| "Emulator = presentation" | "Emulator = AT Engine core" | V3 §12 / TERM.A0.5 | Agent needs screen state without UI |
| "Session Runtime (tmux-like server)" | "ATTerminalSession (byte-stream)" | V3 Canonical | Simplified for current scope; full runtime deferred |
| "4B.1.3 Interactive PTY = next" | "AT Core v0.1 → AGA v0" | 4A.8 / 4B.R1 | Product priority: AG first, full terminal later |

---

## 15. Physical Boundary Constraints

Current boundaries Emulator implementation MUST respect:

| Boundary | Constraint |
|---|---|
| `:at-engine-api` | Public contract. Emulator/ScreenState API (if public) goes HERE |
| `:at-engine-spi` | Backend SPI. Emulator is NOT a backend concern |
| `:at-engine` | Implementation. Emulator implementation likely HERE |
| `:at-backend-*` | MUST NOT contain VT parsing or screen state |
| `:app` | MUST NOT contain VT parsing (Renderer only) |
| `:adb-engine` | MUST NOT contain terminal semantics |
| Consumer boundary | Consumers see only `:at-engine-api` |
| Dependency direction | api ← spi ← engine ← backends ← composition ← app |

**Prohibited for Emulator work:**
- Adding VT parsing to `:at-backend-*` or `:adb-engine`
- Adding ScreenState to `:app`
- Breaking consumer dependency rules
- Modifying frozen execution semantics (byte-stream I/O, lifecycle, resize)

---

## 16. Build Preservation Constraints

```
CURRENT_BUILD_INVARIANTS:
  - 15 modules in settings.gradle
  - Candidate B DAG (no cycles)
  - :at-engine-api has ZERO project deps
  - :adb-engine has ZERO project deps
  - :at-architecture-gate enforces all boundaries at build time

CURRENT_DEPENDENCY_INVARIANTS:
  - Normal consumer → :at-engine-api + :at-composition-android ONLY
  - :at-engine does NOT depend on concrete backends
  - :at-backend-* depend on :at-engine-spi (not :at-engine implementation)
  - No :app import in any non-app module

CURRENT_CONSUMER_INVARIANTS:
  - Consumer MUST NOT see AdbRuntime / AdbEngine / backend classes
  - Consumer MUST NOT construct backends directly
  - at-composition-android is the ONLY wiring point
```

**Emulator work MUST NOT:**
- Add new modules without updating architecture-gate
- Create dependency cycles
- Expose backend SPI types to consumers
- Modify :at-engine-api public surface without Contract-1 review

---

## 17. True Open Questions

| ID | Question | Why Still Open | History Says | Source Says | Missing Evidence | Blocks EMULATOR-0? |
|---|---|---|---|---|---|---|
| OQ-EM-01 | VT/ANSI compatibility scope (xterm? VT220? xterm.js?) | V3 says "Terminal Emulator" but never defines which sequences | 4A.1: xterm = "compatibility baseline"; 4A.6: libghostty-vt supports full VT | No code exists | Decision on minimum sequence set for v0.5.0 | YES (implementation scope) |
| OQ-EM-02 | Implementation: self-written vs libghostty-vt vs other | 4A.7 "preferred" libghostty-vt but V3 does NOT freeze | 4A.7: "ADOPT libghostty-vt as reference/primary; keep interface swappable" | No C++ in current AT modules (only ADB Engine has C++) | NDK build integration test; libghostty-vt Android compatibility verification | YES (implementation approach) |
| OQ-EM-03 | ScreenState internal data structure | V3: "pure data structure" but no fields defined | 4A.1: "grid of (char,attrs) + scrollback + cursor + modes" | ATSessionEvent only carries raw bytes | Design decision: 2D array? line-based? what attrs? | YES (core design) |
| OQ-EM-04 | Where does Emulator sit in module graph? | V3: "属于 AT Engine" but V4 split into api/spi/impl | 4A.7: "independent C++ class/library" | :at-engine is Kotlin; no C++ currently | Decision: new module? JNI in :at-engine? separate :at-emulator? | YES (physical placement) |
| OQ-EM-05 | Public API for screen observation | PRE-EMULATOR: "Emulator API / ScreenState API = NOT FROZEN" | 4A.7: "Agent can read screen grid" | ATTerminalSession has no screen methods | Contract-1 design (AFTER implementation) | NO (after impl) |
| OQ-EM-06 | Scrollback size / memory bounds | 4A.2: "bounded by scrollback limit" | 4A.2: "memory bounded by scrollback limit (not unbounded)" | No scrollback in current code | Configurable? fixed? default size? | NO (implementation detail) |
| OQ-EM-07 | Unicode handling scope | Named in all layers but no scope | 4A.1: "Unicode handling" in Emulator; 4A.3: Termux handles UTF-8 | No Unicode handling in current code | Width? combining? emoji? CJK? | NO (implementation detail) |

**NOT open questions (already answered):**
- Emulator layer → A (frozen: AT Engine)
- Renderer layer → A (frozen: Product/Human UI)
- Who owns Emulator → A (frozen: AT Engine / TerminalSession)
- PTY vs Emulator → A (frozen: different concepts)
- One-shot needs Emulator → A (frozen: "通常不需要")
- Raw preview fate → A (frozen: coexists, NOT deleted)
- Contract-1 timing → A (frozen: AFTER Emulator)

---

## 18. Round-2 Input Set (AT.EMULATOR-GAP-RESEARCH-0)

Only STILL_OPEN + EVIDENCE_GAP items:

```
1. OQ-EM-01: VT/ANSI compatibility scope
   - What minimum escape sequence set for v0.5.0?
   - xterm-256color? Full xterm? VT220 + common extensions?
   - Evidence needed: what do the target use cases (sh, vim, top, less, htop) actually require?

2. OQ-EM-02: Implementation approach (self-written vs libghostty-vt vs other)
   - Is libghostty-vt Android/NDK-compatible in practice?
   - What is the maintenance/licensing posture?
   - If self-written: what scope is "minimal correct"?
   - Evidence needed: build test, API surface review, license check

3. OQ-EM-03: ScreenState data structure design
   - 2D grid vs line-based vs cell-based?
   - What attributes? (color, bold, underline, reverse, ...)
   - Cursor: position only? shape? visibility?
   - Evidence needed: what do consumers (Android Renderer, Agent observer) actually need?

4. OQ-EM-04: Module placement in physical graph
   - New :at-emulator module? Or inside :at-engine?
   - If C++: JNI bridge location?
   - Impact on architecture-gate rules?
   - Evidence needed: dependency analysis, build impact
```

---

## 19. Final Verdict

```
AT.EMULATOR-HISTORICAL-RECONCILIATION-0 FINAL REPORT

VERDICT = PASS

REPO = D:\codebank\AndCode\AndroidTerminal-V4-LOCAL-TCP-042
BRANCH = feature/local-tcp-v0.4.2
HEAD = 64384ab
WORKTREE = CLEAN

HISTORICAL_SOURCES_REVIEWED = YES (9 documents across 5 stages)
CURRENT_SOURCE_AUDITED = YES

SESSION_RUNTIME_RECONCILED = YES (PARTIALLY_ABSORBED into ATTerminalSession)
EMULATOR_TERMINOLOGY_RECONCILED = YES (6 terms tracked)

V3_CANONICAL_STILL_AUTHORITATIVE = YES
V4_SEMANTIC_DELTA_FOUND = NO
V4_PHYSICAL_BOUNDARY_CONFIRMED = YES

HISTORICALLY_FROZEN_COUNT = 15
SUPERSEDED_COUNT = 4
IMPLEMENTED_VERIFIED_COUNT = 2 (raw preview, byte-stream session)
DESIGNED_NOT_IMPLEMENTED_COUNT = 3 (Emulator, ScreenState, full Session Runtime)
STILL_OPEN_COUNT = 7 (OQ-EM-01 through OQ-EM-07)
DEFERRED_COUNT = 6 (H4, H5-interactive, H11-full, H12, H13, H14)
EVIDENCE_GAP_COUNT = 0

AT_EMULATOR_0_ALREADY_FULLY_SPECIFIED_BY_HISTORY = NO
  (4 critical open questions remain: VT scope, implementation approach,
   ScreenState structure, module placement)

ROUND2_REQUIRED = YES
ROUND2_INPUTS =
  - OQ-EM-01: VT/ANSI compatibility scope
  - OQ-EM-02: Implementation approach (libghostty-vt vs self-written)
  - OQ-EM-03: ScreenState data structure design
  - OQ-EM-04: Module placement in physical graph

PRODUCTION_SOURCE_CHANGED = NO
BUILD_FILES_CHANGED = NO
MODULE_GRAPH_CHANGED = NO
API_CHANGED = NO
SPI_CHANGED = NO

COMMIT = NO
PUSH = NO
TAG = NO
RELEASE = NO

NEXT = AT.EMULATOR-GAP-RESEARCH-0
STOP = YES
```
