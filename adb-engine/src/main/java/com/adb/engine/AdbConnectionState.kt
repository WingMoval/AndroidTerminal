// ADB Engine — connection state projection.
//
// Canonical V1 §7.2: ADB Engine owns ADB connection lifecycle. This is a
// factual projection of the native ADB connection state, NOT an independent
// state machine.

package com.adb.engine

/**
 * Factual view of the ADB connection, projected from native
 * `adb::ConnectionState`. This is a *projection*: the ADB Engine never
 * advances connection state itself.
 */
enum class AdbConnectionState(val nativeOrdinal: Int) {
    DISCONNECTED(0),
    CONNECTING(1),
    WAITING_STLS(2),
    TLS_NEGOTIATING(3),
    WAITING_POST_TLS_CNXN(4),
    ONLINE(5),
    CLOSING(6),
    OFFLINE(7);

    val isOnline: Boolean get() = this == ONLINE

    companion object {
        /** Map a native ordinal; unknown values degrade to DISCONNECTED. */
        fun fromNative(ordinal: Int): AdbConnectionState =
            entries.firstOrNull { it.nativeOrdinal == ordinal } ?: DISCONNECTED
    }
}