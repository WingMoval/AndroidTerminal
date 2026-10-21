// AT.CLI-CONSUMER-0 — Host-provided execution SPI for the reusable CLI bridge.
//
// The :at-cli bridge server is a REUSABLE consumer capability. It must NOT
// depend on any AndroidTerminal :app concrete class. Instead, the embedding
// host (product app, test consumer host, future AG adapter host) supplies an
// [ATCliExecution] that routes CLI one-shot / interactive requests into the
// host's OWN shared AT composition.
//
// V4-4: Simplified to pure Consumer-facing semantic bridge.
//
// The bridge NEVER creates a second AdbRuntime / AT Engine / identity. It only
// calls into whatever composition the host already owns.
//
// The host supplies:
//   - adbEngine(): ATEngine — the ADB execution engine
//   - ensureReady(): readiness semantic operation
//   - pairingState(): readiness projection (for first-use classification)
//   - pair(): pairing semantic operation (explicit endpoint)
//   - pairDiscovered(): pairing semantic operation (auto-discovered)
//
// PROVISIONAL consumer boundary (CLI_CONSUMER_API_STATUS = PROVISIONAL / DRAFT).
// NOT a final AT public API freeze (AT_CONTRACT_STATUS = DRAFT).

package com.at.cli

import com.at.engine.ATEngine

/**
 * Host-provided execution capability the CLI bridge routes into.
 *
 * A single host instance is shared by all CLI connections in that process —
 * the bridge is a thin frontend over the host's existing execution subsystem.
 *
 * V4-4: This interface no longer exposes:
 *   - AdbRuntime
 *   - AdbConsumerBootstrap
 *   - adbIdentityDir()
 *   - consumerBootstrap()
 *
 * Instead, it uses:
 *   - pairingState(): readiness projection (replaces hasIdentity check)
 *   - pair(): pairing semantic operation (explicit endpoint)
 *   - pairDiscovered(): pairing semantic operation (auto-discovered)
 */
interface ATCliExecution {

    /**
     * The host's ATEngine (ADB backend) that CLI `adb shell` commands execute
     * through. One-shot uses [ATEngine.execute]; interactive uses
     * [ATEngine.openSession].
     *
     * The returned engine MUST route through the host's single AdbRuntime
     * connection (no per-command reconnect, no second ADB stack).
     */
    fun adbEngine(): ATEngine

    /**
     * Ensure the host's ADB lifecycle is READY before executing a CLI command.
     *
     * This is the Runtime-owned ensureReady sequence (readiness / Wireless
     * Debugging / discovery / connect). The host owns this — the bridge does
     * NOT run its own polling loop.
     *
     * [onStatusLine] is invoked with intermediate human-readable status lines
     * (e.g. "checking connection...", "connected", "user action required:
     * enable Wireless Debugging"). The host decides how to surface them:
     *   - :app → Host/SYSTEM terminal event to the bound Local session
     *     (live projection) or T_STATUS fallback.
     *   :at-cli-consumer-host → T_STATUS fallback (no Human UI).
     *
     * Return true if the runtime reached READY (command may proceed), false
     * otherwise (bridge returns a non-zero exit; no command executed).
     */
    fun ensureReady(onStatusLine: (String) -> Unit): Boolean =
        ensureReady(sessionId = 0, onStatusLine = onStatusLine)

    /**
     * Ensure the host's ADB lifecycle is READY before executing a CLI command,
     * bound to a specific Local [sessionId] (from the bridge HELLO payload; 0 =
     * unbound / non-Human client).
     *
     * The [sessionId] lets a host with a Human UI project command-time status
     * LIVE to the bound session (Host/SYSTEM event). The default impl ignores
     * it and relies on [onStatusLine] (the bridge's T_STATUS fallback).
     */
    fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean

    /**
     * Readiness projection for first-use classification.
     *
     * Returns a string classification:
     *   - "READY" — ADB is ready, command may proceed
     *   - "PAIRING_REQUIRED" — no identity, user must pair first
     *   - "CONNECT_FAILED" — identity exists but connection failed
     *   - "NOT_READY" — runtime not initialized
     *
     * This replaces the old hasIdentity() check with a richer classification.
     */
    fun pairingState(): String = "NOT_READY"

    /**
     * Pair against an explicit endpoint using the 6-digit code.
     *
     * This is called by the CLI `adb pair <host>:<port> <code>` command.
     * The host implements this by delegating to its composition's adbControl.
     *
     * @param host the pairing service host
     * @param port the pairing service port
     * @param code the 6-digit pairing code
     * @return a human-readable result message
     */
    fun pair(host: String, port: Int, code: String): String =
        "pairing not supported by this host"

    /**
     * Pair against an auto-discovered endpoint using the 6-digit code.
     *
     * This is called by the CLI `adb pair` command (without explicit endpoint).
     * The host implements this by delegating to its composition's adbControl.
     *
     * @param code the 6-digit pairing code
     * @return a human-readable result message
     */
    fun pairDiscovered(code: String): String =
        "pairing not supported by this host"
}
