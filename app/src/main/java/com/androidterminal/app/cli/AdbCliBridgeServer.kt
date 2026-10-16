// AT.CLI-0 — Product CLI Bridge server (app-internal, Unix Domain Socket).
//
// Thin Kotlin wrapper over a native UDS server (cli_bridge_server.cpp) that
// binds in the app's private files dir, verifies peer UID == app UID
// (SO_PEERCRED), and speaks the length-prefixed Product CLI Bridge protocol.
// The [CliBridgeHandler] below delegates to the existing App-owned execution
// capability:
//
//   one-shot    -> AppRuntime.adbEngine().execute(...)   (AdbOneShot path)
//   interactive -> AppRuntime.adbEngine().openSession(...) (AdbPersistent path)
//
// This is a CONSUMER/frontend adapter. It does NOT create a second AdbRuntime,
// second identity, second ADB server, or touch the AT/ADB Engine contract. The
// CLI invocation owns its remote ATTerminalSession (closed on CLI disconnect);
// it is NOT registered as a Human SessionPage.
//
// Product bridge: present in BOTH debug and release builds (NOT gated by
// BuildConfig.DEBUG). Independent from AT.DEBUG-0 DebugBridge (no port/protocol
// reuse).

package com.androidterminal.app.cli

import android.os.Process
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.androidterminal.app.AppRuntime
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATTerminalSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AdbCliBridgeServer(private val filesDir: File) {

    companion object {
        private const val TAG = "AdbCliBridge"
    }

    init {
        runCatching { System.loadLibrary("atcli_env") }
    }

    /** The UDS path (app-private files dir). */
    val socketPath: String = File(filesDir, "cli_bridge.sock").absolutePath

    private val handler = CliBridgeHandler()
    private var listenFd = -1
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "at-cli-bridge").apply { isDaemon = true }
    }

    @Volatile private var started = false

    fun isRunning() = started

    external private fun nativeStart(path: String, handler: CliBridgeHandler): Int
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

    private inner class CliBridgeHandler {

        // CLI-owned interactive sessions, keyed by client fd.
        private val sessions = ConcurrentHashMap<Int, ATTerminalSession>()
        // AT.CLI-0.1A: fd -> Local session id (from HELLO payload) for ADB status
        // subscription binding.
        private val fdSession = ConcurrentHashMap<Int, Int>()
        // AT.CLI-0.2: Local session id -> active interactive client fd (the
        // session currently hosting an `adb shell` remote PTY). Used by the
        // app-layer resize relay (§20): when the Human resizes a Local session
        // that is mid-interactive, forward the new size to the inner Remote PTY.
        // This is a CLI-connection composition map, NOT a second ADB runtime /
        // session registry / recovery owner.
        private val interactiveForSession = ConcurrentHashMap<Int, Int>()

        /** Auth: same-UID only (CLI runs as app UID). */
        fun onClientAuth(fd: Int, peerUid: Int): Boolean = peerUid == Process.myUid()

        /**
         * AT.CLI-0.1A: called by the native server after HELLO/ACK with the
         * Local session id carried in the HELLO payload (0 = none). Records the
         * fd->session binding used for ADB lifecycle status subscription.
         */
        fun onHello(fd: Int, sessionId: Int) {
            if (sessionId > 0) fdSession[fd] = sessionId
        }

        /**
         * AT.CLI-0.1A: Runtime-owned ensureReady. Delegates the readiness/WD/
         * discovery/connect sequence to the single AdbRuntime (NOT a CLI-owned
         * polling loop). Emits `[adb] ...` STATUS frames (stderr channel) as the
         * Runtime progresses. Subscribes the bound Local session to ADB lifecycle
         * status so ASYNC transitions (disconnect/reconnect) reach it after the
         * one-shot process exits.
         */
        private fun ensureReady(fd: Int): Boolean {
            val sid = fdSession[fd]
            // AT.CLI-0.1B: subscribe BEFORE ensureReady so that command-time
            // status (Host projection) can reach the session DURING the
            // cold-connect sequence (not just after). The session is marked
            // adb-active immediately.
            if (sid != null) {
                val firstSubscribe = !AppRuntime.isAdbStatusSubscribed(sid)
                AppRuntime.subscribeAdbStatus(sid)
                // If AdbRuntime is ALREADY READY (bootstrap connect happened
                // before this subscribe), the lifecycle transition already
                // fired (subscribed was empty then). Backfill "connected" so
                // the session sees it (no duplicate: the transition won't
                // fire again for the same state).
                if (firstSubscribe && AdbRuntime.readinessState().isReady) {
                    emitStatus(fd, sid, "connected")
                }
            }

            // Fast path: already READY. Surface the "checking" status only on
            // THIS session's FIRST use (dedupe: subsequent same-session commands
            // stay silent — no state change, no re-spam). See §23.
            if (AdbRuntime.readinessState().isReady) {
                val firstUse = sid != null && !wasStatusEmittedForSession(fd)
                if (firstUse) {
                    // Host projection for valid session; T_STATUS fallback otherwise.
                    emitStatus(fd, sid, "checking connection...")
                    // "connected" is already emitted above (backfill) or will
                    // be emitted by the lifecycle transition (cold path).
                }
                return true
            }
            // Not READY: run the Runtime-owned sequence, forwarding status lines
            // LIVE via Host projection (valid session) or T_STATUS (fallback).
            //
            // Dedupe: the "connected" line is NOT emitted here — the AdbRuntime
            // lifecycle transition (READY) already projects `[adb] connected` via
            // AdbCliStatusHub.onTransition. Emitting it again from the
            // statusListener would duplicate. We only emit the INTERMEDIATE
            // status lines (checking / enabling / discovering / connecting /
            // retrying / user action required / pairing / connection failed).
            val keyDir = runCatching { AppRuntime.adbIdentityDir() }.getOrNull() ?: return false
            val finalState = AdbRuntime.ensureReady(keyDir) { line ->
                // Skip "connected" — lifecycle transition owns it (no duplicate).
                if (line != "connected") emitStatus(fd, sid, line)
            }
            return finalState == AdbRuntimeState.READY
        }

        /**
         * AT.CLI-0.1B: emit a status line to the bound session via HOST
         * projection (live, immediate) when a valid Local session is bound;
         * otherwise fall back to T_STATUS (the short-lived AdbCliMain path,
         * which buffers until EXIT — acceptable for non-Human clients).
         *
         * This is the SINGLE projection path for command-time status: it does
         * NOT also send T_STATUS for a valid session (avoids duplicate).
         */
        private fun emitStatus(fd: Int, sid: Int?, line: String) {
            if (sid != null && sid > 0 && AppRuntime.isAdbStatusSubscribed(sid)) {
                // Valid Local session: HOST projection (live, immediate).
                AppRuntime.emitAdbCommandStatus(sid, line)
                // Mark that status was emitted for this fd (dedupe fast-path).
                statusEmittedForFd[fd] = true
            } else {
                // No valid session (test client / future non-Human client):
                // T_STATUS fallback (AdbCliMain buffers until EXIT).
                sendStatus(fd, "[adb] $line")
            }
        }

        /** Track whether command-time status was already emitted for this fd. */
        private val statusEmittedForFd = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        private fun wasStatusEmittedForSession(fd: Int): Boolean =
            statusEmittedForFd[fd] == true

        private fun sendStatus(fd: Int, text: String) {
            sendFrame(fd, AdbCliProtocol.T_STATUS, text.toByteArray(Charsets.UTF_8))
        }

        /** One-shot: execute and push OUT/ERR/(ERROR)/EXIT frames. */
        fun onOneShot(fd: Int, cmdBytes: ByteArray) {
            val cmd = String(cmdBytes, Charsets.UTF_8)
            android.util.Log.i(TAG, "onOneShot: ensureReady for [$cmd]")
            val ok = ensureReady(fd)
            if (!ok) {
                // Not READY (e.g. WD off + no auto-WD capability). The status
                // lines (user action required / connection failed) were already
                // emitted; return a non-zero exit. No command was executed.
                android.util.Log.w(TAG, "onOneShot: not READY, failing without execute")
                sendFrame(fd, AdbCliProtocol.T_OUT, ByteArray(0))
                sendFrame(fd, AdbCliProtocol.T_ERR, ByteArray(0))
                sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(1))
                return
            }
            val result: ATExecutionResult = try {
                AppRuntime.adbEngine().execute(ATExecutionRequest(command = cmd))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "onOneShot: execute threw: ${e.message}")
                ATExecutionResult(
                    outcome = ATExecutionOutcome.TRANSPORT_ERROR,
                    stdout = "", stderr = "", exitCode = -1, hasError = true,
                    error = e.message ?: "execute failed",
                )
            }
            android.util.Log.i(TAG, "onOneShot: result outcome=${result.outcome} exit=${result.exitCode}")
            sendFrame(fd, AdbCliProtocol.T_OUT, result.stdout.toByteArray(Charsets.UTF_8))
            sendFrame(fd, AdbCliProtocol.T_ERR, result.stderr.toByteArray(Charsets.UTF_8))
            if (result.outcome != ATExecutionOutcome.COMPLETED && result.error != null) {
                pushError(fd, result.error!!)
            }
            val exitCode = if (result.outcome == ATExecutionOutcome.COMPLETED) result.exitCode else 1
            sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(exitCode))
        }

        /** Open a CLI-owned interactive session. Returns false on failure. */
        fun onInteractiveOpen(fd: Int, rows: Int, cols: Int): Boolean {
            if (!ensureReady(fd)) return false
            val result: ATOpenSessionResult = try {
                AppRuntime.adbEngine().openSession(
                    ATPersistentBackend.Launch(command = "", initialRows = rows, initialCols = cols)
                )
            } catch (e: Exception) {
                ATOpenSessionResult.Failure("openSession: ${e.message}")
            }
            val session = when (result) {
                is ATOpenSessionResult.Success -> result.session
                is ATOpenSessionResult.Failure -> { pushError(fd, result.reason); return false }
            }
            session.setListener { ev ->
                when (ev) {
                    is ATSessionEvent.Data -> sendFrame(fd, AdbCliProtocol.T_OUT, ev.bytes)
                    is ATSessionEvent.Exited -> sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(ev.exitCode))
                    // AT.CLI-0.2: the ADB backend signals a natural remote-shell
                    // exit as an Eof (stream end) — the shell-v2 facade does not
                    // carry a shell exit code (INTERACTIVE_EXIT_CODE = NOT_EXPOSED).
                    // Map Eof -> T_EXIT so the CLI frontend closes the relay and
                    // the shell wrapper restores the Outer TTY. Without this the
                    // relay never terminates (AdbCliMain blocks forever).
                    is ATSessionEvent.Eof -> sendFrame(fd, AdbCliProtocol.T_EXIT, AdbCliProtocol.le32Bytes(0))
                    is ATSessionEvent.Failed -> pushError(fd, ev.error)
                    else -> {}
                }
            }
            sessions[fd] = session
            // AT.CLI-0.2: record the Local session -> interactive fd binding so
            // the app-layer resize relay can reach the inner Remote PTY.
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
            // AT.CLI-0.2: drop the Local session -> interactive fd binding.
            fdSession[fd]?.let { sid ->
                // Only remove if this fd still owns the binding (a newer
                // interactive on the same session would have overwritten it).
                if (interactiveForSession[sid] == fd) interactiveForSession.remove(sid)
            }
        }

        /**
         * AT.CLI-0.2: app-layer resize relay (§20). When the Human resizes a
         * Local session that is currently hosting an active `adb shell`
         * interactive, forward the new rows/cols to the inner Remote PTY (so
         * remote `stty size` reflects the change). No-op if the session has no
         * active interactive. This is app/CLI composition — the AT Engine
         * never learns about "nested adb shell".
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
     * AT.CLI-0.2: app-layer resize relay entry. Forward a Local session resize
     * to the inner Remote PTY if that session is mid-`adb shell` interactive.
     * See [CliBridgeHandler.relayResize].
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
