// ADB Engine — endpoint + source + discovered service (ADB-domain).
//
// Migrated from V2 AtEndpoint / AtEndpointSource / AtDiscoveredService.
// ADB-domain only: endpoint selection / caching / discovery facts.

package com.adb.engine

/** Where an [AdbEndpoint] came from (drives lifecycle policy). */
enum class AdbEndpointSource {
    /** Caller supplied the endpoint explicitly (authoritative). */
    EXPLICIT,

    /** Discovered via mDNS; connect target is the on-device loopback. */
    MDNS_LOOPBACK,

    /** Discovered via mDNS; connect target is the resolved WLAN address. */
    MDNS_RESOLVED,
}

/**
 * ADB Engine endpoint (connect target).
 */
data class AdbEndpoint(
    val host: String,
    val port: Int,
    val source: AdbEndpointSource,
    val discoveredAt: Long,
    val serviceName: String? = null,
) {
    /** A loopback endpoint is process-local to the device. */
    val isLoopback: Boolean
        get() = host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"
}

/**
 * A single discovered Wireless ADB connect service (`_adb-tls-connect._tcp.`).
 */
data class AdbDiscoveredService(
    val serviceName: String,
    val host: String?,
    val port: Int,
)