// AT.CLI-CONSUMER-0 — Host-provided execution SPI for the reusable CLI bridge.
//
// The :at-cli bridge server is a REUSABLE consumer capability. It must NOT
// depend on any AndroidTerminal :app concrete class. Instead, the embedding
// host (product app, test consumer host, future AG adapter host) supplies an
// [ATCliExecution] that routes CLI one-shot / interactive requests into the
// host's OWN shared AT / AdbRuntime composition.
//
// This is the seam that makes the bridge host-agnostic:
//   - :app provides AppCliExecution (wires AppRuntime.adbEngine + AdbRuntime
//     ensureReady + Human status projection).
//   - :at-cli-consumer-host provides its own ATCliExecution (wires its own
//     ATEngine + AdbRuntime — NO :app dependency).
//
// The bridge NEVER creates a second AdbRuntime / AT Engine / identity. It only
// calls into whatever composition the host already owns.
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
     * The host's ADB identity directory (app-private). Used by the host for
     * pairing / bootstrap. The bridge does not read keys directly; this is
     * exposed for host-side bootstrap surfaces (e.g. `adb pair` delegation).
     */
    fun adbIdentityDir(): String
}
