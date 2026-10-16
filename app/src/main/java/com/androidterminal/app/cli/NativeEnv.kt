// AT.CLI-0 — JNI helper loader for process environment variables.
package com.androidterminal.app.cli

object NativeEnv {
    init {
        runCatching { System.loadLibrary("atcli_env") }
    }

    external fun setEnv(key: String, value: String)

    /** Create an AF_UNIX SOCK_STREAM listening socket bound to [path]; returns fd or -1. */
    external fun createUdsListen(path: String): Int

    /** Peer UID of a connected AF_UNIX socket [fd] via SO_PEERCRED; -1 on failure. */
    external fun peerUid(fd: Int): Int

    /** Connect a new AF_UNIX socket to [path] (UDS file); returns fd or -1. */
    external fun connectUds(path: String): Int

    /** Read up to buf.size bytes from [fd]; returns bytes read or -1. */
    external fun udsRead(fd: Int, buf: ByteArray): Int

    /** Write buf to [fd]; returns bytes written or -1. */
    external fun udsWrite(fd: Int, buf: ByteArray): Int

    /** Set a recv timeout (ms) on [fd] so reads cannot block forever. */
    external fun udsSetTimeout(fd: Int, ms: Int)

    /** Close a raw fd. */
    external fun udsClose(fd: Int)
}
