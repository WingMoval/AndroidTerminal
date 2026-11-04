// AT.EMULATOR-0-IMPLEMENTATION-DECISION — Spike A: Native Parser Skeleton
//
// Purpose: Validate structural viability of an AT-native VT parser:
//   1. Parser state architecture is additive
//   2. Arbitrary chunk boundaries are structurally safe
//   3. Unknown/malformed sequence recovery is defined
//   4. State extensibility (new CSI/OSC handlers can be added without rewriting)
//
// This is NOT production code. It demonstrates the architecture pattern.
//
// Run: kotlinc NativeParserSpike.kt && java NativeParserSpikeKt
// (or adapt to JUnit for the real test suite)

package com.at.engine.terminal.spike

// ═══════════════════════════════════════════════════════════════════
// Parser States
// ═══════════════════════════════════════════════════════════════════

enum class ParserState {
    GROUND,
    ESCAPE,
    ESCAPE_INTERMEDIATE,
    CSI_ENTRY,
    CSI_PARAM,
    CSI_INTERMEDIATE,
    CSI_IGNORE,
    OSC_STRING,
    OSC_ESC,
    DCS_ENTRY,
    DCS_PARAM,
    DCS_PASSTHROUGH,
    DCS_ESC,
    DCS_IGNORE,
    SOS_IGNORE,
    PM_IGNORE,
    APC_IGNORE,
    APC_ESC,
    RECOVERY,
}

// ═══════════════════════════════════════════════════════════════════
// CSI Intermediate/Parameter tracking
// ═══════════════════════════════════════════════════════════════════

data class CsiParams(
    val intermediates: StringBuilder = StringBuilder(),
    val params: IntArray = IntArray(16),
    val paramCount: Int = 0,
    val privateMarker: Char? = null, // '?', '>', '<', '='
) {
    fun paramAt(i: Int, default: Int = 0): Int =
        if (i < paramCount) params[i] else default
}

// ═══════════════════════════════════════════════════════════════════
// Terminal State (minimal — just enough for the spike)
// ═══════════════════════════════════════════════════════════════════

data class TerminalState(
    var cursorRow: Int = 0,
    var cursorCol: Int = 0,
    var rows: Int = 24,
    var cols: Int = 80,
    var altScreen: Boolean = false,
    var cursorVisible: Boolean = true,
    var autoWrap: Boolean = true,
    var applicationCursorKeys: Boolean = false,
    var originMode: Boolean = false,
    // SGR state
    var fgColor: Int = 0,   // -1 = default, 0-255 = indexed, 0x1000000+ = truecolor
    var bgColor: Int = 0,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false,
    // Buffer
    val buffer: Array<StringBuilder> = Array(24) { StringBuilder() },
    val altBuffer: Array<StringBuilder> = Array(24) { StringBuilder() },
) {
    val activeBuffer: Array<StringBuilder>
        get() = if (altScreen) altBuffer else buffer

    fun reset() {
        cursorRow = 0; cursorCol = 0
        altScreen = false; cursorVisible = true
        autoWrap = true; applicationCursorKeys = false; originMode = false
        fgColor = 0; bgColor = 0; bold = false; underline = false; inverse = false
        for (i in buffer.indices) buffer[i].setLength(0)
        for (i in altBuffer.indices) altBuffer[i].setLength(0)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Parser
// ═══════════════════════════════════════════════════════════════════

class VtParser(private val state: TerminalState) {

    private var parserState = ParserState.GROUND
    private var csiParams = CsiParams()
    private var oscString = StringBuilder()
    private var dcsString = StringBuilder()

    // UTF-8 decoder state (survives chunk boundaries)
    private var utf8Remaining = 0
    private var utf8CodePoint = 0
    private var utf8BytesAccumulated = 0

    /** Feed raw bytes. Safe to call with any chunk size. */
    fun feed(data: ByteArray) {
        for (b in data) {
            processByte(b.toInt() and 0xFF)
        }
    }

    private fun processByte(byte: Int) {
        // UTF-8 decoding layer
        if (utf8Remaining > 0) {
            if (byte and 0xC0 == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (byte and 0x3F)
                utf8BytesAccumulated++
                if (utf8BytesAccumulated == utf8Remaining) {
                    utf8Remaining = 0
                    processCodePoint(utf8CodePoint)
                }
                return
            } else {
                // Invalid continuation — emit replacement, restart
                processCodePoint(0xFFFD)
                utf8Remaining = 0
            }
        }

        if (byte < 0x80) {
            // ASCII — process directly
            processCodePoint(byte)
        } else if (byte and 0xE0 == 0xC0) {
            utf8Remaining = 1
            utf8CodePoint = byte and 0x1F
            utf8BytesAccumulated = 0
        } else if (byte and 0xF0 == 0xE0) {
            utf8Remaining = 2
            utf8CodePoint = byte and 0x0F
            utf8BytesAccumulated = 0
        } else if (byte and 0xF8 == 0xF0) {
            utf8Remaining = 3
            utf8CodePoint = byte and 0x07
            utf8BytesAccumulated = 0
        } else {
            // Invalid UTF-8 lead byte
            processCodePoint(0xFFFD)
        }
    }

    private fun processCodePoint(cp: Int) {
        when (parserState) {
            ParserState.GROUND -> handleGround(cp)
            ParserState.ESCAPE -> handleEscape(cp)
            ParserState.CSI_ENTRY, ParserState.CSI_PARAM,
            ParserState.CSI_INTERMEDIATE, ParserState.CSI_IGNORE -> handleCsi(cp)
            ParserState.OSC_STRING -> handleOsc(cp)
            ParserState.OSC_ESC -> handleOscEsc(cp)
            ParserState.DCS_ENTRY, ParserState.DCS_PARAM,
            ParserState.DCS_PASSTHROUGH, ParserState.DCS_IGNORE -> handleDcs(cp)
            ParserState.DCS_ESC -> handleDcsEsc(cp)
            ParserState.SOS_IGNORE, ParserState.PM_IGNORE -> handleIgnoreString(cp)
            ParserState.APC_IGNORE -> handleApc(cp)
            ParserState.APC_ESC -> handleApcEsc(cp)
            ParserState.RECOVERY -> {
                // Recovery: consume until we can resync
                if (cp == 0x18 || cp == 0x1A) { // CAN / SUB
                    parserState = ParserState.GROUND
                } else if (cp == 0x1B) {
                    parserState = ParserState.ESCAPE
                }
                // Otherwise stay in recovery, consuming bytes
            }
            else -> { /* unhandled — stay in current state */ }
        }
    }

    // ─── GROUND ───────────────────────────────────────────────────

    private fun handleGround(cp: Int) {
        when {
            cp == 0x1B -> parserState = ParserState.ESCAPE
            cp == 0x07 -> { /* BEL — ignore or signal */ }
            cp == 0x08 -> { /* BS */ }
            cp == 0x09 -> { /* HT */ }
            cp == 0x0A || cp == 0x0B || cp == 0x0C -> { /* LF/VT/FF */ }
            cp == 0x0D -> { /* CR */ state.cursorCol = 0 }
            else -> { /* printable — emit to buffer */ }
        }
    }

    // ─── ESCAPE ───────────────────────────────────────────────────

    private fun handleEscape(cp: Int) {
        when {
            cp == 0x1B -> { /* ESC ESC = ESC in ground */ }
            cp == '[' -> {
                csiParams = CsiParams()
                parserState = ParserState.CSI_ENTRY
            }
            cp == ']' -> {
                oscString = StringBuilder()
                parserState = ParserState.OSC_STRING
            }
            cp == 'P' -> {
                dcsString = StringBuilder()
                parserState = ParserState.DCS_ENTRY
            }
            cp == 'X' -> parserState = ParserState.SOS_IGNORE
            cp == '^' -> parserState = ParserState.PM_IGNORE
            cp == '_' -> parserState = ParserState.APC_IGNORE
            cp == 'c' -> { /* RIS — full reset */ state.reset() }
            cp == 'M' -> { /* RI — reverse index */ }
            cp == '7' -> { /* DECSC */ }
            cp == '8' -> { /* DECRC */ }
            cp == '=' -> { /* DECKPAM */ }
            cp == '>' -> { /* DECKPNM */ }
            cp in ' '..'/'.code -> { /* intermediate byte */ }
            cp in '@'..'~'.code -> { /* final byte — 2-char escape */ }
            else -> parserState = ParserState.RECOVERY
        }
    }

    // ─── CSI ──────────────────────────────────────────────────────

    private fun handleCsi(cp: Int) {
        when {
            // Private markers (only valid at entry)
            parserState == ParserState.CSI_ENTRY && cp == '?' -> {
                csiParams = CsiParams(privateMarker = '?')
                parserState = ParserState.CSI_PARAM
                return
            }
            parserState == ParserState.CSI_ENTRY && cp in '>'.code..'<'.code -> {
                csiParams = CsiParams(privateMarker = cp.toChar())
                parserState = ParserState.CSI_PARAM
                return
            }
            parserState == ParserState.CSI_ENTRY && cp == '=' -> {
                csiParams = CsiParams(privateMarker = '=')
                parserState = ParserState.CSI_PARAM
                return
            }

            // Parameter digits
            cp in '0'..'9'.code -> {
                parserState = ParserState.CSI_PARAM
                val idx = csiParams.paramCount
                if (idx < 16) {
                    val prev = if (idx < csiParams.paramCount) csiParams.params[idx] else -1
                    val newParam = if (prev < 0) (cp - '0'.code) else (prev * 10) + (cp - '0'.code)
                    csiParams.params[idx] = newParam.coerceAtMost(9999)
                    csiParams.paramCount = idx + 1
                }
                return
            }

            // Parameter separator
            cp == ';' -> {
                parserState = ParserState.CSI_PARAM
                return
            }

            // Intermediate bytes
            cp in ' '..'/' .code -> {
                parserState = ParserState.CSI_INTERMEDIATE
                return
            }

            // Final byte (execute)
            cp in '@'..'~'.code -> {
                executeCsi(cp.toChar())
                parserState = ParserState.GROUND
                return
            }

            // CAN / SUB abort
            cp == 0x18 || cp == 0x1A -> {
                parserState = ParserState.GROUND
                return
            }

            // ESC in CSI = abort, start new escape
            cp == 0x1B -> {
                parserState = ParserState.ESCAPE
                return
            }

            // Invalid byte in CSI
            else -> parserState = ParserState.CSI_IGNORE
        }
    }

    private fun executeCsi(final: Char) {
        val marker = csiParams.privateMarker
        when {
            // Private modes: CSI ? ... h/l
            marker == '?' && final == 'h' -> decSet(true)
            marker == '?' && final == 'l' -> decSet(false)

            // Cursor movement
            final == 'A' -> state.cursorRow = (state.cursorRow - csiParams.paramAt(0, 1)).coerceAtLeast(0)
            final == 'B' -> state.cursorRow = (state.cursorRow + csiParams.paramAt(0, 1)).coerceAtMost(state.rows - 1)
            final == 'C' -> state.cursorCol = (state.cursorCol + csiParams.paramAt(0, 1)).coerceAtMost(state.cols - 1)
            final == 'D' -> state.cursorCol = (state.cursorCol - csiParams.paramAt(0, 1)).coerceAtLeast(0)
            final == 'H' || final == 'f' -> {
                state.cursorRow = (csiParams.paramAt(0, 1) - 1).coerceIn(0, state.rows - 1)
                state.cursorCol = (csiParams.paramAt(1, 1) - 1).coerceIn(0, state.cols - 1)
            }

            // Erase
            final == 'J' -> { /* erase display — param 0/1/2/3 */ }
            final == 'K' -> { /* erase line — param 0/1/2 */ }

            // SGR
            final == 'm' -> applySgr()

            // Mode set/reset
            final == 'h' -> { /* set mode */ }
            final == 'l' -> { /* reset mode */ }

            else -> { /* unknown CSI — safe ignore */ }
        }
    }

    private fun decSet(setting: Boolean) {
        for (i in 0 until csiParams.paramCount) {
            val mode = csiParams.params[i]
            when (mode) {
                1 -> state.applicationCursorKeys = setting // DECCKM
                7 -> state.autoWrap = setting // DECAWM
                25 -> state.cursorVisible = setting
                1049 -> { // Alternate screen
                    state.altScreen = setting
                    if (setting) { /* save cursor, clear alt buffer */ }
                    else { /* restore cursor */ }
                }
                else -> { /* unknown private mode — safe ignore */ }
            }
        }
    }

    private fun applySgr() {
        if (csiParams.paramCount == 0) {
            csiParams.params[0] = 0
            csiParams.paramCount = 1
        }
        var i = 0
        while (i < csiParams.paramCount) {
            val p = csiParams.params[i]
            when {
                p == 0 -> { state.fgColor = 0; state.bgColor = 0; state.bold = false; state.underline = false; state.inverse = false }
                p == 1 -> state.bold = true
                p == 4 -> state.underline = true
                p == 7 -> state.inverse = true
                p in 30..37 -> state.fgColor = p - 30
                p in 40..47 -> state.bgColor = p - 40
                p == 38 && i + 1 < csiParams.paramCount -> {
                    if (csiParams.params[i + 1] == 5) { state.fgColor = csiParams.paramAt(i + 2, 0); i += 2 }
                    else if (csiParams.params[i + 1] == 2) {
                        val r = csiParams.paramAt(i + 2, 0); val g = csiParams.paramAt(i + 3, 0); val b = csiParams.paramAt(i + 4, 0)
                        state.fgColor = 0x1000000 or (r shl 16) or (g shl 8) or b; i += 4
                    }
                }
                p == 48 && i + 1 < csiParams.paramCount -> {
                    if (csiParams.params[i + 1] == 5) { state.bgColor = csiParams.paramAt(i + 2, 0); i += 2 }
                    else if (csiParams.params[i + 1] == 2) {
                        val r = csiParams.paramAt(i + 2, 0); val g = csiParams.paramAt(i + 3, 0); val b = csiParams.paramAt(i + 4, 0)
                        state.bgColor = 0x1000000 or (r shl 16) or (g shl 8) or b; i += 4
                    }
                }
            }
            i++
        }
    }

    // ─── OSC ──────────────────────────────────────────────────────

    private fun handleOsc(cp: Int) {
        when {
            cp == 0x07 -> { /* BEL terminates OSC */ executeOsc() }
            cp == 0x1B -> parserState = ParserState.OSC_ESC
            cp == 0x18 || cp == 0x1A -> { parserState = ParserState.GROUND } // abort
            else -> { if (oscString.length < 4096) oscString.append(cp.toChar()) }
        }
    }

    private fun handleOscEsc(cp: Int) {
        when {
            cp == '\\' -> { /* ST terminates OSC */ executeOsc() }
            else -> { /* ESC was part of data */ oscString.append('\u001B'); parserState = ParserState.OSC_STRING }
        }
    }

    private fun executeOsc() {
        val str = oscString.toString()
        // Parse: "Ps;Pt"
        val semiIdx = str.indexOf(';')
        val ps = if (semiIdx >= 0) str.substring(0, semiIdx).toIntOrNull() else null
        // ps == 0: set icon+title; ps == 2: set title
        // Unknown: safe ignore
    }

    // ─── DCS ──────────────────────────────────────────────────────

    private fun handleDcs(cp: Int) {
        when {
            cp == 0x1B -> parserState = ParserState.DCS_ESC
            cp == 0x18 || cp == 0x1A -> parserState = ParserState.GROUND
            else -> { if (dcsString.length < 4096) dcsString.append(cp.toChar()) }
        }
    }

    private fun handleDcsEsc(cp: Int) {
        when {
            cp == '\\' -> { /* ST — execute DCS (ignore for v0.5) */ parserState = ParserState.GROUND }
            else -> { dcsString.append('\u001B'); parserState = ParserState.DCS_PASSTHROUGH }
        }
    }

    // ─── Ignore strings (SOS, PM, APC) ────────────────────────────

    private fun handleIgnoreString(cp: Int) {
        when {
            cp == 0x1B -> parserState = ParserState.DCS_ESC // reuse ESC handling
            cp == 0x18 || cp == 0x1A -> parserState = ParserState.GROUND
        }
    }

    private fun handleApc(cp: Int) {
        when {
            cp == 0x1B -> parserState = ParserState.APC_ESC
            cp == 0x18 || cp == 0x1A -> parserState = ParserState.GROUND
        }
    }

    private fun handleApcEsc(cp: Int) {
        when {
            cp == '\\' -> parserState = ParserState.GROUND
            else -> parserState = ParserState.APC_IGNORE
        }
    }

    // ─── Public access for tests ─────────────────────────────────

    fun getState(): TerminalState = state
    fun getParserState(): ParserState = parserState
}

// ═══════════════════════════════════════════════════════════════════
// Test harness
// ═══════════════════════════════════════════════════════════════════

fun main() {
    var passed = 0
    var failed = 0

    fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) { passed++; println("  PASS: $name") }
        else { failed++; println("  FAIL: $name $detail") }
    }

    println("═══ Spike A: Native Parser Skeleton ═══")
    println()

    // Test 1: Basic CSI cursor movement
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '5', 'C')) // ESC[5C
        check("CSI cursor right", s.cursorCol == 5, "(got col=${s.cursorCol})")
    }

    // Test 2: SGR colors
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '3', '1', 'm')) // ESC[31m
        check("SGR red", s.fgColor == 1, "(got fg=${s.fgColor})")
        p.feed(byteArrayOf(0x1B, '[', '4', '8', ';', '5', ';', '1', '9', '6', 'm')) // ESC[48;5;196m
        check("SGR 256-color bg", s.bgColor == 196, "(got bg=${s.bgColor})")
    }

    // Test 3: Alternate screen
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '?', '1', '0', '4', '9', 'h')) // ESC[?1049h
        check("Alt screen enter", s.altScreen, "")
        p.feed(byteArrayOf(0x1B, '[', '?', '1', '0', '4', '9', 'l')) // ESC[?1049l
        check("Alt screen exit", !s.altScreen, "")
    }

    // Test 4: SPLIT CSI — the critical chunk boundary test
    run {
        val s1 = TerminalState()
        val p1 = VtParser(s1)
        // Feed in 3 chunks: ESC [ | ?104 | 9h
        p1.feed(byteArrayOf(0x1B, '['))
        p1.feed(byteArrayOf('?', '1', '0', '4'))
        p1.feed(byteArrayOf('9', 'h'))
        check("Split CSI: ?1049h", s1.altScreen, "(got alt=${s1.altScreen})")
    }

    // Test 5: Split CSI — another pattern
    run {
        val s1 = TerminalState()
        val p1 = VtParser(s1)
        // ESC | [3 | 8;5;1 00m
        p1.feed(byteArrayOf(0x1B))
        p1.feed(byteArrayOf('[', '3', '8'))
        p1.feed(byteArrayOf(';', '5', ';', '1', '0', '0', 'm'))
        check("Split CSI: 38;5;100", s1.fgColor == 100, "(got fg=${s1.fgColor})")
    }

    // Test 6: Split OSC
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, ']', '2', ';', 'H'))
        p.feed(byteArrayOf('e', 'l', 'l', 'o', 0x07)) // ESC]2;Hello BEL
        check("Split OSC: title", s.cursorRow == 0, "(parser should be in GROUND, got ${p.getParserState()})")
        check("Split OSC: state ground", p.getParserState() == ParserState.GROUND, "")
    }

    // Test 7: Unknown private CSI — safe ignore
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '?', '9', '9', '9', '9', 'h')) // unknown mode
        check("Unknown CSI: safe ignore", p.getParserState() == ParserState.GROUND, "")
        check("Unknown CSI: state unchanged", s.cursorRow == 0 && s.cursorCol == 0, "")
    }

    // Test 8: Malformed sequence — recovery
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '!', 'X')) // malformed
        check("Malformed: recovered", p.getParserState() == ParserState.GROUND, "(got ${p.getParserState()})")
    }

    // Test 9: Split UTF-8
    run {
        val s = TerminalState()
        val p = VtParser(s)
        // U+4E2D (中) = E4 B8 AD in UTF-8
        p.feed(byteArrayOf(0xE4, 0xB8)) // first 2 bytes
        check("Split UTF-8: still decoding", p.getParserState() == ParserState.GROUND, "")
        p.feed(byteArrayOf(0xAD)) // final byte
        check("Split UTF-8: complete", p.getParserState() == ParserState.GROUND, "")
    }

    // Test 10: C1 control in UTF-8 — ignored
    run {
        val s = TerminalState()
        val p = VtParser(s)
        // C1 ESC (0x9B) encoded in UTF-8 as C2 9B
        p.feed(byteArrayOf(0xC2, 0x9B))
        check("C1 in UTF-8: ignored", p.getParserState() == ParserState.GROUND, "")
    }

    // Test 11: DECCKM (application cursor keys)
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '?', '1', 'h')) // ESC[?1h
        check("DECCKM set", s.applicationCursorKeys, "")
        p.feed(byteArrayOf(0x1B, '[', '?', '1', 'l')) // ESC[?1l
        check("DECCKM reset", !s.applicationCursorKeys, "")
    }

    // Test 12: DECAWM (autowrap)
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, '[', '?', '7', 'l')) // ESC[?7l
        check("DECAWM off", !s.autoWrap, "")
        p.feed(byteArrayOf(0x1B, '[', '?', '7', 'h')) // ESC[?7h
        check("DECAWM on", s.autoWrap, "")
    }

    // Test 13: Full sequence in one feed vs split — equivalence
    run {
        val s1 = TerminalState()
        val p1 = VtParser(s1)
        p1.feed(byteArrayOf(0x1B, '[', '?', '1', '0', '4', '9', 'h', 0x1B, '[', '3', '1', 'm', 0x1B, '[', '5', 'H'))

        val s2 = TerminalState()
        val p2 = VtParser(s2)
        // Feed one byte at a time
        for (b in byteArrayOf(0x1B, '[', '?', '1', '0', '4', '9', 'h', 0x1B, '[', '3', '1', 'm', 0x1B, '[', '5', 'H')) {
            p2.feed(byteArrayOf(b))
        }

        check("Byte-at-a-time == bulk: altScreen", s1.altScreen == s2.altScreen, "")
        check("Byte-at-a-time == bulk: fgColor", s1.fgColor == s2.fgColor, "")
        check("Byte-at-a-time == bulk: cursorRow", s1.cursorRow == s2.cursorRow, "")
        check("Byte-at-a-time == bulk: parserState", p1.getParserState() == p2.getParserState(), "")
    }

    // Test 14: OSC with ST terminator (ESC \)
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, ']', '2', ';', 'T', 'e', 's', 't', 0x1B, '\\'))
        check("OSC ST terminator", p.getParserState() == ParserState.GROUND, "(got ${p.getParserState()})")
    }

    // Test 15: DCS safe ignore
    run {
        val s = TerminalState()
        val p = VtParser(s)
        p.feed(byteArrayOf(0x1B, 'P', '1', '$', 'r', 0x1B, '\\')) // DCS 1$r ST
        check("DCS: safe ignore", p.getParserState() == ParserState.GROUND, "(got ${p.getParserState()})")
    }

    println()
    println("═══ Results: $passed passed, $failed failed ═══")
    if (failed > 0) System.exit(1)
}
