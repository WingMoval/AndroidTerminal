// ADB-only Test Host — consumer composition root (AT.V3-2.3).
//
// TEST / VALIDATION ONLY. Proves the physical ADB-only composition (Goal A):
// a consumer that can ONLY combine :at-engine + :at-backend-adb + :adb-engine
// (no Local Backend) to both (1) bootstrap the ADB connection lifecycle and
// (2) execute through the AT Engine.
//
// Two strictly separated paths:
//   (A) Host / Composition lifecycle  -> AdbRuntime (ADB-domain):
//       initialize / readiness / connectAuto / state / disconnect
//   (B) business execution            -> ATEngine -> ADB Backend -> ADB Engine:
//       execute / openSession
//
// The execution consumer NEVER touches AdbClient / TLS / CNXN / mDNS / dynamic
// port. It only knows "the ADB provider" via the ATEngine facade.

package com.androidterminal.adbonlyhost

import android.content.Context
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbRuntime
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.engine.ATEngine

/**
 * The ADB-only consumer composition root.
 *
 * - [bootstrap] wires the ADB lifecycle (platform assembly; ADB-domain).
 * - [engine] is the business-execution facade the consumer actually calls.
 *   It is constructed with the ADB Backend. After [AdbRuntime.connectAuto] has
 *   established the connection (ONLINE), the backend reuses that same ONLINE
 *   connection — no per-command discovery / reconnect.
 */
object AdbOnlyHost {

    /** Lifecycle key dir (identity storage). The consumer supplies the path;
     *  it is NOT the private key itself. */
    private var keyDir: String? = null

    /**
     * Platform bootstrap for the ADB lifecycle. Idempotent.
     * [context] is a platform assembly dependency (ADB-domain), not an
     * execution-semantic input.
     */
    fun bootstrap(context: Context): AdbRuntime = run {
        val app = context.applicationContext
        keyDir = app.filesDir.absolutePath
        AdbRuntime.initialize(app)
        AdbRuntime
    }

    /**
     * The business-execution facade. After [AdbRuntime.connectAuto] -> READY,
     * the ADB Backend reuses the ONLINE connection. The placeholder endpoint in
     * the backend request is NOT used for the auto path (the connection already
     * exists); it only matters for the explicit path.
     */
    fun engine(): ATEngine {
        val kd = keyDir ?: error("AdbOnlyHost.bootstrap(context) not called")
        // Placeholder endpoint: the auto lifecycle (AdbRuntime.connectAuto) has
        // already established the connection. The backend's ensureConnected()
        // short-circuits on ONLINE and reuses it.
        val placeholder = AdbConnectionRequest("127.0.0.1", 0, kd)
        return ATEngine(AdbOneShotBackend(placeholder), AdbPersistentBackend(placeholder))
    }
}
