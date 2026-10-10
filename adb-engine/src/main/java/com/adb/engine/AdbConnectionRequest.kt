// ADB Engine — explicit connection input (ADB-domain).
//
// Migrated from V2 AtConnectionRequest (AT.RUNTIME-2A). ADB-domain only: a
// plain host/port/keyDir triple with no Android Context.

package com.adb.engine

/**
 * Explicit ADB connection input.
 *
 * @property host explicit endpoint (device IP, or "127.0.0.1" for on-device
 *        loopback)
 * @property port wireless connect port
 * @property keyDir directory holding the ADB identity (adbkey), e.g. the
 *        consumer's app files dir
 */
data class AdbConnectionRequest(
    val host: String,
    val port: Int,
    val keyDir: String,
)