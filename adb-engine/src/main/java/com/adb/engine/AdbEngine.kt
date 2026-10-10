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
}