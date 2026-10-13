// AT.V3-DEBT-1 Gate 3 — Accessibility pairing-code acquisition probe.
//
// VALIDATION / DEBUG ONLY (V2 E2 parity). This is a minimal AccessibilityService
// that dumps the active window node tree and reads the pairing-code node text
// (com.android.settings:id/pairing_code). It performs NO pairing logic.
//
// This is NOT a production onboarding component: the view id is an OEM /
// Settings implementation detail, not an Android public contract. Manual
// pairing-code fallback remains the cross-OEM product path (deferred).
//
// Modeled after the V2 disposable PairingCodeProbeService (AT_FIRST_RUN §6).

package com.androidterminal.adbonlyhost

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * DEBUG-ONLY probe: reads the Wireless Debugging pairing-code node and exposes
 * the last observed value. Enabled via shell (`settings put secure
 * enabled_accessibility_services ...`) for validation; NOT a product component.
 *
 * The probe writes the last observed code to `filesDir/pairing_code.txt` and
 * `filesDir/pairing_ipport.txt` so a SEPARATE instrumentation process can read
 * it (Android instrumentation runs in a different APK/process; in-memory
 * statics are not shared).
 */
class PairingCodeProbeService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbPairingProbe"

        @Volatile
        var lastPairingCode: String? = null

        @Volatile
        var lastPairingIpPort: String? = null

        @Volatile
        var lastReadAtMs: Long = 0L

        @Volatile
        var probeEnabled: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        probeEnabled = true
        Log.i(TAG, "probe connected; eventTypes=TYPE_WINDOW_CONTENT_CHANGED|TYPE_WINDOW_STATE_CHANGED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        scan(root)
        root.recycle()
    }

    private fun scan(node: AccessibilityNodeInfo) {
        val vid = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        when {
            vid == "com.android.settings:id/pairing_code" && text != null -> {
                if (text.matches(Regex("\\d{6}"))) {
                    lastPairingCode = text
                    lastReadAtMs = System.currentTimeMillis()
                    Log.i(TAG, "PAIRING_CODE_READ text='$text' vid=$vid")
                    persistCode(text)
                }
            }
            vid == "com.android.settings:id/ip_addr" && text != null -> {
                lastPairingIpPort = text
                Log.i(TAG, "PAIRING_IP_PORT_READ text='$text' vid=$vid")
                persistIpPort(text)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scan(child)
            child.recycle()
        }
    }

    private fun persistCode(code: String) {
        try {
            File(filesDir, "pairing_code.txt").writeText(code)
        } catch (t: Throwable) {
            Log.w(TAG, "persist code failed", t)
        }
    }

    private fun persistIpPort(ipPort: String) {
        try {
            File(filesDir, "pairing_ipport.txt").writeText(ipPort)
        } catch (t: Throwable) {
            Log.w(TAG, "persist ipport failed", t)
        }
    }

    override fun onInterrupt() {}
}