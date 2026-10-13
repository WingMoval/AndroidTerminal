// ADB Engine — ADB-domain connection lifecycle state.
//
// AT.V3-2.3: ADB lifecycle ownership is ADB-domain (Canonical V1 §7.2), NOT AT
// execution. This is an ADB Engine lifecycle projection — it is NOT a copy of
// the native transport state (that is AdbConnectionState) and it is NOT an AT
// execution outcome. It answers "is the ADB connection usable for AT execution,
// and if not, why / what is happening".
//
// Mirrors the V2 AtReadinessState lifecycle vocabulary (AT.RUNTIME-2A/2B/2D)
// re-owned by the ADB domain. Pairing states are deliberately OUT OF SCOPE for
// the post-trust lifecycle closure and are not emitted by AdbRuntime.

package com.adb.engine

/**
 * ADB Engine connection lifecycle state (post-trust; pairing excluded).
 *
 * - [READY]            a usable ADB connection is established (ONLINE).
 * - [DISCONNECTED]     no connection (initial, or after explicit disconnect).
 * - [CONNECTING]       a connect / connectAuto attempt is in flight.
 * - [CONNECT_FAILED]   an explicit/auto connect attempt failed (bounded).
 * - [ENDPOINT_NOT_FOUND] readiness is not DISABLED but discovery found nothing.
 * - [USER_ACTION_REQUIRED] readiness is confirmed DISABLED (Wireless Debugging
 *                          is OFF); a human must enable it. No reconnect storm.
 * - [TRANSPORT_LOST]   a previously READY connection was lost; a recovery cycle
 *                      may follow (operation-driven detection).
 * - [RECOVERING]       a bounded connection-recovery cycle is in flight.
 * - [RECOVERY_FAILED]  recovery attempts were exhausted (bounded).
 */
enum class AdbRuntimeState {
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
}
