// AT Composition Android — ADB control facade (V4-4).
//
// This is a CONSUMER SEMANTIC FACADE (PROJECTION_ONLY). It does NOT:
//   - own the ADB lifecycle
//   - own recovery
//   - own pairing
//   - create a second AdbRuntime
//
// It ONLY delegates to:
//   - AdbRuntime (lifecycle owner)
//   - AdbConsumerBootstrap (first-use bootstrap implementation)
//   - AdbEngine.pair (pairing primitive)
//
// Consumer-visible API does NOT expose:
//   - AdbRuntime
//   - AdbEngine
//   - AdbConsumerBootstrap
//   - AdbConnectionRequest
//   - identityDir
//   - File
//
// STATE_MACHINE_DELTA = NONE (only mapping, not new state machine)

package com.at.composition.android

import com.adb.engine.AdbBootstrapState
import com.adb.engine.AdbConsumerBootstrap
import com.adb.engine.AdbPairingDiscovery
import com.adb.engine.AdbPairingResultV2
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState

/**
 * ADB control facade for Android consumers.
 *
 * This is a PROJECTION_ONLY facade. It does NOT own the ADB lifecycle,
 * recovery, or pairing. It only delegates to the existing owners:
 *   - AdbRuntime (lifecycle owner)
 *   - AdbConsumerBootstrap (first-use bootstrap implementation)
 *   - AdbEngine.pair (pairing primitive)
 *
 * Usage:
 * ```kotlin
 * val composition = ATAndroid.create(context)
 * val control = composition.adbControl
 *
 * // Check readiness
 * val state = control.bootstrapState()
 * if (state == AdbBootstrapState.PAIRING_REQUIRED) {
 *     // Discover pairing service
 *     val discovery = control.discoverPairing()
 *     // ... user enters code ...
 *     val result = control.pairDiscovered(code)
 * }
 *
 * // Ensure ready before executing commands
 * val ready = control.ensureReady { line ->
 *     // status line callback
 * }
 * ```
 */
class ATAndroidAdbControl internal constructor(
    /** The app-private identity directory (NOT exposed to consumers). */
    private val identityDir: String,
) {

    /**
     * The consumer-facing ADB bootstrap (first-use pairing boundary).
     *
     * This is the SINGLE pairing boundary shared with:
     *   - Product UI (PairActivity)
     *   - CLI `adb pair`
     *   - Consumer host
     */
    private val bootstrap: AdbConsumerBootstrap = AdbConsumerBootstrap(identityDir)

    /**
     * Consumer-facing readiness PROJECTION (read-only).
     *
     * Projects AdbRuntime state + identity presence. Does NOT drive the
     * AdbRuntime lifecycle.
     *
     * @return AdbBootstrapState (READY, PAIRING_REQUIRED, USER_ACTION_REQUIRED_WD,
     *         CONNECT_FAILED, ENDPOINT_NOT_FOUND, NOT_READY)
     */
    fun bootstrapState(): AdbBootstrapState = bootstrap.bootstrapState()

    /**
     * Whether the consumer has usable identity material.
     *
     * First-use pairing is required when this is false.
     */
    fun hasIdentity(): Boolean = bootstrap.hasIdentity()

    /**
     * Discover the pairing service (system "pair device with pairing code"
     * screen must be foreground).
     *
     * @param timeoutMs discovery timeout in milliseconds
     * @return AdbPairingDiscovery (0 / 1 / N endpoints)
     */
    fun discoverPairing(timeoutMs: Long = AdbConsumerBootstrap.DEFAULT_DISCOVERY_TIMEOUT_MS): AdbPairingDiscovery =
        bootstrap.discoverPairing(timeoutMs)

    /**
     * Pair against an explicit endpoint using the 6-digit code.
     *
     * This is the SINGLE place that calls AdbEngine.pair (via bootstrap).
     *
     * @param host the pairing service host
     * @param port the pairing service port
     * @param code the 6-digit pairing code
     * @return AdbPairingResultV2 (structured result)
     */
    fun pair(host: String, port: Int, code: String): AdbPairingResultV2 =
        bootstrap.pair(host, port, code)

    /**
     * Pair against an AUTO-DISCOVERED endpoint using the 6-digit code.
     *
     *   - exactly 1 endpoint  -> pair against it
     *   - 0 endpoints         -> SERVICE_NOT_FOUND
     *   - >1 endpoints        -> MULTIPLE_ENDPOINTS
     *
     * @param code the 6-digit pairing code
     * @param timeoutMs discovery timeout in milliseconds
     * @return AdbPairingResultV2 (structured result)
     */
    fun pairDiscovered(code: String, timeoutMs: Long = AdbConsumerBootstrap.DEFAULT_DISCOVERY_TIMEOUT_MS): AdbPairingResultV2 =
        bootstrap.pairDiscovered(code, timeoutMs)

    /**
     * Ensure the ADB lifecycle is READY before executing a command.
     *
     * This delegates to AdbRuntime.ensureReady (the NORMAL_COMMAND_READINESS_OWNER).
     *
     * @param onStatusLine callback for intermediate status lines
     * @return true if READY, false otherwise
     */
    fun ensureReady(onStatusLine: (String) -> Unit): Boolean =
        AdbRuntime.ensureReady(identityDir, onStatusLine) == AdbRuntimeState.READY

    /**
     * The current AdbRuntime readiness state.
     *
     * This is a READ-ONLY projection of the AdbRuntime state.
     */
    fun readinessState(): AdbRuntimeState = AdbRuntime.readinessState()
}
