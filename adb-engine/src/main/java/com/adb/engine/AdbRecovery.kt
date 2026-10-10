// ADB Engine — endpoint store + recovery policy + connection intent.
//
// Migrated from V2 EndpointStore / RecoveryPolicy / ConnectionIntent.
// ADB-domain only: endpoint caching and recovery policy are ADB Engine
// capabilities (Canonical V1 §7.2). Recovery policy is bounded; no replay.

package com.adb.engine

import android.util.Log

/** Runtime-internal connection intent (how the connection was established). */
sealed class AdbConnectionIntent {
    /** No connection established (or explicitly disconnected). */
    object None : AdbConnectionIntent()

    /** Auto-established connection (recover via rediscovery). */
    data class Auto(val keyDir: String) : AdbConnectionIntent()

    /** Explicitly-established connection (recover same endpoint; no mDNS fallback). */
    data class Explicit(
        val host: String,
        val port: Int,
        val keyDir: String,
    ) : AdbConnectionIntent()
}

/** Bounded recovery policy. No infinite retry. */
data class AdbRecoveryPolicy(
    val maxAttempts: Int,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
    val discoveryTimeoutMs: Long,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be > 0 (bounded)" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must be >= 0" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must be >= initialBackoffMs" }
        require(discoveryTimeoutMs > 0) { "discoveryTimeoutMs must be > 0" }
    }

    /** Backoff before attempt [attempt] (1-based). Capped at [maxBackoffMs]. */
    fun backoffBefore(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val raw = initialBackoffMs * (attempt - 1)
        return raw.coerceAtMost(maxBackoffMs)
    }

    companion object {
        /** Production policy: small and bounded. */
        val production: AdbRecoveryPolicy = AdbRecoveryPolicy(
            maxAttempts = 3,
            initialBackoffMs = 1_000L,
            maxBackoffMs = 3_000L,
            discoveryTimeoutMs = 10_000L,
        )
    }
}

/** Process-local ADB endpoint store with bounded TTL. */
class AdbEndpointStore(
    private val discoveryProvider: () -> AdbDiscoveryBackend?,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val scanTimeoutMs: Long = SCAN_TIMEOUT_MS,
) {
    private companion object {
        const val TAG = "AdbEndpointStore"
        const val CACHE_TTL_MS: Long = 60_000L
        const val SCAN_TIMEOUT_MS: Long = 10_000L
    }

    @Volatile
    private var current: AdbEndpoint? = null

    fun current(): AdbEndpoint? {
        val e = current ?: return null
        if (isExpired(e)) {
            current = null
            return null
        }
        return e
    }

    fun hasCurrent(): Boolean = current() != null

    fun set(host: String, port: Int, source: AdbEndpointSource) {
        current = AdbEndpoint(
            host = host,
            port = port,
            source = source,
            discoveredAt = System.currentTimeMillis(),
        )
        Log.i(TAG, "endpoint set $source ${host}:$port")
    }

    fun setExplicit(host: String, port: Int) = set(host, port, AdbEndpointSource.EXPLICIT)

    fun rediscover(): List<AdbEndpoint> = rediscover(scanTimeoutMs)

    fun rediscover(timeoutMs: Long): List<AdbEndpoint> {
        invalidate()
        val backend = discoveryProvider()
        if (backend == null) {
            Log.w(TAG, "rediscover: discovery backend not initialized")
            return emptyList()
        }
        val services = backend.scan(timeoutMs)
        val now = System.currentTimeMillis()
        val endpoints = services.map { svc ->
            AdbEndpoint(
                host = svc.host ?: "127.0.0.1",
                port = svc.port,
                source = if (svc.isResolvedLoopback()) AdbEndpointSource.MDNS_LOOPBACK
                else AdbEndpointSource.MDNS_RESOLVED,
                discoveredAt = now,
                serviceName = svc.serviceName,
            )
        }
        if (endpoints.isNotEmpty()) {
            current = endpoints.first()
        } else {
            Log.w(TAG, "rediscover: no usable endpoint")
        }
        return endpoints
    }

    fun invalidate() {
        val e = current
        current = null
        if (e != null) Log.i(TAG, "endpoint invalidated: ${e.host}:${e.port}")
    }

    fun clear() = invalidate()

    private fun isExpired(e: AdbEndpoint): Boolean =
        (System.currentTimeMillis() - e.discoveredAt) > cacheTtlMs

    private fun AdbDiscoveredService.isResolvedLoopback(): Boolean {
        val h = host
        return h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1"
    }
}