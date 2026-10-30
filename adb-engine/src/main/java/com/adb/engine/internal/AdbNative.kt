// ADB Engine — internal JNI bridge (marshal/forward only).
//
// Canonical V1 §7.2 + AT.V3-1 §5 Batch G: the JNI/Kotlin boundary is a
// marshal layer. It forwards to the native adb::AdbClient (migrated to
// :adb-engine). It does NOT own any runtime state or apply any policy.
//
// This deliberately does NOT include the legacy second-handshake path
// (V2 AdbManager.execute / terminal_jni.cpp nativeExecute) nor netDiag /
// network_probe / dead headers — those stay out of the canonical ADB Engine
// (AT.V3-1 §9).

package com.adb.engine.internal

import com.adb.engine.AdbShellResult

/**
 * Native bridge to libadbd_engine.so.
 *
 * Native functions are `private` to this object (matching the V2 JNI ABI
 * discipline: Kotlin `external fun` must keep the plain symbol name).
 */
internal object AdbNative {

    init {
        System.loadLibrary("adbd_engine")
    }

    external fun connect(host: String, port: Int, keyDir: String): Boolean
    external fun disconnect()
    external fun connectionState(): Int
    external fun executeShell(command: String, timeoutMs: Int): AdbShellResult
    /** AT.TRUST-LOSS-RECOVERY-1: true if the last connect() was rejected by the
     *  device at the TLS layer (device no longer trusts our key). */
    external fun lastConnectTrustRejected(): Boolean

    // --- Interactive stream primitive (ADB Engine) ---
    /** Open a service stream on the current connection; returns native handle (>0) or 0. */
    external fun interactiveOpen(service: String): Long
    /** Write stdin bytes to the stream. Returns bytes written or -1. */
    external fun interactiveWrite(handle: Long, data: ByteArray): Int
    /** Read available bytes. >0 bytes, 0 timeout, -1 error/close. */
    external fun interactiveRead(handle: Long, buf: ByteArray, timeoutMs: Int): Int
    /** Send shell-v2 winsize frame. Returns true on success. */
    external fun interactiveResize(handle: Long, rows: Int, cols: Int): Boolean
    /** Send shell-v2 CloseStdin frame. Returns true on success. */
    external fun interactiveCloseStdin(handle: Long): Boolean
    /** CLSE the stream (does not close the connection). */
    external fun interactiveClose(handle: Long)

    // --- Pairing primitive (ADB Engine) ---
    /** One-shot `pair(host, port, code)`; returns structured result string. */
    external fun pair(host: String, port: Int, code: String, keyDir: String): String

    // --- AT.LOCAL-TCP-1A: plain TCP localhost ADB (product core) ---
    /** Connect to legacy adbd on 127.0.0.1 (no TLS). Idempotent. */
    external fun connectLocalhost(port: Int, keyDir: String): Boolean
    /** Run a shell command on the Local TCP connection (hub one-shot). */
    external fun executeShellLocalhost(command: String): AdbShellResult
    /** Open an interactive service on the Local TCP connection; returns handle (>0) or 0. */
    external fun interactiveOpenLocalhost(service: String): Long
    /** Disconnect the Local TCP connection. */
    external fun disconnectLocalhost()
    /** True if the Local TCP transport is ONLINE. */
    external fun localhostConnected(): Boolean
    /** Internal diagnostic counters (NOT public product API). */
    external fun localTcpDebugCounters(): String

    // --- AT.LOCAL-TCP-1C/1D: narrow adbd mode-switch capability ---
    /**
     * Request the adbd `tcpip:<port>` DAEMON SERVICE (an ADB OPEN, NOT an
     * Android shell command) over the current Wireless connection, re-arming
     * adbd in legacy TCP mode on `port`. Returns "accepted" / "rejected:<reason>".
     * The Wireless transport dropping right after is EXPECTED (daemon restarts).
     */
    external fun requestTcpip(port: Int): String

    // --- AT.LOCAL-TCP-1D (Gate D): symmetric narrow adbd mode-switch ---
    /**
     * Request the adbd `usb:` DAEMON SERVICE (an ADB OPEN, NOT an Android shell
     * command) over the active ADB connection, re-arming adbd in USB mode. This
     * REMOVES the legacy TCP listener (e.g. :38800) and closes the Local TCP
     * transport. Returns "accepted" / "rejected:<reason>". The Local transport
     * dropping right after is EXPECTED (adbd switches to USB mode).
     */
    external fun requestUsb(): String
}