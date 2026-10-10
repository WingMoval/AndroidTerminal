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

    override fun scan(timeoutMs: Long): List<AdbDiscoveredService> {
        val discovered = mutableListOf<AdbDiscoveredService>()
        val latch = java.util.concurrent.CountDownLatch(1)

        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager not available")
            return emptyList()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                discovered += AdbDiscoveredService(
                    serviceName = serviceInfo.serviceName,
                    host = serviceInfo.host?.hostAddress,
                    port = serviceInfo.port,
                )
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.e(TAG, "discoverServices threw", t)
        } finally {
            try { manager.stopServiceDiscovery(listener) } catch (t: Throwable) { Log.w(TAG, "stop threw", t) }
        }
        return discovered.filter { it.port != 0 }
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