// ADB Engine — connection recovery coordinator (ADB-domain).
//
// Migrated + re-wired from V2 RecoveryCoordinator. The V2 version depended on
// AT's `TerminalRuntime` (the exact coupling AT.V3-1 must break). In the ADB
// Engine this coordinator drives recovery through [AdbEngine] directly.
//
// Semantics preserved: bounded attempts, backoff, AUTO (rediscover +
// multi-candidate) vs EXPLICIT (same endpoint, no mDNS fallback), and
// **no command replay** — recovery only restores the CONNECTION; it never
// re-executes the command that failed.

package com.adb.engine

import android.util.Log

/**
 * Internal ADB recovery orchestrator.
 *
 * Runs the bounded recovery state machine once a transport loss is detected
 * by the caller. It holds NO public API of its own; the ADB Runtime facade
 * delegates to it. Not thread-safe on its own.
 */
class AdbRecoveryCoordinator(
    private val endpointStore: AdbEndpointStore,
    private val discoveryProvider: () -> AdbDiscoveryBackend?,
    private val readinessProvider: () -> AdbWirelessDebuggingState,
    private val policyProvider: () -> AdbRecoveryPolicy = { AdbRecoveryPolicy.production },
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
) {
    private companion object {
        const val TAG = "AdbRecovery"
    }

    /**
     * Run a bounded recovery cycle for the given [intent].
     *
     * @return true when the ADB connection is restored (READY), false otherwise.
     */
    fun recover(intent: AdbConnectionIntent): Boolean {
        val policy = policyProvider()

        // 1. Tear down the stale ADB session so a fresh connect starts clean.
        try {
            AdbEngine.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "recovery: stale disconnect threw (ignored)", t)
        }
        // 2. Invalidate the stale endpoint: never trust the cache.
        endpointStore.invalidate()

        // 3. Readiness gate (detect only). DISABLED stops immediately.
        val wd = readinessProvider()
        if (wd == AdbWirelessDebuggingState.DISABLED) {
            Log.i(TAG, "recovery: Wireless Debugging DISABLED -> no recovery")
            return false
        }

        val keyDir = when (intent) {
            is AdbConnectionIntent.Auto -> intent.keyDir
            is AdbConnectionIntent.Explicit -> intent.keyDir
            is AdbConnectionIntent.None -> {
                Log.w(TAG, "recovery: no connection intent -> RECOVERY_FAILED")
                return false
            }
        }

        var lastFailure = "no attempt"
        for (attempt in 1..policy.maxAttempts) {
            val backoff = policy.backoffBefore(attempt)
            if (backoff > 0) {
                Log.i(TAG, "recovery: backoff ${backoff}ms before attempt $attempt")
                try {
                    sleeper(backoff)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            Log.i(TAG, "RECOVERY_ATTEMPT #$attempt (of ${policy.maxAttempts})")

            val ok = when (intent) {
                is AdbConnectionIntent.Auto -> recoverAuto(keyDir, policy)
                is AdbConnectionIntent.Explicit -> {
                    if (tryConnect(intent.host, intent.port, intent.keyDir)) {
                        endpointStore.set(intent.host, intent.port, AdbEndpointSource.EXPLICIT)
                        true
                    } else {
                        lastFailure = "explicit endpoint ${intent.host}:${intent.port} failed"
                        false
                    }
                }
                else -> false
            }
            if (ok) {
                Log.i(TAG, "RECOVERY_SUCCEEDED (attempt $attempt)")
                return true
            }
            Log.w(TAG, "RECOVERY_ATTEMPT_FAILED #$attempt: $lastFailure")
        }

        Log.w(TAG, "recovery exhausted after ${policy.maxAttempts} attempts: $lastFailure")
        return false
    }

    private fun recoverAuto(keyDir: String, policy: AdbRecoveryPolicy): Boolean {
        val candidates = endpointStore.rediscover(policy.discoveryTimeoutMs)
        if (candidates.isEmpty()) {
            Log.w(TAG, "recovery: no endpoint discovered")
            return false
        }
        for (candidate in candidates) {
            if (tryConnect("127.0.0.1", candidate.port, keyDir)) {
                endpointStore.set("127.0.0.1", candidate.port, AdbEndpointSource.MDNS_LOOPBACK)
                return true
            }
            if (!candidate.isLoopback && candidate.host != "127.0.0.1" &&
                tryConnect(candidate.host, candidate.port, keyDir)
            ) {
                endpointStore.set(candidate.host, candidate.port, AdbEndpointSource.MDNS_RESOLVED)
                return true
            }
        }
        return false
    }

    /** Thin non-throwing connect through the ADB Engine facade. */
    private fun tryConnect(host: String, port: Int, keyDir: String): Boolean {
        return try {
            AdbEngine.connect(host, port, keyDir)
        } catch (t: Throwable) {
            Log.e(TAG, "recovery connect threw for $host:$port", t)
            false
        }
    }
}