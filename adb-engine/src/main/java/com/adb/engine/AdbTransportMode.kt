// ADB Engine — active ADB transport selector (AT.LOCAL-TCP-1B).
//
// Minimal transport selector: expresses ONLY "which transport does the current
// execution backend use". It is NOT a copy of the readiness state machine and
// NOT a re-implementation of the native transport state. The Runtime
// ([AdbRuntime]) is the single owner of this value (Gate B).
//
// Invariant (MAX_ACTIVE_ADB_TRANSPORTS = 1): at most ONE of {Wireless, Local}
// is active at any time. The Runtime enforces this via connect/switch ordering
// (Wireless is disconnected before Local is connected, and vice versa).
package com.adb.engine

/**
 * Which ADB transport the Runtime currently routes execution through.
 *
 * - [WIRELESS] the existing post-trust Wireless ADB connection
 *              (TCP + TLS + STLS + CNXN; the v0.4.0 default).
 * - [LOCAL]    a plain-TCP localhost ADB connection to a legacy-mode adbd
 *              (CNXN + AUTH, no TLS), reached over [DEFAULT_LOCAL_ADB_PORT].
 */
enum class AdbTransportMode {
    WIRELESS,
    LOCAL,
    ;
}
