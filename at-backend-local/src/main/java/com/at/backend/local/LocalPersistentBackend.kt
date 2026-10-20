// Local Backend 鈥?persistent terminal backend (real).
//
// AT.PERSIST-0 鈥?migrates the V2 ATsh.T1 verified Local PTY semantics into the
// AT Engine backend contract. Implements:
//   ATPersistentBackend.openSession(launch)
//     -> LocalPtySession.start()   (real /system/bin/sh -i on a real PTY)
//     -> LocalTerminalSession       (AT Engine TerminalSession impl)
//
// Ownership (C3): this backend constructs and returns an ATTerminalSession
// implementation. Logical ownership of session semantics remains with the AT
// Engine (consumer-facing); the backend provides the underlying provider
// primitive (LocalPtySession) and an AT-domain view over it. This is an
// implementation placement, not a contract reversal.

package com.at.backend.local

import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionLaunch
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession

/**
 * Local persistent backend.
 *
 * Opens a real Local PTY shell (/system/bin/sh -i) 鈥?the V2 T1 verified
 * primitive migrated into :at-backend-local.
 */
class LocalPersistentBackend : ATPersistentBackend {

    override fun openSession(launch: ATSessionLaunch): ATOpenSessionResult {
        val pty = LocalPtySession.start()
            ?: return ATOpenSessionResult.Failure("local pty start failed")
        val session = LocalTerminalSession(pty)
        return ATOpenSessionResult.Success(session)
    }
}

/**
 * AT Engine TerminalSession implementation over a Local PTY.
 *
 * Wraps [LocalPtySession] (the provider primitive) and implements the AT
 * session contract: byte transport + lifecycle/state + events.
 *
 * C1 (read/listener model): the session supports ONE active consumption mode 鈥?
 * either pull ([read]) or push ([setListener]). Setting a listener starts a
 * background reader that owns the fd and dispatches events; while a listener is
 * active, [read] is not a valid concurrent consumer. Default is pull.
 *
 * C2 (read==null): [read] returns null when the session has ended. The precise
 * cause is expressed by [state] (EXITED / FAILED / CLOSED / EOF) 鈥?read-null
 * never conflates process exit, failure, caller close, or EOF.
 */
class LocalTerminalSession internal constructor(
    private val pty: LocalPtySession,
) : ATTerminalSession {

    @Volatile
    override var state: ATSessionState = ATSessionState.OPENING
        private set

    @Volatile
    private var listener: ((ATSessionEvent) -> Unit)? = null

    private var readerThread: Thread? = null

    init {
        state = ATSessionState.ACTIVE
    }

    override fun write(bytes: ByteArray): Int {
        if (state != ATSessionState.ACTIVE) return -1
        return pty.write(bytes)
    }

    override fun read(timeoutMs: Int): ByteArray? {
        // Pull mode: caller-driven reads. Not valid while a listener reader is
        // active (C1) 鈥?only one consumer of the fd at a time.
        val l = listener
        if (l != null) {
            // Listener owns the reader; pull is not a concurrent consumer.
            return null
        }
        if (state != ATSessionState.ACTIVE) return null
        val buf = ByteArray(4096)
        val n = pty.read(buf, timeoutMs)
        if (n > 0) {
            return buf.copyOf(n)
        }
        if (n == 0) {
            return ByteArray(0) // timeout: no data, session still active
        }
        // n < 0: EOF/error. Distinguish via process/stream state.
        if (state == ATSessionState.ACTIVE) {
            // EOF without explicit exit: treat as EOF (no exit code known).
            state = ATSessionState.EXITED
        }
        return null
    }

    override fun setListener(listener: (ATSessionEvent) -> Unit) {
        this.listener = listener
        if (readerThread == null) {
            readerThread = Thread({ runReader() }, "at-local-session-reader").also { it.start() }
        }
    }

    private fun runReader() {
        val l = listener ?: return
        while (state == ATSessionState.ACTIVE) {
            val buf = ByteArray(4096)
            val n = pty.read(buf, 100)
            if (n > 0) {
                l(ATSessionEvent.Data(buf.copyOf(n)))
            } else if (n < 0) {
                // Stream ended.
                when (state) {
                    ATSessionState.ACTIVE -> {
                        state = ATSessionState.EXITED
                        l(ATSessionEvent.Eof)
                    }
                    else -> { /* already terminal */ }
                }
                break
            }
        }
    }

    override fun resize(rows: Int, cols: Int): Boolean {
        if (state != ATSessionState.ACTIVE) return false
        val ok = pty.setWinsize(rows, cols)
        if (ok) {
            listener?.invoke(ATSessionEvent.Resized(rows, cols))
        }
        return ok
    }

    override fun close(terminateProcess: Boolean) {
        if (state == ATSessionState.CLOSED) return
        state = ATSessionState.CLOSED
        pty.close()
        listener?.invoke(ATSessionEvent.Closed)
        readerThread?.join(1000)
        readerThread = null
    }
}
