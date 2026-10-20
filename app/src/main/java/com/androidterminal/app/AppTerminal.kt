// AndroidTerminal V3 鈥?App-side terminal composition helper.
//
// AT.CONTRACT-0.5. Consumer-side composition root for the Dev App: builds the
// Local backend and the AT Engine, then exposes ONLY the AT Engine boundary
// (ATEngine / ATTerminalSession / ATSessionEvent / ATSessionState).
//
// This is NOT a new architecture layer and NOT an Agent/control server. It is
// the single composition point shared by the Human UI (MainActivity) and the
// debug-only instrumentation (AppDebugInputTest), so both drive the SAME
// consumer path:  AT Engine 鈫?Local Backend 鈫?Local PTY 鈫?/system/bin/sh.
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
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine

/**
 * App-side terminal composition helper.
 *
 * One AppTerminal = one TerminalSession over a SELECTED backend kind. Local and
 * ADB share the SAME ATEngine public surface (openSession / write / resize /
 * close / events). The engine is injected (built by [AppRuntime]) so this class
 * never constructs backends directly for ADB 鈥?it just consumes AT Engine.
 *
 * @param eventListener receives session output/state events (UI shows them;
 *        debug tests assert them). Implementation shape PROVISIONAL.
 * @param kind which backend kind this terminal drives (provider identity).
 * @param engine the ATEngine to use (null = build a Local-only engine).
 */
class AppTerminal(
    private val eventListener: (ATSessionEvent) -> Unit,
    val kind: SessionBackendKind = SessionBackendKind.LOCAL,
    engine: ATEngine? = null,
    /** Optional app-level session id (SessionPage.pageId) for the shared handle. */
    val sessionId: Int = 0,
    /** Optional registry to register the shared handle into (null = no debug bridge). */
    private val registry: AppSessionRegistry? = null,
    /** Optional input-activity callback (for UI annotation of external writes). */
    private val onInputActivity: ((InputSource, ByteArray) -> Unit)? = null,
) {
    private val engine: ATEngine = engine ?: DefaultATEngine(
        oneShot = null, // Dev preview / debug is persistent-focused (not one-shot)
        persistent = LocalPersistentBackend(),
    )

    /** Current session, if any. */
    var session: ATTerminalSession? = null
        private set

    /** Shared handle wrapping the current session (for Human + Debug fan-out). */
    var sharedHandle: SharedSessionHandle? = null
        private set

    /** Human UI observer on the shared handle (output/state/input-activity). */
    private val humanObserver = object : SessionObserver {
        override fun onOutput(bytes: ByteArray) {
            eventListener(ATSessionEvent.Data(bytes))
        }

        override fun onStateChange(state: ATSessionEvent) {
            eventListener(state)
        }

        override fun onInputActivity(source: InputSource, bytes: ByteArray) {
            onInputActivity?.invoke(source, bytes)
        }
    }

    /**
     * Open a new persistent session. Default command: Local -> /system/bin/sh;
     * ADB -> "" (AdbPersistentBackend opens `shell,v2,pty:` regardless).
     *
     * When a registry is provided, the session is wrapped in a SharedSessionHandle
     * (single listener, fan-out to Human + Debug) and registered. Writes then go
     * through the handle's serialized path.
     */
    fun open(rows: Int = 24, cols: Int = 80, command: String = defaultCommand()): Boolean {
        close()
        val r = engine.openSession(
            ATSessionLaunch(
                command = command,
                initialRows = rows,
                initialCols = cols,
            ),
        )
        return when (r) {
            is ATOpenSessionResult.Success -> {
                session = r.session
                val reg = registry
                if (reg != null && sessionId != 0) {
                    val handle = SharedSessionHandle(sessionId, kind, r.session)
                    handle.addObserver(humanObserver)
                    handle.attachSessionListener() // single listener = the handle
                    reg.register(handle)
                    sharedHandle = handle
                } else {
                    // No debug bridge: direct listener (legacy path).
                    r.session.setListener { ev -> eventListener(ev) }
                }
                true
            }
            is ATOpenSessionResult.Failure -> {
                session = null
                false
            }
        }
    }

    private fun defaultCommand(): String =
        if (kind == SessionBackendKind.ADB) "" else "/system/bin/sh"

    fun state(): ATSessionState = session?.state ?: ATSessionState.CLOSED

    /**
     * Write raw bytes (Human source). Goes through the shared handle's
     * serialized path when present (so Human + Debug never byte-interleave);
     * otherwise direct to the session.
     */
    fun write(bytes: ByteArray): Int {
        val h = sharedHandle
        if (h != null) {
            h.write(InputSource.HUMAN, bytes)
            return bytes.size
        }
        return session?.write(bytes) ?: -1
    }

    /** Write a string without an auto newline (byte-stream semantics). */
    fun writeText(text: String): Int = write(text.toByteArray())

    /** Append newline to the session (explicit, byte-stream semantics). */
    fun newline(): Int = write("\n".toByteArray())

    /** Send Ctrl-C (0x03) to the foreground process. */
    fun ctrlC(): Int = write(byteArrayOf(0x03))

    fun resize(rows: Int, cols: Int): Boolean {
        val h = sharedHandle
        val ok = if (h != null) h.resize(rows, cols)
        else runCatching { session?.resize(rows, cols) }.getOrNull() ?: false
        // AT.CLI-0.2: if this Local session is mid-`adb shell` interactive,
        // relay the new size to the inner Remote PTY (搂20/搂19). App/CLI
        // composition only; the AT Engine never learns of the nesting.
        if (sessionId != 0) {
            runCatching { com.androidterminal.app.AppRuntime.relayInteractiveResize(sessionId, rows, cols) }
        }
        return ok
    }

    fun close() {
        val h = sharedHandle
        if (h != null) {
            // Close the session but KEEP the handle in the registry (closed state)
            // so a Debug write after Human-close gets SESSION_CLOSED (rejected),
            // not "no such session". Unregister happens on Activity destroy.
            h.close()
            sharedHandle = null
        } else {
            runCatching { session?.close() }
        }
        session = null
    }
}



