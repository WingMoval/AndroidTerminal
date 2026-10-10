// Local Backend — JNI binding for the Local PTY provider.
//
// AT.PERSIST-0 — adapted from V2 atsh PtyNative. Loads libat_local_backend.so.

package com.at.backend.local

internal object LocalPtyNative {

    init {
        System.loadLibrary("at_local_backend")
    }

    /**
     * Start a PTY-backed /system/bin/sh -i.
     * @return String[]{slavePath, pid, masterFd} or null on failure.
     */
    external fun nativeStartPty(): Array<String>?

    /** Write bytes to the PTY master. Returns bytes written or -1. */
    external fun nativeWrite(fd: Int, data: ByteArray): Int

    /** Read available bytes with timeout. 0=timeout, -1=error/EOF. */
    external fun nativeRead(fd: Int, buf: ByteArray, timeoutMs: Int): Int

    /** @return IntArray{rows, cols} or null. */
    external fun nativeGetWinsize(fd: Int): IntArray?

    /** Returns 0 on success. */
    external fun nativeSetWinsize(fd: Int, rows: Int, cols: Int): Int

    /** Close master + SIGHUP + reap child. Returns 0 on success. */
    external fun nativeClosePty(fd: Int, pid: Int, timeoutMs: Int): Int
}