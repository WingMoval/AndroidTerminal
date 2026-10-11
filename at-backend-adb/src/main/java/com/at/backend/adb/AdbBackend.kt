// ADB Backend — real AT Engine execution provider over the ADB Engine.
//
// AT.V3-2. Implements the AT Engine backend contracts using the V3 ADB Engine
// (already independently verified in AT.V3-1). This is a thin Adapter:
//   AT domain  <->  ADB Engine domain
//
// It maps ATExecutionRequest/Result and wraps AdbInteractiveStream into an
// ATTerminalSession. It does NOT implement ADB protocol (that stays in
// :adb-engine). It must NOT bypass AT Engine semantics.

package com.at.backend.adb

import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbEngine
import com.adb.engine.AdbInteractiveStream
import com.adb.engine.AdbShellResult
import com.adb.engine.AdbShellStatus
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOneShotBackend
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import java.nio.charset.StandardCharsets

/**
 * ADB Backend one-shot capability.
 *
 * Mappings:
 *   ATExecutionRequest.command  -> AdbEngine.executeShell (shell,v2,raw)
 *   AdbShellResult              -> ATExecutionResult
 *
 * Non-zero process exit = COMPLETED (AT result semantics preserved);
 * only transport/protocol failure => TRANSPORT_ERROR.
 */
class AdbOneShotBackend(
    private val connection: AdbConnectionRequest,
) : ATOneShotBackend {

    /** Ensure the ADB Engine is ONLINE for this endpoint (connect once). */
    private fun ensureConnected(): Boolean {
        if (AdbEngine.connectionState().isOnline) return true
        return AdbEngine.connect(connection.host, connection.port, connection.keyDir)
    }

    private fun mapAdb(r: AdbShellResult): ATExecutionResult = ATExecutionResult(
        outcome = when (r.status) {
            AdbShellStatus.COMPLETED -> ATExecutionOutcome.COMPLETED
            else -> ATExecutionOutcome.TRANSPORT_ERROR
        },
        stdout = r.stdout,
        stderr = r.stderr,
        exitCode = r.exitCode,
        hasError = r.hasError,
        error = r.error,
    )

    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        if (!ensureConnected()) {
            return ATExecutionResult(
                outcome = ATExecutionOutcome.TRANSPORT_ERROR,
                stdout = "",
                stderr = "",
                exitCode = -1,
                hasError = true,
                error = "ADB connect failed for ${connection.host}:${connection.port}",
            )
        }
        val r = AdbEngine.executeShell(request.command, request.timeoutMs)
        return mapAdb(r)
    }
}

/**
 * ADB Backend persistent capability.
 *
 * Opens a real remote PTY via the ADB Engine interactive stream
 * (`shell,v2,pty:`). Wraps the [AdbInteractiveStream] into an [ATTerminalSession].
 */
class AdbPersistentBackend(
    private val connection: AdbConnectionRequest,
) : ATPersistentBackend {

    private fun ensureConnected(): Boolean {
        if (AdbEngine.connectionState().isOnline) return true
        return AdbEngine.connect(connection.host, connection.port, connection.keyDir)
    }

    override fun openSession(launch: ATPersistentBackend.Launch): ATOpenSessionResult {
        if (!ensureConnected()) {
            return ATOpenSessionResult.Failure("ADB connect failed for ${connection.host}:${connection.port}")
        }
        // Real remote PTY: empty command after "pty:" (AOSP services.cpp).
        val stream = AdbEngine.openInteractive("shell,v2,pty:")
            ?: return ATOpenSessionResult.Failure("ADB interactive open failed")
        val session = AdbTerminalSession(stream)
        // Apply initial size (remote PTY starts at 0x0 unless set).
        session.resize(launch.initialRows, launch.initialCols)
        return ATOpenSessionResult.Success(session)
    }
}

/**
 * AT Engine TerminalSession implementation over an ADB interactive stream.
 *
 * Uses a SINGLE output consumer: a background reader pulls from the stream and
 * dispatches ATSessionEvent.Data / EOF to the listener (push mode). It does NOT
 * use read()+listener concurrently (C1 rule).
 *
 * State model (matching Local):
 *   open -> ACTIVE
 *   read()==-1 (stream end) -> EXITED (or EOF) — the Kotlin ADB facade does not
 *                              expose the shell-v2 exit frame, so a stream end
 *                              with an ACTIVE session is treated as EXITED.
 *   close() -> CLOSED
 */
class AdbTerminalSession internal constructor(
    private val stream: AdbInteractiveStream,
) : ATTerminalSession {

    @Volatile
    override var state: ATSessionState = ATSessionState.ACTIVE
        private set

    @Volatile
    private var listener: ((ATSessionEvent) -> Unit)? = null

    private var readerThread: Thread? = null

    override fun write(bytes: ByteArray): Int {
        if (state != ATSessionState.ACTIVE) return -1
        return stream.write(bytes)
    }

    override fun read(timeoutMs: Int): ByteArray? {
        // Push mode is active (reader owns the stream). Pull read is not a
        // concurrent consumer (C1). Return null as "use listener".
        if (listener != null) return null
        if (state != ATSessionState.ACTIVE) return null
        val buf = ByteArray(4096)
        val n = stream.read(buf, timeoutMs)
        return when {
            n > 0 -> buf.copyOf(n)
            n == 0 -> ByteArray(0) // timeout, still active
            else -> { // -1: stream end / error
                if (state == ATSessionState.ACTIVE) state = ATSessionState.EXITED
                null
            }
        }
    }

    override fun setListener(listener: (ATSessionEvent) -> Unit) {
        this.listener = listener
        if (readerThread == null) {
            readerThread = Thread({ runReader() }, "at-adb-session-reader").also { it.start() }
        }
    }

    private fun runReader() {
        val l = listener ?: return
        val buf = ByteArray(4096)
        while (state == ATSessionState.ACTIVE) {
            val n = stream.read(buf, 100)
            when {
                n > 0 -> l(ATSessionEvent.Data(buf.copyOf(n)))
                n < 0 -> {
                    if (state == ATSessionState.ACTIVE) {
                        state = ATSessionState.EXITED
                        l(ATSessionEvent.Eof)
                    }
                    break
                }
                // n == 0: timeout, keep polling
            }
        }
    }

    override fun resize(rows: Int, cols: Int): Boolean {
        if (state != ATSessionState.ACTIVE) return false
        val ok = stream.resize(rows, cols)
        if (ok) listener?.invoke(ATSessionEvent.Resized(rows, cols))
        return ok
    }

    override fun close(terminateProcess: Boolean) {
        if (state == ATSessionState.CLOSED) return
        state = ATSessionState.CLOSED
        stream.close() // CLSE; does not close the ADB connection (reuse)
        listener?.invoke(ATSessionEvent.Closed)
        readerThread?.join(1000)
        readerThread = null
    }
}