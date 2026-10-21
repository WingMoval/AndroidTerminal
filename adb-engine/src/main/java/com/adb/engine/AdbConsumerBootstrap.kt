// AT.CONSUMER-BOOTSTRAP-0 — Reusable ADB Consumer Bootstrap boundary.
//
// AdbRuntime is the UNIQUE ADB lifecycle owner (connectAuto / ensureReady /
// readiness / recovery). AdbEngine owns the pairing PROTOCOL primitive
// (pair / discovery). Neither knows "consumer bootstrap" semantics:
//
//   - "am I READY, or do I need to pair, or is WD off?"
//   - "discover the pairing service"
//   - "pair with a 6-digit code using MY app-private identity"
//   - "pair against an auto-discovered endpoint"
//
// This class is that consumer-facing projection + orchestration. It is:
//   - placed ADJACENT TO AdbRuntime inside :adb-engine (ADB subsystem semantics,
//     NOT CLI semantics, NOT backend-neutral AT Engine semantics) — no new module.
//   - ONE reusable concrete implementation parameterized by the consumer's
//     app-private [identityDir]. Both :app and :at-cli-consumer-host (and any
//     future consumer) instantiate the SAME implementation — the pairing
//     implementation count is exactly 1.
//   - the ONLY place that calls AdbEngine.pair. PairActivity / AtCliMain
//     `adb pair` / consumer-host acceptance tests all route through it; none
//     call AdbEngine.pair directly.
//
// It does NOT introduce a second lifecycle owner / state machine.
// [bootstrapState] is a READ-ONLY consumer PROJECTION of AdbRuntime state +
// identity presence + (lazily) discovery; it never drives the AdbRuntime
// lifecycle itself. AdbRuntime remains the single owner.
//
// CONSUMER_BOOTSTRAP_API_STATUS = PROVISIONAL / DRAFT (NOT a final AT API
// freeze; AT_CONTRACT_STATUS stays DRAFT).

package com.adb.engine

import java.io.File

// ---------------------------------------------------------------------------
// Structured result types (machine-readable; no String-only returns)
// ---------------------------------------------------------------------------

/**
 * Consumer-facing ADB readiness PROJECTION (read-only; NOT a lifecycle owner).
 *
 * This is a consumer readiness state, not a second AdbRuntime state machine.
 * It projects AdbRuntime state + identity presence so a consumer can decide
 * "can I use ADB now, or do I need to pair, or is WD off?" without knowing
 * pairing / TLS / identity internals.
 */
enum class AdbBootstrapState {
    /** A usable ADB connection is established (AdbRuntime READY). */
    READY,

    /**
     * No usable consumer identity exists (the app-private key material is
     * missing), so first-use pairing is required to create one. This is the
     * HONEST first-use signal: there is evidence (no key material), not a guess.
     */
    PAIRING_REQUIRED,

    /**
     * Wireless Debugging is confirmed OFF. A human must enable it. This is NOT
     * pairing — it is an explicit, evidence-backed readiness classification.
     */
    USER_ACTION_REQUIRED_WD,

    /**
     * Wireless Debugging is on and identity exists, but no usable connection
     * could be established. This is a CONSERVATIVE classification: it does NOT
     * assert "you must pair" (a generic connect Boolean failure could be a
     * transient transport issue, not an auth rejection — see C4). Pairing MAY
     * be the cause, but it is not proven; the consumer should surface a generic
     * "not connected" and offer pairing as a remediation, not assert it.
     */
    CONNECT_FAILED,

    /** No ADB endpoint was discovered (WD on, identity present). */
    ENDPOINT_NOT_FOUND,

    /** AdbRuntime is not initialized / state unknown. */
    NOT_READY,
    ;

    val isReady: Boolean get() = this == READY
    /** True when the consumer should offer pairing to the user. */
    val pairingOffered: Boolean get() = this == PAIRING_REQUIRED || this == CONNECT_FAILED
}

/**
 * Outcome of a pairing-service discovery. Structured so the consumer can
 * distinguish "service not open" from "multiple endpoints" WITHOUT guessing.
 */
data class AdbPairingDiscovery(
    /** Discovered pairing endpoints (host may be null for loopback services). */
    val endpoints: List<AdbDiscoveredService>,
    /** Convenience: the single endpoint when exactly one was found, else null. */
    val unique: AdbDiscoveredService?,
    /** True when no pairing service was found (system pairing screen not open). */
    val serviceNotFound: Boolean,
    /** True when more than one pairing endpoint was found. */
    val multiple: Boolean,
) {
    companion object {
        fun from(endpoints: List<AdbDiscoveredService>): AdbPairingDiscovery =
            AdbPairingDiscovery(
                endpoints = endpoints,
                unique = if (endpoints.size == 1) endpoints.first() else null,
                serviceNotFound = endpoints.isEmpty(),
                multiple = endpoints.size > 1,
            )
    }
}

/**
 * Structured result of a pairing attempt (NOT a Boolean). Carries the
 * machine-readable classification, the device GUID on success, and the
 * underlying error text on failure.
 */
data class AdbPairingResultV2(
    val success: Boolean,
    /**
     * Classification of the outcome:
     *   - SUCCESS
     *   - REJECTED          (pairing service rejected the code / auth failed)
     *   - SERVICE_NOT_FOUND (no endpoint to pair against)
     *   - MULTIPLE_ENDPOINTS(discovery found >1; caller must pick explicitly)
     *   - FAILED            (other failure)
     */
    val outcome: Outcome,
    val deviceGuid: String = "",
    val error: String = "",
    /** The endpoint that was (or should be) used; null when not applicable. */
    val endpoint: AdbDiscoveredService? = null,
) {
    enum class Outcome { SUCCESS, REJECTED, SERVICE_NOT_FOUND, MULTIPLE_ENDPOINTS, FAILED }
}

// ---------------------------------------------------------------------------
// The reusable bootstrap boundary
// ---------------------------------------------------------------------------

/**
 * Reusable ADB Consumer Bootstrap. ONE concrete implementation; parameterized
 * by the consumer's app-private [identityDir].
 *
 * A consumer (AndroidTerminal product UI, the shell-visible `adb pair`, or any
 * future consumer) uses this to complete first-use pairing without knowing
 * AdbEngine.pair / SPAKE2 / pairing TLS / certificate / transport / recovery:
 *
 *   bootstrap.bootstrapState()                 // READY? PAIRING_REQUIRED? WD off?
 *   if (state == PAIRING_REQUIRED) {
 *       val d = bootstrap.discoverPairing()    // find the pairing service
 *       // consumer obtains the 6-digit code from ITS user, then:
 *       val r = bootstrap.pairDiscovered(code) // or bootstrap.pair(host, port, code)
 *   }
 *
 * [identityDir] is the consumer's OWN app-private directory (never another app's
 * path, never a copied key). Pairing via [pair] / [pairDiscovered] triggers the
 * existing AdbEngine identity primitive to create the key material if absent.
 */
class AdbConsumerBootstrap(private val identityDir: String) {

    companion object {
        private const val TAG = "AdbConsumerBootstrap"
        /** Default discovery window (ms) — the pairing screen must be open. */
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000L

        /**
         * Pure filesystem check: does the given identity dir hold usable
         * identity material (adbkey / adbkey.pub)?
         *
         * V4-4.1: static helper so a test can evaluate the identity-presence
         * predicate against a CONTROLLED directory without constructing a
         * bootstrap bound to a real consumer identity (no second bootstrap
         * state machine, no runtime initialization).
         */
        fun hasIdentityForDir(identityDir: String): Boolean =
            File(identityDir, "adbkey").exists() || File(identityDir, "adbkey.pub").exists()

        /**
         * Pure classification: the [AdbBootstrapState] that the consumer
         * bootstrap bound to [identityDir] would report, evaluated against the
         * CURRENT AdbRuntime state (no runtime side effects, no re-init).
         *
         * V4-4.1: static helper (same conservative C4 rules as
         * [bootstrapState]) so a test can verify the honest first-use
         * classification against a controlled (e.g. empty) directory.
         */
        fun bootstrapStateForDir(identityDir: String): AdbBootstrapState {
            if (!AdbRuntime.isInitialized()) return AdbBootstrapState.NOT_READY
            val state = AdbRuntime.readinessState()
            return when {
                state.isReady -> AdbBootstrapState.READY
                state == AdbRuntimeState.USER_ACTION_REQUIRED ->
                    AdbBootstrapState.USER_ACTION_REQUIRED_WD
                state == AdbRuntimeState.ENDPOINT_NOT_FOUND ->
                    AdbBootstrapState.ENDPOINT_NOT_FOUND
                !hasIdentityForDir(identityDir) -> AdbBootstrapState.PAIRING_REQUIRED
                else -> AdbBootstrapState.CONNECT_FAILED
            }
        }

        /**
         * V4-4.1: pure delegation to the single [AdbRuntime].discoverPairing
         * (NO second discovery owner; the Runtime remains the discovery
         * owner). Exposed here so a test can exercise discovery through the
         * bootstrap's semantic surface without importing AdbRuntime directly.
         */
        fun discoverPairingForTest(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): List<AdbDiscoveredService> =
            AdbRuntime.discoverPairing(timeoutMs)

        /**
         * V4-4.1: test-only capability probe — delegates to the single
         * [AdbRuntime].canWriteSecureSettings (READ-ONLY permission check;
         * enables nothing). Lets a product test assert auto-WD capability
         * without importing AdbRuntime from the consumer test scope.
         */
        fun canWriteSecureSettingsForTest(): Boolean =
            AdbRuntime.canWriteSecureSettings()

        /**
         * V4-4.1: test-only probe — whether the process's single AdbRuntime
         * has been initialized (ownership gate: SECOND_ADB_RUNTIME = NO).
         */
        fun isRuntimeInitializedForTest(): Boolean =
            AdbRuntime.isInitialized()
    }

    /** The consumer's app-private identity directory. */
    val identityDirPath: String get() = identityDir

    /**
     * Whether the consumer has usable identity material (adbkey / adbkey.pub).
     * First-use pairing is required when this is false (HONEST signal — see
     * [AdbBootstrapState.PAIRING_REQUIRED]).
     */
    fun hasIdentity(): Boolean =
        File(identityDir, "adbkey").exists() || File(identityDir, "adbkey.pub").exists()

    /**
     * Consumer-facing readiness PROJECTION (read-only). Projects AdbRuntime
     * state + identity presence. Does NOT drive the AdbRuntime lifecycle.
     *
     * Conservative classification (C4):
     *   - READY            when AdbRuntime is READY.
     *   - USER_ACTION_REQUIRED_WD when WD is confirmed DISABLED.
     *   - PAIRING_REQUIRED when there is NO usable identity (evidence-backed).
     *   - ENDPOINT_NOT_FOUND when AdbRuntime says so.
     *   - CONNECT_FAILED   when identity exists but no usable connection (does
     *     NOT assert "must pair" — a Boolean connect failure is not auth
     *     evidence; pairing is offered as a remediation, not asserted).
     *   - NOT_READY        otherwise / runtime not initialized.
     */
    fun bootstrapState(): AdbBootstrapState {
        if (!AdbRuntime.isInitialized()) return AdbBootstrapState.NOT_READY
        val state = AdbRuntime.readinessState()
        return when {
            state.isReady -> AdbBootstrapState.READY
            state == AdbRuntimeState.USER_ACTION_REQUIRED ->
                AdbBootstrapState.USER_ACTION_REQUIRED_WD
            state == AdbRuntimeState.ENDPOINT_NOT_FOUND ->
                AdbBootstrapState.ENDPOINT_NOT_FOUND
            !hasIdentity() -> AdbBootstrapState.PAIRING_REQUIRED
            else -> AdbBootstrapState.CONNECT_FAILED
        }
    }

    /**
     * Discover the pairing service (system "pair device with pairing code"
     * screen must be foreground). Wraps AdbRuntime.discoverPairing; returns a
     * structured [AdbPairingDiscovery] (0 / 1 / N endpoints).
     */
    fun discoverPairing(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): AdbPairingDiscovery =
        AdbPairingDiscovery.from(AdbRuntime.discoverPairing(timeoutMs))

    /**
     * Pair against an explicit endpoint using the 6-digit [code]. This is the
     * SINGLE place (in the whole bootstrap surface) that calls AdbEngine.pair.
     * On success the consumer's identity key material is created/updated in
     * [identityDir] and the device becomes trusted.
     */
    fun pair(host: String, port: Int, code: String): AdbPairingResultV2 {
        val r = AdbEngine.pair(host, port, code, identityDir)
        return AdbPairingResultV2(
            success = r.success,
            outcome = if (r.success) AdbPairingResultV2.Outcome.SUCCESS else AdbPairingResultV2.Outcome.REJECTED,
            deviceGuid = r.deviceGuid,
            error = r.error,
        )
    }

    /**
     * Pair against an AUTO-DISCOVERED endpoint using the 6-digit [code].
     *
     *   - exactly 1 endpoint  -> pair against it (consumer only supplies code).
     *   - 0 endpoints         -> SERVICE_NOT_FOUND (pairing screen not open).
     *   - >1 endpoints        -> MULTIPLE_ENDPOINTS (caller must pick via [pair]).
     *
     * Never guesses among multiple endpoints.
     */
    fun pairDiscovered(code: String, timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): AdbPairingResultV2 {
        val d = discoverPairing(timeoutMs)
        return when {
            d.serviceNotFound -> AdbPairingResultV2(
                success = false,
                outcome = AdbPairingResultV2.Outcome.SERVICE_NOT_FOUND,
                error = "No wireless-debugging pairing service discovered (system pairing screen must be open)",
            )
            d.multiple -> AdbPairingResultV2(
                success = false,
                outcome = AdbPairingResultV2.Outcome.MULTIPLE_ENDPOINTS,
                error = "${d.endpoints.size} pairing endpoints found; use pair(host, port, code) to choose",
            )
            else -> {
                val ep = d.unique!!
                val host = ep.host ?: "127.0.0.1"
                val r = pair(host, ep.port, code)
                r.copy(endpoint = ep)
            }
        }
    }

    /**
     * AT.V4-4.3: Complete the first-use bootstrap after a successful pair +
     * connect (AdbRuntime READY).
     *
     * This is the FIRST-USE PRODUCT BOOTSTRAP completion point. It runs the
     * WRITE_SECURE_SETTINGS self-grant (idempotent) so that subsequent
     * WD-OFF scenarios can auto-enable Wireless Debugging without user action.
     *
     * MUST be called:
     *   1. AFTER [pairDiscovered] or [pair] returns success=true, AND
     *   2. AFTER the caller has driven [AdbRuntime.ensureReady] to READY.
     *
     * The method is IDEMPOTENT: if WRITE_SECURE_SETTINGS is already granted
     * (e.g. app restart, or a previous successful bootstrap), it skips the
     * pm grant and returns [AdbRuntime.SelfGrantResult.SKIPPED_ALREADY_GRANTED].
     *
     * Failure semantics: a self-grant failure does NOT invalidate the pairing.
     * The identity is NOT deleted, rotated, or re-paired. The caller should
     * treat this as AUTO_WD_BOOTSTRAP = FAILED while PAIRING = SUCCESS.
     *
     * @return the [AdbRuntime.SelfGrantResult] classification.
     */
    fun completeFirstUseBootstrap(): AdbRuntime.SelfGrantResult {
        if (!AdbRuntime.isInitialized()) {
            return AdbRuntime.SelfGrantResult.NOT_READY
        }
        if (AdbRuntime.readinessState() != AdbRuntimeState.READY) {
            return AdbRuntime.SelfGrantResult.NOT_READY
        }
        return AdbRuntime.selfGrantWriteSecureSettings()
    }
}
