// AT Composition Android — ADB control facade (V4-4 / V4-4.1).
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
//   - AdbEngine.pair (pairing primitive, via bootstrap)
//
// V4-4.1: The public surface returns ONLY composition-owned semantic types
// (ATAdbReadinessState / ATAdbBootstrapState / ATAdbPairingDiscovery /
// ATAdbPairingResult) or primitives. NO com.adb.engine.* type appears in a
// public signature. The implementation internals still use the provider types
// (composition is the assembly/internal integration boundary).
//
// STATE_MACHINE_DELTA = NONE (pure 1:1 mapping, no reclassification).

package com.at.composition.android

import com.adb.engine.AdbConsumerBootstrap
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState

/**
 * ADB control facade for Android consumers.
 *
 * PROJECTION_ONLY — delegates to AdbRuntime + AdbConsumerBootstrap +
 * AdbEngine.pair (via bootstrap). Does NOT own any lifecycle/recovery/pairing.
 */
class ATAndroidAdbControl internal constructor(
    /** The app-private identity directory (NOT exposed to consumers). */
    private val identityDir: String,
) {

    /** Default pairing-service discovery window (ms). */
    private val DEFAULT_DISCOVERY_TIMEOUT_MS: Long = 10_000L

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
     * Pure projection of AdbRuntime state + identity presence.
     * Does NOT drive the AdbRuntime lifecycle.
     */
    fun bootstrapState(): ATAdbBootstrapState =
        ATAdbBootstrapState.from(bootstrap.bootstrapState())

    /**
     * Whether the consumer has usable identity material.
     * First-use pairing is required when this is false.
     */
    fun hasIdentity(): Boolean = bootstrap.hasIdentity()

    /**
     * Discover the pairing service (system "pair device with pairing code"
     * screen must be foreground).
     */
    fun discoverPairing(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): ATAdbPairingDiscovery =
        ATAdbPairingDiscovery.from(bootstrap.discoverPairing(timeoutMs))

    /**
     * Pair against an explicit endpoint using the 6-digit code.
     *
     * Delegates to the bootstrap (the SINGLE place that calls AdbEngine.pair).
     */
    fun pair(host: String, port: Int, code: String): ATAdbPairingResult =
        ATAdbPairingResult.from(bootstrap.pair(host, port, code))

    /**
     * Pair against an AUTO-DISCOVERED endpoint using the 6-digit code.
     */
    fun pairDiscovered(code: String, timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): ATAdbPairingResult =
        ATAdbPairingResult.from(bootstrap.pairDiscovered(code, timeoutMs))

    /**
     * Ensure the ADB lifecycle is READY before executing a command.
     *
     * Delegates to AdbRuntime.ensureReady (the NORMAL_COMMAND_READINESS_OWNER).
     */
    fun ensureReady(onStatusLine: (String) -> Unit): Boolean =
        AdbRuntime.ensureReady(identityDir, onStatusLine) == AdbRuntimeState.READY

    /**
     * AT.V4-4.3: First-use bootstrap result (composition-owned projection).
     *
     * Maps 1:1 to [AdbRuntime.SelfGrantResult] without exposing the
     * com.adb.engine type in the public signature.
     */
    enum class FirstUseBootstrapResult {
        /** WRITE_SECURE_SETTINGS already granted; no pm grant executed. */
        SKIPPED_ALREADY_GRANTED,

        /** pm grant over embedded ADB shell; checkSelfPermission confirms granted. */
        GRANT_SUCCESS,

        /** pm grant executed but checkSelfPermission still not granted. */
        GRANT_FAILED,

        /** Runtime not initialized or ADB not READY. */
        NOT_READY,
    }

    /**
     * AT.V4-4.3: Complete the first-use bootstrap (WRITE_SECURE_SETTINGS
     * self-grant) after a successful pair + connect.
     *
     * Delegates to the composition-owned [AdbConsumerBootstrap].
     * MUST be called after [ensureReady] returns true (AdbRuntime READY).
     * Idempotent: safe to call multiple times.
     */
    fun completeFirstUseBootstrap(): FirstUseBootstrapResult {
        val r = bootstrap.completeFirstUseBootstrap()
        return when (r) {
            AdbRuntime.SelfGrantResult.SKIPPED_ALREADY_GRANTED -> FirstUseBootstrapResult.SKIPPED_ALREADY_GRANTED
            AdbRuntime.SelfGrantResult.GRANT_SUCCESS -> FirstUseBootstrapResult.GRANT_SUCCESS
            AdbRuntime.SelfGrantResult.GRANT_FAILED -> FirstUseBootstrapResult.GRANT_FAILED
            AdbRuntime.SelfGrantResult.NOT_READY -> FirstUseBootstrapResult.NOT_READY
        }
    }

    /**
     * The current AdbRuntime readiness state (pure projection).
     */
    fun readinessState(): ATAdbReadinessState =
        ATAdbReadinessState.from(AdbRuntime.readinessState())

    // ---- Product glue observation (NOT a normal-consumer surface) ---------
    //
    // The product's AdbCliStatusHub (a read-only AdbRuntime lifecycle observer)
    // needs the provider listener API. Exposing it here keeps :app free of a
    // direct :adb-engine dependency for the HUB. The hub still observes the
    // SINGLE AdbRuntime (no second observer owner / no lifecycle ownership).

    /**
     * Register a read-only lifecycle listener over the composition-owned
     * [ATAdbReadinessState] projection (delegates to the single AdbRuntime).
     *
     * Returns a composition-owned [ATAdbLifecycleListenerHandle] — consumers
     * never name the provider handle type.
     */
    fun addLifecycleListener(onTransition: (old: ATAdbReadinessState, new: ATAdbReadinessState) -> Unit): ATAdbLifecycleListenerHandle {
        val providerHandle = AdbRuntime.addLifecycleListener { old, new ->
            onTransition(ATAdbReadinessState.from(old), ATAdbReadinessState.from(new))
        }
        return ATAdbLifecycleListenerHandle.create(providerHandle)
    }

    /** Remove a previously registered read-only listener (delegates to the handle). */
    fun removeLifecycleListener(handle: ATAdbLifecycleListenerHandle) = handle.remove()

    // ---- Test-only probes (delegation to the single AdbRuntime) -----------
    //
    // These let product tests assert capability / initialization WITHOUT
    // importing the provider type. All are READ-ONLY (no lifecycle effect)
    // and delegate to the single AdbRuntime (no second owner).

    /** Whether the process can write Settings.Global (auto-WD capability). */
    fun canWriteSecureSettings(): Boolean = AdbRuntime.canWriteSecureSettings()

    /** Whether the process's single AdbRuntime has been initialized. */
    fun isRuntimeInitialized(): Boolean = AdbRuntime.isInitialized()
}

/**
 * Composition-owned lifecycle listener handle.
 *
 * PROJECTION_ONLY: [remove] unregisters the listener from the single
 * AdbRuntime (via the provider handle). The provider handle type is held
 * PRIVATELY — consumers never see it.
 */
class ATAdbLifecycleListenerHandle private constructor(
    private val providerHandle: Any,
) {
    fun remove() = providerHandle.javaClass.getMethod("remove").invoke(providerHandle)

    companion object {
        internal fun create(providerHandle: AdbRuntime.AdbRuntimeListenerHandle): ATAdbLifecycleListenerHandle =
            ATAdbLifecycleListenerHandle(providerHandle)
    }
}
