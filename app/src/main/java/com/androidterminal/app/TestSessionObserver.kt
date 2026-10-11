// AndroidTerminal V3 — Debug-only Test Session Observer.
//
// AT.DEVAPP-0.1. A minimal, debug-only adapter that lets an automated test
// drive its OWN independent TerminalSession and have the real input, real PTY
// output, and session state surfaced to the AndroidTerminal MainActivity UI.
//
// This is NOT:
//   - a Session Registry / Bus / Multi-client Manager
//   - a shared session (Human and Test sessions stay independent)
//   - an Agent/provider/remote control
//
// It IS the single output consumer for the Test Session: the test forwards
// every ATSessionEvent here, and this observer appends tagged lines for the
// UI to render. This preserves the read()/listener mutual-exclusion rule:
// the Test Session uses ONE consumer (setListener -> observer -> UI + capture).

package com.androidterminal.app

import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState

/**
 * Renders one Test Session's activity as tagged, UI-friendly lines.
 *
 * The test calls [onTestInput] when it sends bytes and [onEvent] for every
 * session event (real PTY output arrives as [ATSessionEvent.Data]). The lines
 * are delivered to [uiSink] (which the MainActivity renders), and also
 * accumulated in [log] for assertions / evidence.
 */
class TestSessionObserver(
    private val uiSink: (String) -> Unit,
) {
    /** Human-readable, tagged log of everything this observer saw. */
    val log: StringBuilder = StringBuilder()

    /** Mark the test started. */
    fun onTestStart(name: String) {
        line("[TEST] START $name")
    }

    /** Record bytes the test sent to the session. */
    fun onTestInput(bytes: ByteArray) {
        line("[TEST INPUT] " + bytes.decodeToString())
    }

    /** Record every session event (Data = real PTY output). */
    fun onEvent(ev: ATSessionEvent) {
        when (ev) {
            is ATSessionEvent.Data -> {
                // Real PTY bytes (merged stdout/stderr). Decoded for display;
                // the actual bytes sent to/from the session are unchanged.
                line("[PTY OUTPUT] " + String(ev.bytes))
            }
            is ATSessionEvent.Resized -> line("[STATE] RESIZED ${ev.rows}x${ev.cols}")
            is ATSessionEvent.Exited -> line("[STATE] EXITED ${ev.exitCode}")
            is ATSessionEvent.Eof -> line("[STATE] EOF")
            is ATSessionEvent.Failed -> line("[STATE] FAILED ${ev.error}")
            is ATSessionEvent.Closed -> line("[STATE] CLOSED")
        }
    }

    /** Record a session state snapshot. */
    fun onState(state: ATSessionState) {
        line("[STATE] $state")
    }

    /** Mark the test ended. */
    fun onTestEnd(result: String) {
        line("[TEST] END $result")
    }

    private fun line(s: String) {
        log.append(s).append('\n')
        uiSink(s + "\n")
    }
}