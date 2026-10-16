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
        private var ready = false

        /** Auth: same-UID only (CLI runs as app UID). */
        fun onClientAuth(fd: Int, peerUid: Int): Boolean = peerUid == Process.myUid()

        private fun ensureReady() {
            if (ready) return
            if (AdbRuntime.readinessState().isReady) { ready = true; return }
            runCatching { AppRuntime.connectAuto() }
            ready = AdbRuntime.readinessState().isReady
        }

        /** One-shot: execute and push OUT/ERR/(ERROR)/EXIT frames. */
        fun onOneShot(fd: Int, cmdBytes: ByteArray) {
            ensureReady()
            val cmd = String(cmdBytes, Charsets.UTF_8)
            val result: ATExecutionResult = try {
                AppRuntime.adbEngine().execute(ATExecutionRequest(command = cmd))
            } catch (e: Exception) {
                ATExecutionResult(
                    outcome = ATExecutionOutcome.TRANSPORT_ERROR,
                    stdout = "", stderr = "", exitCode = -1, hasError = true,
                    error = e.message ?: "execute failed",
                )
            }
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
            ensureReady()
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
                    is ATSessionEvent.Failed -> pushError(fd, ev.error)
                    else -> {}
                }
            }
            sessions[fd] = session
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
}

private fun ByteArray.le32At(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8) or
    ((this[offset + 2].toInt() and 0xff) shl 16) or
    ((this[offset + 3].toInt() and 0xff) shl 24)
