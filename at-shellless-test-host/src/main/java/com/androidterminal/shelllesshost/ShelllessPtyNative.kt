// AT.TERM-0 — shellless PTY native wrapper (VALIDATION ONLY).
//
// Thin JNI bridge over libat_shellless_probe.so. The child is a NON-shell
// interactive program (the in-process probe); no /system/bin/sh is launched.

package com.androidterminal.shelllesshost

object ShelllessPtyNative {

    init {
        System.loadLibrary("at_shellless_probe")
    }

    /** @return String[] {slavePath, pid, masterFd} or null on failure. */
    private external fun nativeStart(rows: Int, cols: Int): Array<String>?

    private external fun nativeWrite(fd: Int, data: ByteArray): Int

    private external fun nativeRead(fd: Int, buf: ByteArray, timeoutMs: Int): Int

    private external fun nativeSetWinsize(fd: Int, rows: Int, cols: Int): Int

    private external fun nativeGetWinsize(fd: Int): IntArray?

    private external fun nativeWaitpidNonblock(pid: Int, status: IntArray): Int

    private external fun nativeClose(fd: Int, pid: Int, timeoutMs: Int): Int

    /** @return StartHandle or null on failure. */
    fun start(rows: Int, cols: Int): StartHandle? {
        val arr = nativeStart(rows, cols) ?: return null
        return StartHandle(
            slavePath = arr[0],
            pid = arr[1].toInt(),
            masterFd = arr[2].toInt(),
        )
    }

    fun write(fd: Int, data: ByteArray): Int = nativeWrite(fd, data)

    /** @return bytes read (0=timeout, -1=error/EOF). */
    fun read(fd: Int, buf: ByteArray, timeoutMs: Int): Int = nativeRead(fd, buf, timeoutMs)

    fun setWinsize(fd: Int, rows: Int, cols: Int): Int = nativeSetWinsize(fd, rows, cols)

    fun getWinsize(fd: Int): IntArray? = nativeGetWinsize(fd)

    /** @return 1 if exited (status[0]=wait status), 0 if running, -1 error. */
    fun waitpidNonblock(pid: Int, status: IntArray): Int = nativeWaitpidNonblock(pid, status)

    /** @return child wait status or -1. */
    fun close(fd: Int, pid: Int, timeoutMs: Int): Int = nativeClose(fd, pid, timeoutMs)

    data class StartHandle(val slavePath: String, val pid: Int, val masterFd: Int)
}
