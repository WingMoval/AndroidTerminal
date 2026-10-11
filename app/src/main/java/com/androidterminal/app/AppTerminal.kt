// AndroidTerminal V3 — App-side terminal composition helper.
//
// AT.CONTRACT-0.5. Consumer-side composition root for the Dev App: builds the
// Local backend and the AT Engine, then exposes ONLY the AT Engine boundary
// (ATEngine / ATTerminalSession / ATSessionEvent / ATSessionState).
//
// This is NOT a new architecture layer and NOT an Agent/control server. It is
// the single composition point shared by the Human UI (MainActivity) and the
// debug-only instrumentation (AppDebugInputTest), so both drive the SAME
// consumer path:  AT Engine → Local Backend → Local PTY → /system/bin/sh.
//
// It never touches LocalPtyNative / LocalPtySession / JNI / fd / pid.

package com.androidterminal.app

import com.at.backend.local.LocalPersistentBackend
import com.at.engine.ATEngine
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession

/**
 * App-side terminal composition root.
 *
 * @param eventListener receives session output/state events (UI shows them;
 *        debug tests assert them). Implementation shape PROVISIONAL.
 */
class AppTerminal(
    private val eventListener: (ATSessionEvent) -> Unit,
) {
    private val engine: ATEngine = ATEngine(
        oneShot = null, // Dev preview / debug is persistent-focused (not one-shot)
        persistent = LocalPersistentBackend(),
    )

    /** Current session, if any. */
    var session: ATTerminalSession? = null
        private set

    /** Open a new Local persistent session (default launch = /system/bin/sh). */
    fun open(rows: Int = 24, cols: Int = 80, command: String = "/system/bin/sh"): Boolean {
        close()
        val r = engine.openSession(
            ATPersistentBackend.Launch(
                command = command,
                initialRows = rows,
                initialCols = cols,
            ),
        )
        return when (r) {
            is ATOpenSessionResult.Success -> {
                session = r.session
                r.session.setListener { ev -> eventListener(ev) }
                true
            }
            is ATOpenSessionResult.Failure -> {
                session = null
                false
            }
        }
    }

    fun state(): ATSessionState = session?.state ?: ATSessionState.CLOSED

    /** Write raw bytes to the persistent session. Returns bytes accepted or -1. */
    fun write(bytes: ByteArray): Int =
        session?.write(bytes) ?: -1

    /** Write a string without an auto newline (byte-stream semantics). */
    fun writeText(text: String): Int = write(text.toByteArray())

    /** Append newline to the session (explicit, byte-stream semantics). */
    fun newline(): Int = write("\n".toByteArray())

    /** Send Ctrl-C (0x03) to the foreground process. */
    fun ctrlC(): Int = write(byteArrayOf(0x03))

    fun resize(rows: Int, cols: Int): Boolean = session?.resize(rows, cols) ?: false

    fun close() {
        runCatching { session?.close() }
        session = null
    }
}