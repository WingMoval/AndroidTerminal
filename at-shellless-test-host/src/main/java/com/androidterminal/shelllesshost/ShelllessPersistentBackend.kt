// AT.TERM-0 鈥?shellless persistent backend (VALIDATION ONLY).
//
// Implements the AT Engine ATPersistentBackend contract backed by a REAL
// Android PTY whose child is a NON-shell interactive program (the in-process
// native probe). This proves the AT persistent terminal abstraction is a
// backend-neutral, shell-independent byte-stream session.
//
// Launch.command is treated as an OPAQUE launch selector (NOMINAL_ONLY shell
// coupling): only the value "probe" is accepted; anything else is a Failure.
// It is NEVER passed to a shell / sh -c / system()/popen()/execve().
//
// VALIDATION ONLY. Not a production backend.

package com.androidterminal.shelllesshost

import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import com.at.engine.ATSessionLaunch

/** Shared validation constants (VALIDATION ONLY). */
internal object Sl {
    const val READ_BUF = 4096
    const val EXIT_WAIT_MS = 2000
    const val PROBE_COMMAND = "probe"
}

class ShelllessPersistentBackend : ATPersistentBackend {

    override fun openSession(launch: ATSessionLaunch): ATOpenSessionResult {
        // Opaque launch selector: only "probe" is valid. Never sh -c / exec.
        val cmd = launch.command.trim()
        if (cmd != Sl.PROBE_COMMAND && cmd != "") {
            return ATOpenSessionResult.Failure(
                "shellless backend: unknown launch selector '$cmd' (expected 'probe'); " +
                "no shell is launched"
            )
        }
        val rows = if (launch.initialRows > 0) launch.initialRows else 24
        val cols = if (launch.initialCols > 0) launch.initialCols else 80
        val handle = ShelllessPtyNative.start(rows, cols)
            ?: return ATOpenSessionResult.Failure("shellless backend: PTY open failed")
        return ATOpenSessionResult.Success(
            ShelllessSession(handle.slavePath, handle.pid, handle.masterFd)
        )
    }
}

/**
 * AT TerminalSession over a real shellless PTY.
 *
 * A single reader thread drains the PTY master and feeds the pull buffer and/or
 * the push listener. State transitions follow the AT contract:
 *   OPENING -> ACTIVE -> EXITED (natural) / CLOSED (explicit) / FAILED.
 */
internal class ShelllessSession(
    val slavePath: String,
    val pid: Int,
    val masterFd: Int,
) : ATTerminalSession {

    @Volatile private var _state: ATSessionState = ATSessionState.OPENING
    override val state: ATSessionState get() = _state

    @Volatile private var listener: ((ATSessionEvent) -> Unit)? = null
    private val pullBuf = java.util.ArrayDeque<ByteArray>()
    private val bufLock = Any()
    @Volatile private var exitedCode: Int? = null
    private val reader = Thread({ readerLoop() }, "shellless-reader-$pid")
    @Volatile private var closed = false

    init {
        reader.start()
        _state = ATSessionState.ACTIVE
    }

    private fun readerLoop() {
        val buf = ByteArray(Sl.READ_BUF)
        while (!closed) {
            val n = ShelllessPtyNative.read(masterFd, buf, 100)
            when {
                n > 0 -> {
                    val chunk = buf.copyOfRange(0, n)
                    synchronized(bufLock) { pullBuf.addLast(chunk) }
                    listener?.invoke(ATSessionEvent.Data(chunk))
                }
                n < 0 -> {
                    // EOF / master gone: the child likely exited. Reap to learn code.
                    observeExit()
                    break
                }
                else -> { /* timeout: keep polling */ }
            }
            // Also detect natural exit even if no EOF byte arrived.
            if (observeExit()) break
        }
        if (_state == ATSessionState.ACTIVE || _state == ATSessionState.OPENING) {
            if (exitedCode != null) {
                _state = ATSessionState.EXITED
                listener?.invoke(ATSessionEvent.Exited(exitedCode!!))
            } else {
                _state = ATSessionState.CLOSED
                listener?.invoke(ATSessionEvent.Closed)
            }
        }
    }

    /** @return true if the child has exited (code recorded). */
    private fun observeExit(): Boolean {
        if (exitedCode != null) return true
        val status = IntArray(1)
        val rc = ShelllessPtyNative.waitpidNonblock(pid, status)
        if (rc == 1) {
            exitedCode = exitCodeFromStatus(status[0])
        }
        return exitedCode != null
    }

    private fun exitCodeFromStatus(status: Int): Int {
        // wait(2): if WIFEXITED, code = WEXITSTATUS; if WIFSIGNALED, code = 128+sig.
        if ((status and 0x7f) == 0) return (status shr 8) and 0xff
        return 128 + (status and 0x7f)
    }

    override fun write(bytes: ByteArray): Int {
        if (_state != ATSessionState.ACTIVE) return -1
        val n = ShelllessPtyNative.write(masterFd, bytes)
        if (n < 0) {
            _state = ATSessionState.FAILED
            listener?.invoke(ATSessionEvent.Failed("write failed"))
        }
        return n
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val acc = java.io.ByteArrayOutputStream()
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            synchronized(bufLock) {
                val b = pullBuf.pollFirst()
                if (b != null) acc.write(b)
            }
            if (acc.size() > 0) break
            if (_state == ATSessionState.EXITED || _state == ATSessionState.CLOSED ||
                _state == ATSessionState.FAILED) {
                if (acc.size() == 0) return null
                break
            }
            Thread.sleep(10)
        }
        if (_state == ATSessionState.EXITED || _state == ATSessionState.CLOSED ||
            _state == ATSessionState.FAILED) {
            return if (acc.size() > 0) acc.toByteArray() else null
        }
        return acc.toByteArray()
    }

    override fun setListener(listener: (ATSessionEvent) -> Unit) {
        this.listener = listener
    }

    override fun resize(rows: Int, cols: Int): Boolean {
        if (_state != ATSessionState.ACTIVE) return false
        val rc = ShelllessPtyNative.setWinsize(masterFd, rows, cols)
        if (rc == 0) {
            listener?.invoke(ATSessionEvent.Resized(rows, cols))
            return true
        }
        return false
    }

    override fun close(terminateProcess: Boolean) {
        if (closed) return
        closed = true
        val st = _state
        if (st == ATSessionState.CLOSED) return  // already closed (idempotent)
        // Stop the reader (it will exit on closed flag / EOF).
        reader.interrupt()
        val status = ShelllessPtyNative.close(masterFd, pid, Sl.EXIT_WAIT_MS)
        if (status >= 0 && exitedCode == null) {
            exitedCode = exitCodeFromStatus(status)
        }
        // Always surface Closed on explicit close (idempotent at the AT level).
        _state = ATSessionState.CLOSED
        listener?.invoke(ATSessionEvent.Closed)
    }
}


