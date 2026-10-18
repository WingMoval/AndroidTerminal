// :app — JNI helper loader for CLI frontend provisioning + debug UDS test.
//
// AT.CLI-CONSUMER-0: the bridge server (atcli_env) moved to :at-cli. :app keeps
// only setenv (CLI provisioning) + the UDS test helpers (debug CliProbe) in
// atapp_env (app_env.cpp).

package com.androidterminal.app.cli

object NativeEnv {
    init {
        runCatching { System.loadLibrary("atapp_env") }
    }

    external fun setEnv(key: String, value: String)

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
