// ADB Engine — Kotlin facade (ADB-domain, UI-free).
//
// Canonical V1 §7.2: ADB Engine independently owns ADB connection, one-shot
// shell transport, interactive stream primitive, pairing, discovery,
// readiness, endpoint, recovery, diagnostics.
//
// This facade exposes ONLY ADB-domain types (AdbShellResult / AdbConnectionState
// / AdbConnectionRequest). It does NOT expose Canonical AT Engine result
// semantics, TerminalSession, Emulator, or Screen State.
//
// Owns exactly one native adb::AdbClient (process-scoped). All methods BLOCK;
// call them off the main thread.

package com.adb.engine

import com.adb.engine.internal.AdbNative

object AdbEngine {

    private const val TAG = "AdbEngine"

    /**
     * Establish an ADB connection to a wireless-debugging adbd.
     *
     * @param host device IP, or "127.0.0.1" for on-device loopback.
     * @param port wireless connect port.
     * @param keyDir directory holding the ADB identity (adbkey / cert).
     * @return true when the connection is ONLINE (TLS + post-TLS CNXN done).
     */
    fun connect(host: String, port: Int, keyDir: String): Boolean =
        AdbNative.connect(host, port, keyDir)

    /**
     * AT.TRUST-LOSS-RECOVERY-1: true if the most recent [connect] was rejected
     * by the device at the TLS layer (device no longer trusts our key).
     * Client-observable trust-revocation signal; does NOT depend on adbd
     * logcat. Valid immediately after a failed connect(); false after a
     * successful connect (handshake succeeded).
     */
    fun lastConnectTrustRejected(): Boolean = AdbNative.lastConnectTrustRejected()

    /** Tear down the connection. Idempotent. */
    fun disconnect() = AdbNative.disconnect()

    /** Current ADB connection state (factual projection). */
    fun connectionState(): AdbConnectionState =
        AdbConnectionState.fromNative(AdbNative.connectionState())

    /**
     * Run one non-interactive shell command ("shell,v2,raw"). Blocks.
     *
     * @return ADB-domain result: device stdout/stderr separated from
     *         transport/protocol diagnostics (status / hasError / error).
     */
    fun executeShell(command: String, timeoutMs: Int = 0): AdbShellResult =
        AdbNative.executeShell(command, timeoutMs)

    /**
     * Open an ADB interactive stream primitive (e.g. "shell,v2,pty:").
     * Returns null if the connection is not ONLINE or OPEN fails.
     */
    fun openInteractive(service: String): AdbInteractiveStream? {
        val handle = AdbNative.interactiveOpen(service)
        return if (handle > 0) AdbInteractiveStream(handle) else null
    }

    /**
     * One-shot ADB pairing primitive (`pair(host, pair_port, code)`).
     */
    fun pair(host: String, port: Int, code: String, keyDir: String): AdbPairingResult {
        val raw = AdbNative.pair(host, port, code, keyDir)
        // Native returns "status=0;success=1;guid=...;error=..."
        val fields = raw.split(";").map { it.trim() }
        val success = fields.any { it == "success=1" }
        val guid = fields.firstOrNull { it.startsWith("guid=") }?.substringAfter("=") ?: ""
        val err = fields.firstOrNull { it.startsWith("error=") }?.substringAfter("=") ?: ""
        return AdbPairingResult(success = success, deviceGuid = guid, error = err)
    }

    // --- AT.LOCAL-TCP-1A: plain TCP localhost ADB (product core) ---

    /**
     * Connect to a legacy-mode adbd on 127.0.0.1 (no TLS, no STLS).
     * Idempotent: reuses an ONLINE transport.
     *
     * @param port TCP port the legacy adbd is listening on (e.g. from
     *        `adb tcpip <port>`).
     * @param keyDir directory holding the ADB identity (adbkey / cert).
     * @return true when the connection is ONLINE (CNXN + AUTH done) and the
     *         shared stream hub is started.
     */
    fun connectLocalhost(port: Int, keyDir: String): Boolean =
        AdbNative.connectLocalhost(port, keyDir)

    /** Run a shell command on the Local TCP connection (hub one-shot). */
    fun executeShellLocalhost(command: String): AdbShellResult =
        AdbNative.executeShellLocalhost(command)

    /**
     * Open an interactive service (e.g. "shell,v2,pty:") on the Local TCP
     * connection. Returns null if the Local TCP transport is not ONLINE or
     * OPEN fails.
     */
    fun openInteractiveLocalhost(service: String): AdbInteractiveStream? {
        val handle = AdbNative.interactiveOpenLocalhost(service)
        return if (handle > 0) AdbInteractiveStream(handle) else null
    }

    /** Disconnect the Local TCP connection (transport teardown). */
    fun disconnectLocalhost() = AdbNative.disconnectLocalhost()

    /** True if the Local TCP transport is ONLINE. */
    fun localhostConnected(): Boolean = AdbNative.localhostConnected()

    /** Internal diagnostic counters (NOT public product API). */
    @JvmSynthetic
    internal fun localTcpDebugCounters(): String = AdbNative.localTcpDebugCounters()

    /**
     * AT.LOCAL-TCP-1C (PRECHECK-B fix): request the adbd `tcpip:<port>` DAEMON
     * SERVICE over the current Wireless connection — an ADB OPEN of the
     * "tcpip:<port>" service, NOT an Android shell command. This re-arms adbd in
     * legacy TCP mode on `port`. The Wireless transport dropping immediately
     * after is EXPECTED (the daemon restarts / switches transport).
     *
     * This is a NARROW product capability for the adbd mode switch only (not a
     * generic raw-service API).
     *
     * @return "accepted" when the OPEN was accepted (or delivered and the
     *         transport dropped as expected); "rejected:<reason>" otherwise.
     */
    fun requestTcpip(port: Int): String = AdbNative.requestTcpip(port)

    /**
     * AT.LOCAL-TCP-1D (Gate D): request the adbd `usb:` DAEMON SERVICE over the
     * active ADB connection — an ADB OPEN of the "usb:" service, NOT an Android
     * shell command. This re-arms adbd in USB mode, REMOVING the legacy TCP
     * listener (e.g. :38800) and closing the Local TCP transport. The Local
     * transport dropping immediately after is EXPECTED (adbd switches to USB mode).
     *
     * This is a NARROW product capability for the adbd mode switch only (not a
     * generic raw-service API) — symmetric to [requestTcpip].
     *
     * @return "accepted" when the OPEN was accepted (or delivered and the
     *         transport dropped as expected); "rejected:<reason>" otherwise.
     */
    fun requestUsb(): String = AdbNative.requestUsb()
}