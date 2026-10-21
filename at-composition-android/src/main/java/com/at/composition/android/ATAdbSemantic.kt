// AT Composition Android — Consumer-facing ADB semantic types (V4-4.1).
//
// These are PURE MAPPING projections of :adb-engine internal semantic types.
// They close the Consumer-level type boundary: consumers (:app,
// :at-cli-consumer-host, their tests) depend on THESE types, not on
// com.adb.engine.* types.
//
// RULES:
//   - No new state machine (values mirror the provider types 1:1).
//   - No new retry / readiness / pairing / error-classification logic.
//   - The honest bootstrap classification is preserved:
//       NO IDENTITY                -> PAIRING_REQUIRED
//       EXISTING IDENTITY + connect
//       failure                    -> CONNECT_FAILED (NOT auto PAIRING_REQUIRED)
//   - These types belong to :at-composition-android (Android ADB provider
//     control plane), NOT :at-engine-api (provider-neutral execution
//     semantics).
//
// MAPPING (provider -> composition):
//   AdbRuntimeState        -> ATAdbReadinessState
//   AdbBootstrapState      -> ATAdbBootstrapState
//   AdbDiscoveredService   -> ATAdbDiscoveredService
//   AdbPairingDiscovery    -> ATAdbPairingDiscovery
//   AdbPairingResultV2     -> ATAdbPairingResult

package com.at.composition.android

import com.adb.engine.AdbBootstrapState
import com.adb.engine.AdbDiscoveredService
import com.adb.engine.AdbPairingDiscovery
import com.adb.engine.AdbPairingResultV2
import com.adb.engine.AdbRuntimeState

/**
 * Consumer-facing ADB readiness state (pure projection of [AdbRuntimeState]).
 *
 * ADB lifecycle lifecycle ownership remains with AdbRuntime; this enum only
 * lets a consumer observe the state without depending on :adb-engine.
 */
enum class ATAdbReadinessState {
    DISCONNECTED,
    CONNECTING,
    READY,
    CONNECT_FAILED,
    ENDPOINT_NOT_FOUND,
    USER_ACTION_REQUIRED,
    TRANSPORT_LOST,
    RECOVERING,
    RECOVERY_FAILED,
    ;

    val isReady: Boolean get() = this == READY

    /**
     * Pure 1:1 mapping from the provider state (internal to composition).
     * No reclassification. The provider parameter type never appears in a
     * CONSUMER-facing signature.
     */
    companion object {
        internal fun from(state: AdbRuntimeState): ATAdbReadinessState = when (state) {
            AdbRuntimeState.DISCONNECTED -> DISCONNECTED
            AdbRuntimeState.CONNECTING -> CONNECTING
            AdbRuntimeState.READY -> READY
            AdbRuntimeState.CONNECT_FAILED -> CONNECT_FAILED
            AdbRuntimeState.ENDPOINT_NOT_FOUND -> ENDPOINT_NOT_FOUND
            AdbRuntimeState.USER_ACTION_REQUIRED -> USER_ACTION_REQUIRED
            AdbRuntimeState.TRANSPORT_LOST -> TRANSPORT_LOST
            AdbRuntimeState.RECOVERING -> RECOVERING
            AdbRuntimeState.RECOVERY_FAILED -> RECOVERY_FAILED
        }
    }
}

/**
 * Consumer-facing ADB bootstrap state (pure projection of [AdbBootstrapState]).
 *
 * The honest first-use classification semantics are preserved exactly:
 *   - PAIRING_REQUIRED: evidence-backed (no usable identity material).
 *   - CONNECT_FAILED: identity exists but no usable connection; does NOT
 *     assert "must pair" (offered as remediation only).
 */
enum class ATAdbBootstrapState {
    READY,
    PAIRING_REQUIRED,
    USER_ACTION_REQUIRED_WD,
    CONNECT_FAILED,
    ENDPOINT_NOT_FOUND,
    NOT_READY,
    ;

    val isReady: Boolean get() = this == READY
    /** True when the consumer should offer pairing to the user. */
    val pairingOffered: Boolean get() = this == PAIRING_REQUIRED || this == CONNECT_FAILED

    /** Pure 1:1 mapping from the provider bootstrap state (internal to composition). */
    companion object {
        internal fun from(state: AdbBootstrapState): ATAdbBootstrapState = when (state) {
            AdbBootstrapState.READY -> READY
            AdbBootstrapState.PAIRING_REQUIRED -> PAIRING_REQUIRED
            AdbBootstrapState.USER_ACTION_REQUIRED_WD -> USER_ACTION_REQUIRED_WD
            AdbBootstrapState.CONNECT_FAILED -> CONNECT_FAILED
            AdbBootstrapState.ENDPOINT_NOT_FOUND -> ENDPOINT_NOT_FOUND
            AdbBootstrapState.NOT_READY -> NOT_READY
        }
    }
}

/**
 * A discovered pairing service endpoint (pure projection of
 * [AdbDiscoveredService]).
 */
data class ATAdbDiscoveredService(
    val serviceName: String,
    val host: String?,
    val port: Int,
) {
    companion object {
        fun from(service: AdbDiscoveredService): ATAdbDiscoveredService =
            ATAdbDiscoveredService(service.serviceName, service.host, service.port)
    }
}

/**
 * Outcome of a pairing-service discovery (pure projection of
 * [AdbPairingDiscovery]).
 */
data class ATAdbPairingDiscovery(
    val endpoints: List<ATAdbDiscoveredService>,
    val unique: ATAdbDiscoveredService?,
    val serviceNotFound: Boolean,
    val multiple: Boolean,
) {
    companion object {
        fun from(discovery: AdbPairingDiscovery): ATAdbPairingDiscovery =
            ATAdbPairingDiscovery(
                endpoints = discovery.endpoints.map { ATAdbDiscoveredService.from(it) },
                unique = discovery.unique?.let { ATAdbDiscoveredService.from(it) },
                serviceNotFound = discovery.serviceNotFound,
                multiple = discovery.multiple,
            )
    }
}

/**
 * Structured result of a pairing attempt (pure projection of
 * [AdbPairingResultV2]).
 */
data class ATAdbPairingResult(
    val success: Boolean,
    val outcome: Outcome,
    val deviceGuid: String = "",
    val error: String = "",
) {
    enum class Outcome { SUCCESS, REJECTED, SERVICE_NOT_FOUND, MULTIPLE_ENDPOINTS, FAILED }

    companion object {
        fun from(result: AdbPairingResultV2): ATAdbPairingResult =
            ATAdbPairingResult(
                success = result.success,
                outcome = when (result.outcome) {
                    AdbPairingResultV2.Outcome.SUCCESS -> Outcome.SUCCESS
                    AdbPairingResultV2.Outcome.REJECTED -> Outcome.REJECTED
                    AdbPairingResultV2.Outcome.SERVICE_NOT_FOUND -> Outcome.SERVICE_NOT_FOUND
                    AdbPairingResultV2.Outcome.MULTIPLE_ENDPOINTS -> Outcome.MULTIPLE_ENDPOINTS
                    AdbPairingResultV2.Outcome.FAILED -> Outcome.FAILED
                },
                deviceGuid = result.deviceGuid,
                error = result.error,
            )
    }
}
