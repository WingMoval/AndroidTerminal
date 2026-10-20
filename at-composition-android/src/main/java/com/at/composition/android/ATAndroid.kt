// AT Composition Android — Android assembly root entry point (V4-2).
//
// This is the CONSTRUCTION / ASSEMBLY SURFACE for Android consumers.
// It wires the ADB lifecycle, identity binding, and backend construction.
//
// Provider Wiring Model = A (direct dependency on :adb-engine)
//
// IMPORTANT: This is NOT a new lifecycle owner. AdbRuntime remains the
// UNIQUE lifecycle owner. This module only performs assembly/construction.

package com.at.composition.android

import android.content.Context
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbRuntime
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.backend.local.LocalOneShotBackend
import com.at.backend.local.LocalPersistentBackend
import com.at.engine.ATEngine
import com.at.engine.impl.DefaultATEngine
import java.io.File

/**
 * Android composition root entry point.
 *
 * Usage:
 * ```kotlin
 * val composition = ATAndroid.create(context)
 * val localEngine: ATEngine = composition.localEngine
 * val adbEngine: ATEngine = composition.adbEngine
 * ```
 *
 * The returned [ATAndroidComposition] exposes ONLY the [ATEngine] interface
 * (Layer-2 public contract). It does NOT expose:
 *   - DefaultATEngine
 *   - LocalOneShotBackend / LocalPersistentBackend
 *   - AdbOneShotBackend / AdbPersistentBackend
 *   - AdbRuntime
 *   - AdbEngine
 *   - identityDir
 *   - Any ADB internals
 *
 * LOCAL_REQUIRES_ADB = NO
 * LOCAL_REQUIRES_ADBRUNTIME = NO
 * LOCAL_REQUIRES_IDENTITY = NO
 */
object ATAndroid {

    /**
     * Create the Android composition root.
     *
     * This method:
     *   1. Initializes AdbRuntime (if not already initialized)
     *   2. Creates the app-private identity directory
     *   3. Constructs the Local ATEngine (LocalOneShotBackend + LocalPersistentBackend)
     *   4. Constructs the ADB ATEngine (AdbOneShotBackend + AdbPersistentBackend)
     *
     * The Local engine is ALWAYS available, even if ADB initialization fails.
     * The ADB engine is constructed but may not be READY (requires connectAuto).
     *
     * @param context Android Context (uses applicationContext)
     * @return ATAndroidComposition with localEngine + adbEngine
     */
    fun create(context: Context): ATAndroidComposition {
        val app = context.applicationContext

        // 1. Initialize AdbRuntime (idempotent, process-scoped singleton)
        if (!AdbRuntime.isInitialized()) {
            AdbRuntime.initialize(app)
        }

        // 2. Create the app-private identity directory
        val identityDir = File(app.filesDir, "adb_identity")
        identityDir.mkdirs()
        val identityPath = identityDir.absolutePath

        // 3. Construct the Local ATEngine
        // LOCAL_REQUIRES_ADB = NO
        val localEngine: ATEngine = DefaultATEngine(
            oneShot = LocalOneShotBackend(),
            persistent = LocalPersistentBackend(),
        )

        // 4. Construct the ADB ATEngine
        // The placeholder port (0) means "reuse the lifecycle connection"
        // The actual connection is established by AdbRuntime.connectAuto
        val connection = AdbConnectionRequest("127.0.0.1", 0, identityPath)
        val adbEngine: ATEngine = DefaultATEngine(
            oneShot = AdbOneShotBackend(connection),
            persistent = AdbPersistentBackend(connection),
        )

        return ATAndroidComposition(
            localEngine = localEngine,
            adbEngine = adbEngine,
        )
    }
}

/**
 * Android composition handle.
 *
 * Exposes ONLY the [ATEngine] interface (Layer-2 public contract).
 * Does NOT expose:
 *   - DefaultATEngine
 *   - Backend concrete classes
 *   - AdbRuntime
 *   - AdbEngine
 *   - identityDir
 *
 * COMPOSITION_CARDINALITY:
 *   - ONE logical composition owner per process
 *   - ATEngine object count is NOT a lifecycle invariant
 *   - Two DefaultATEngine instances (local + adb) is ALLOWED
 */
class ATAndroidComposition internal constructor(
    /**
     * Local ATEngine (LocalOneShotBackend + LocalPersistentBackend).
     *
     * LOCAL_REQUIRES_ADB = NO
     * LOCAL_REQUIRES_ADBRUNTIME = NO
     * LOCAL_REQUIRES_IDENTITY = NO
     */
    val localEngine: ATEngine,

    /**
     * ADB ATEngine (AdbOneShotBackend + AdbPersistentBackend).
     *
     * The engine is constructed but may not be READY.
     * Callers must use AdbRuntime.connectAuto() to establish the connection.
     */
    val adbEngine: ATEngine,
)
