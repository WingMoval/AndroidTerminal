// AT.CLI-CONSUMER-0 — CLI-owned pairing semantic types (V4-4.1).
//
// These are MINIMAL CLI-owned semantic types for the host-provided
// [ATCliExecution] pairing surface. They keep :at-cli free of BOTH
// :adb-engine (provider internals) AND :at-composition-android (composition
// semantic types): the host maps its composition pairing result into these
// CLI types, and AdbCliBridgeServer renders them to wire text at the CLI
// boundary.
//
// WIRE_PROTOCOL_DELTA = NONE: the bridge still emits the same T_PAIR /
// T_OUT / T_EXIT frames with the same human-readable text; only the Kotlin
// typing of the result changes (String -> structured semantic result).

package com.at.cli

/**
 * CLI pairing readiness classification (host-reported).
 *
 * Mirrors the honest bootstrap classification semantics:
 *   - READY: identity present + connection ready.
 *   - PAIRING_REQUIRED: no usable identity (evidence-backed).
 *   - CONNECT_FAILED: identity present but no usable connection (does NOT
 *     assert "must pair").
 *   - NOT_READY: runtime not initialized / unknown.
 */
enum class ATCliPairingState {
    READY,
    PAIRING_REQUIRED,
    CONNECT_FAILED,
    NOT_READY,
    ;

    val isReady: Boolean get() = this == READY
}

/**
 * Structured CLI pairing result (host-reported).
 *
 * The host (product / consumer host) maps its composition semantic pairing
 * result into this type; the bridge renders [outcome] + [error] into the
 * same human-readable text as before.
 */
data class ATCliPairingResult(
    val success: Boolean,
    val outcome: Outcome,
    val deviceGuid: String = "",
    val error: String = "",
) {
    enum class Outcome { SUCCESS, REJECTED, SERVICE_NOT_FOUND, MULTIPLE_ENDPOINTS, FAILED }

    companion object {
        val FAILED_RESULT = ATCliPairingResult(
            success = false,
            outcome = Outcome.FAILED,
            error = "pairing failed",
        )
    }
}
