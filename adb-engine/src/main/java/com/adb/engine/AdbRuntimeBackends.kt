// ADB Engine — platform backend seams (ADB-domain).
//
// Migrated from V2 DiscoveryBackend / WirelessDebuggingReadinessBackend.
// ADB-domain only: discovery + WD readiness are ADB Engine capabilities
// (Canonical V1 §7.2), independent of AT Engine / Terminal.

package com.adb.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Log

/** Wireless Debugging readiness state (platform fact). */
enum class AdbWirelessDebuggingState {
    /** Wireless Debugging is confirmed ON. */
    ENABLED,

    /** Wireless Debugging is confirmed OFF. */
    DISABLED,

    /** State could not be determined reliably (OEM/platform limitation). */
    UNKNOWN,
}

/**
 * Platform backend for Wireless ADB connect-service discovery.
 *
 * Exposes only [AdbDiscoveredService]; never NsdManager / NsdServiceInfo /
 * Context. Only the connect service `_adb-tls-connect._tcp.` is discovered
 * (pairing service deliberately out of scope for this primitive).
 */
interface AdbDiscoveryBackend {
    /** Run a bounded mDNS scan; returns all usable discovered services. */
    fun scan(timeoutMs: Long): List<AdbDiscoveredService>
}

/** Android NsdManager implementation of [AdbDiscoveryBackend]. */
class AdbAndroidNsdDiscoveryBackend(context: Context) : AdbDiscoveryBackend {

    private companion object {
        const val TAG = "AdbNsdDiscovery"
        const val SERVICE_TYPE = "_adb-tls-connect._tcp."
    }

    private val appContext = context.applicationContext

    // NsdManager resolve results are delivered on a handler thread; guard the
    // shared mutable list.
    private val found = mutableListOf<AdbDiscoveredService>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun scan(timeoutMs: Long): List<AdbDiscoveredService> {
        synchronized(found) { found.clear() }

        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager not available")
            return emptyList()
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // IMPORTANT (V2 AT.RUNTIME-2B Oracle): onServiceFound gives an
                // UNRESOLVED service (host=null / port=0). resolveService() is
                // required to obtain the real host/port. Reading the raw fields
                // directly (as in the first V3 port) always yields port==0 and
                // the candidate was filtered out -> ENDPOINT_NOT_FOUND.
                Log.i(TAG, "onServiceFound: ${serviceInfo.serviceName}")
                resolveService(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.w(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "onStartDiscoveryFailed: $serviceType code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "onStopDiscoveryFailed: $serviceType code=$errorCode")
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        } catch (t: Throwable) {
            Log.e(TAG, "discoverServices threw", t)
        }

        // Collect + resolve candidates until the deadline (V2 Oracle: do NOT
        // stop at the first hit — mDNS may carry stale + fresh instances, and
        // multi-candidate selection needs all of them).
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        stopSafe(manager)
        val result = synchronized(found) { found.toList() }
        // Order: loopback-resolved first (on-device consumer's preferred path),
        // then the rest. De-duplication happened at resolve time.
        val ordered = result.sortedByDescending { it.isLoopback() }
        if (ordered.isEmpty()) {
            Log.w(TAG, "scan: no connect service found within ${timeoutMs}ms")
        } else {
            Log.i(TAG, "scan: ${ordered.size} candidate(s): " +
                ordered.joinToString { "${it.serviceName}:${it.port}(${it.host})" })
        }
        return ordered
    }

    private fun stopSafe(manager: NsdManager) {
        try {
            discoveryListener?.let { manager.stopServiceDiscovery(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "stopServiceDiscovery failed", t)
        }
        discoveryListener = null
    }

    private fun resolveService(manager: NsdManager, service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "resolve failed: ${serviceInfo.serviceName} code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val addr = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                Log.i(TAG, "onServiceResolved: name=${serviceInfo.serviceName} host=$addr port=$port")
                if (port == 0) {
                    Log.w(TAG, "port is 0, skipping ${serviceInfo.serviceName}")
                    return
                }
                val svc = AdbDiscoveredService(
                    serviceName = serviceInfo.serviceName,
                    host = addr,
                    port = port,
                )
                synchronized(found) {
                    if (found.none { it.host == svc.host && it.port == svc.port }) {
                        found.add(svc)
                        Log.i(TAG, "candidate added: ${svc.serviceName}:${svc.port}(${svc.host})")
                    }
                }
            }
        }

        try {
            manager.resolveService(service, resolveListener)
        } catch (t: Throwable) {
            Log.e(TAG, "resolveService failed", t)
        }
    }

    private fun AdbDiscoveredService.isLoopback(): Boolean {
        val h = host
        return h == "127.0.0.1" || h == "0:0:0:0:0:0:0:1" || h == "::1"
    }
}

/** Platform backend for Wireless Debugging readiness detection. */
interface AdbWirelessDebuggingReadinessBackend {
    /** Query the current WD state (never auto-enables). */
    fun query(): AdbWirelessDebuggingState
}

/** Android Settings.Global implementation of the readiness backend. */
class AdbAndroidWirelessDebuggingReadinessBackend(context: Context) :
    AdbWirelessDebuggingReadinessBackend {

    private companion object {
        const val TAG = "AdbWdReadiness"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }

    private val appContext = context.applicationContext

    override fun query(): AdbWirelessDebuggingState {
        return try {
            val v = Settings.Global.getInt(
                appContext.contentResolver, ADB_WIFI_ENABLED, 0,
            )
            if (v == 1) AdbWirelessDebuggingState.ENABLED else AdbWirelessDebuggingState.DISABLED
        } catch (t: Throwable) {
            Log.w(TAG, "read WD state failed -> UNKNOWN", t)
            AdbWirelessDebuggingState.UNKNOWN
        }
    }
}