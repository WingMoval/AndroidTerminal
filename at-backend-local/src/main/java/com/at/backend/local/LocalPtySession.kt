// Local Backend — Local PTY session (the provider primitive).
//
// AT.PERSIST-0 — wraps the migrated native PTY (local_pty.cpp) into a real,
// live Local session object used by the Local persistent backend. It is the
// provider-side handle owned by the backend; the AT Engine TerminalSession
// wraps it (ownership analysis in C3 / report).

package com.at.backend.local

import java.nio.charset.StandardCharsets

/**
 * A live Local PTY session backed by /system/bin/sh -i.
 *
 * Owns the native master fd + child pid. Provides the byte-level transport:
 * write / read / resize / close. It does NOT carry AT session lifecycle /
 * events — that is layered above it by [LocalPersistentBackend].
 */
class LocalPtySession internal constructor(
    private var masterFd: Int,
    private var pid: Int,
) {
    var slavePath: String = ""
        private set

    val isOpen: Boolean get() = masterFd >= 0

    internal fun adopt(slave: String) {
        slavePath = slave
    }

    /** Write UTF-8 bytes to the PTY master. Returns bytes written or -1. */
    fun write(s: String): Int = write(s.toByteArray(StandardCharsets.UTF_8))

    fun write(data: ByteArray): Int = LocalPtyNative.nativeWrite(masterFd, data)

    /**
     * Read available bytes with a per-call timeout.
     * @return bytes read (>0), 0 on timeout, -1 on error/EOF.
     */
    fun read(buf: ByteArray, timeoutMs: Int): Int =
        LocalPtyNative.nativeRead(masterFd, buf, timeoutMs)

    fun getWinsize(): Pair<Int, Int>? {
        val ws = LocalPtyNative.nativeGetWinsize(masterFd) ?: return null
        return ws[0] to ws[1]
    }

    fun setWinsize(rows: Int, cols: Int): Boolean =
        LocalPtyNative.nativeSetWinsize(masterFd, rows, cols) == 0

    /** Close master + signal + reap child. Idempotent. */
    fun close(timeoutMs: Int = 3000) {
        if (masterFd >= 0) {
            LocalPtyNative.nativeClosePty(masterFd, pid, timeoutMs)
        }
        masterFd = -1
        pid = -1
    }

    companion object {
        /**
         * Start a new Local PTY shell (/system/bin/sh -i).
         * @return the session, or null on failure.
         */
        fun start(): LocalPtySession? {
            val info = LocalPtyNative.nativeStartPty() ?: return null
            if (info.size < 3) return null
            val session = LocalPtySession(info[2].toInt(), info[1].toInt())
            session.adopt(info[0])
            return session
        }
    }
}