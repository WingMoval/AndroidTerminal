// AT.CLI-CONSUMER-0 — Reusable CLI Bridge server (same-UID UDS, host-agnostic).
//
// Thin Kotlin wrapper over a native UDS server (cli_bridge_server.cpp) that
// binds in the host's private files dir, verifies peer UID == host UID
// (SO_PEERCRED), and speaks the length-prefixed CLI Bridge protocol v1.
//
// REUSABLE: the [ATCliBridgeHandler] delegates to a host-provided
// [ATCliExecution] — NOT to any AndroidTerminal :app concrete class. The host
// (product app / test consumer host / future AG adapter host) wires its OWN
// shared ATEngine + AdbRuntime into the bridge. There is NEVER a second
// AdbRuntime / AT Engine / identity: the bridge is a thin frontend over the
// host's existing execution subsystem.
//
//   one-shot    -> execution.adbEngine().execute(...)    (AdbOneShot path)
//   interactive -> execution.adbEngine().openSession(...) (AdbPersistent path)
//
// Lifecycle status (checking / connected / user-action-required) is surfaced
// through the host's [ATCliExecution.ensureReady] onStatusLine callback:
//   - :app → Host/SYSTEM terminal projection to the bound Local session.
//   - consumer host → T_STATUS fallback (buffered to the CLI client stderr).
//
// Present in BOTH debug and release builds (NOT gated by BuildConfig.DEBUG).
// Independent from any AT.DEBUG-0 DebugBridge (no port/protocol reuse).

package com.at.cli

import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Reusable same-UID UDS CLI bridge server.
 *
 * @param filesDir the host's app-private files dir (the UDS binds here).
 * @param execution the host-provided [ATCliExecution] (shared AT / AdbRuntime
 *        composition the bridge routes CLI commands into).
 */
class AdbCliBridgeServer(
    private val filesDir: File,
    private val execution: ATCliExecution,
) {

    companion object {
        private const val TAG = "AdbCliBridge"
    }

    init {
        runCatching { System.loadLibrary("atcli_env") }
    }

    /** The UDS path (host app-private files dir). */
    val socketPath: String = File(filesDir, "cli_bridge.sock").absolutePath

    private val handler = ATCliBridgeHandler(execution)
    private var listenFd = -1
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "at-cli-bridge").apply { isDaemon = true }
    }

    @Volatile private var started = false

    fun isRunning() = started

    external private fun nativeStart(path: String, handler: ATCliBridgeHandler): Int
    external private fun nativeStop()

    /** Start the UDS server (idempotent). */
    fun start(): Boolean {
        if (started) return true
        val fd = try {
            nativeStart(socketPath, handler)
        } catch (e: Exception) {
            -1
        }
        if (fd < 0) return false
        listenFd = fd
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { nativeStop() }
        runCatching { handler.shutdown() }
    }

    // ---- Kotlin handler (called from native bridge threads) ----------------

    private inner class ATCliBridgeHandler(private val execution: ATCliExecution) {

        // CLI-owned interactive sessions, keyed by client fd.
        private val sessions = ConcurrentHashMap<Int, com.at.engine.ATTerminalSession>()
        // fd -> Local session id (from HELLO payload) for host status binding.
        private val fdSession = ConcurrentHashMap<Int, Int>()
        // Local session id -> active interactive client fd (the session
        // currently hosting an `adb shell` remote PTY). Used by the host-layer
        // resize relay. A CLI-connection composition map, NOT a second ADB
        // runtime / session registry / recovery owner.
        private val interactiveForSession = ConcurrentHashMap<Int, Int>()
        // fd -> whether command-time status was already emitted (fast-path dedupe).
        private val statusEmittedForFd = ConcurrentHashMap<Int, Boolean>()

        /** Auth: same-UID only (the CLI runs as the host UID). */
        fun onClientAuth(fd: Int, peerUid: Int): Boolean = peerUid == Process.myUid()

        /**
         * Called by the native server after HELLO/ACK with the Local session id
         * carried in the HELLO payload (0 = none). Records the fd->session
         * binding the host may use for status projection.
         */
        fun onHello(fd: Int, sessionId: Int) {
            if (sessionId > 0) fdSession[fd] = sessionId
        }

        /**
         * Runtime-owned ensureReady (delegated to the host's [ATCliExecution]).
         * The host emits intermediate status lines via onStatusLine; this bridge
         * forwards each line to the bound client as a T_STATUS frame (the
         * non-Human / short-lived CLI client path). The host's ensureReady is
         * the SINGLE readiness owner — no CLI-owned polling loop.
         */
        private fun ensureReady(fd: Int): Boolean {
            val sid = fdSession[fd] ?: 0
            val ok = execution.ensureReady(sid) { line ->
                // The host decides the final surface (live projection for a
                // bound Human session); the bridge's fallback is T_STATUS
                // (buffered to the CLI client's stderr until EXIT) for
                // non-Human / unbound clients.
                sendStatus(fd, "[adb] $line")
            }
            if (ok) statusEmittedForFd[fd] = true
            return ok
        }

        private fun sendStatus(fd: Int, text: String) {
            sendFrame(fd, AdbCliProtocol.T_STATUS, text.toByteArray(Charsets.UTF_8))
        }

        /** One-shot: execute and push OUT/ERR/(ERROR)/EXIT frames. */
        fun onOneShot(fd: Int, cmdBytes: ByteArray) {
            val cmd = String(cmdBytes, Charsets.UTF_8)
            android.util.Log.i(TAG, "onOneShot: ensureReady for [$cmd]")
            val ok = ensureReady(fd)
            if (!ok) {
                // Not READY (e.g. WD off + no auto-WD capability). Status lines
                // were already emitted; return a non-zero exit. No command ran.
                android.util.Log.w(TAG, "onOneShot: not READY, failing without execute")
                sendFrame(fd, AdbCliProtocol.T_OUT, ByteArray(0))
                sendFrame(fd, AdbCliProtocol.T_ERR, ByteArray(0))
                sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(1))
                return
            }
            val result = try {
                execution.adbEngine().execute(
                    com.at.engine.ATExecutionRequest(command = cmd)
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "onOneShot: execute threw: ${e.message}")
                com.at.engine.ATExecutionResult(
                    outcome = com.at.engine.ATExecutionOutcome.TRANSPORT_ERROR,
                    stdout = "", stderr = "", exitCode = -1, hasError = true,
                    error = e.message ?: "execute failed",
                )
            }
            android.util.Log.i(TAG, "onOneShot: result outcome=${result.outcome} exit=${result.exitCode}")
            sendFrame(fd, AdbCliProtocol.T_OUT, result.stdout.toByteArray(Charsets.UTF_8))
            sendFrame(fd, AdbCliProtocol.T_ERR, result.stderr.toByteArray(Charsets.UTF_8))
            if (result.outcome != com.at.engine.ATExecutionOutcome.COMPLETED && result.error != null) {
                pushError(fd, result.error!!)
            }
            val exitCode = if (result.outcome == com.at.engine.ATExecutionOutcome.COMPLETED) result.exitCode else 1
            sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(exitCode))
        }

        /** Open a CLI-owned interactive session. Returns false on failure. */
        fun onInteractiveOpen(fd: Int, rows: Int, cols: Int): Boolean {
            if (!ensureReady(fd)) return false
            val result = try {
                execution.adbEngine().openSession(
                    com.at.engine.ATPersistentBackend.Launch(command = "", initialRows = rows, initialCols = cols)
                )
            } catch (e: Exception) {
                com.at.engine.ATOpenSessionResult.Failure("openSession: ${e.message}")
            }
            val session = when (result) {
                is com.at.engine.ATOpenSessionResult.Success -> result.session
                is com.at.engine.ATOpenSessionResult.Failure -> { pushError(fd, result.reason); return false }
            }
            session.setListener { ev ->
                when (ev) {
                    is com.at.engine.ATSessionEvent.Data -> sendFrame(fd, AdbCliProtocol.T_OUT, ev.bytes)
                    is com.at.engine.ATSessionEvent.Exited -> sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(ev.exitCode))
                    // The ADB backend signals a natural remote-shell exit as Eof
                    // (stream end) — the shell-v2 facade does not carry a shell
                    // exit code (INTERACTIVE_EXIT_CODE = NOT_EXPOSED). Map
                    // Eof -> T_EXIT so the CLI frontend closes the relay.
                    is com.at.engine.ATSessionEvent.Eof -> sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(0))
                    is com.at.engine.ATSessionEvent.Failed -> pushError(fd, ev.error)
                    else -> {}
                }
            }
            sessions[fd] = session
            fdSession[fd]?.let { interactiveForSession[it] = fd }
            return true
        }

        /** Forward CLI stdin / resize to the session. */
        fun onInteractiveData(fd: Int, type: Int, payload: ByteArray) {
            val s = sessions[fd] ?: return
            if (type == AdbCliProtocol.T_STDIN) {
                executor.execute { runCatching { s.write(payload) } }
            } else if (type == AdbCliProtocol.T_RESIZE) {
                val r = payload.le32At(0)
                val c = payload.le32At(4)
                executor.execute { runCatching { s.resize(r, c) } }
            }
        }

        /** Close the CLI-owned session. */
        fun onInteractiveClose(fd: Int) {
            sessions.remove(fd)?.let { runCatching { it.close() } }
            fdSession[fd]?.let { sid ->
                if (interactiveForSession[sid] == fd) interactiveForSession.remove(sid)
            }
        }

        /**
         * Host-layer resize relay. When the host resizes a Local session that is
         * currently hosting an active `adb shell` interactive, forward the new
         * rows/cols to the inner Remote PTY. No-op if no active interactive.
         * This is host/CLI composition — the AT Engine never learns about
         * "nested adb shell".
         */
        fun relayResize(sessionId: Int, rows: Int, cols: Int) {
            val fd = interactiveForSession[sessionId] ?: return
            val s = sessions[fd] ?: return
            executor.execute { runCatching { s.resize(rows, cols) } }
        }

        fun pushError(fd: Int, msg: String) {
            sendFrame(fd, AdbCliProtocol.T_ERROR, msg.toByteArray(Charsets.UTF_8))
        }

        fun shutdown() {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
        }

        private fun sendFrame(fd: Int, type: Int, payload: ByteArray) {
            runCatching { nativeSendFrame(fd, type, payload) }
        }
    }

    external private fun nativeSendFrame(fd: Int, type: Int, payload: ByteArray)

    /**
     * Host-layer resize relay entry. Forward a Local session resize to the
     * inner Remote PTY if that session is mid-`adb shell` interactive.
     */
    fun relayInteractiveResize(sessionId: Int, rows: Int, cols: Int) {
        handler.relayResize(sessionId, rows, cols)
    }
}

private fun ByteArray.le32At(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8) or
    ((this[offset + 2].toInt() and 0xff) shl 16) or
    ((this[offset + 3].toInt() and 0xff) shl 24)
