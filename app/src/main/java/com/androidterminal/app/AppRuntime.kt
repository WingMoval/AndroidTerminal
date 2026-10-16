// AndroidTerminal V3 — App-layer composition root (AT.HUMAN-ADB-0).
//
// The single process-scoped composition point that wires the ADB lifecycle and
// builds the AT Engine with a selected backend. It is the "one execution core
// path" owner for :app:
//
//   - ADB lifecycle ownership  = AdbRuntime (ADB-domain singleton)  ← THIS
//   - terminal execution owner = ATEngine -> selected backend       ← built here
//
// Both the Human UI (MainActivity) and the Debug/Test consumer (instrumentation
// harness) obtain their ATEngine from THIS object, so Human and Debug share the
// SAME ATEngine public consumer surface / backend semantics. There is only ONE
// AdbRuntime / ADB connection owner per process.
//
// The App owns its own persistent ADB identity in private storage
// (filesDir/adb_identity) — never a copied PC adbkey, never packaged.
//
// SessionBackendKind is App/UI composition only (provider identity), NOT an
// AT domain contract.

package com.androidterminal.app

import android.content.Context
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeDiagnostic
import com.adb.engine.AdbRuntimeState
import com.androidterminal.app.cli.AdbCliBridgeServer
import com.androidterminal.app.cli.CliFrontendProvisioner
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.backend.local.LocalPersistentBackend
import com.at.engine.ATEngine
import java.io.File

/** App/UI backend kind (provider identity; NOT an AT domain contract). */
enum class SessionBackendKind {
    LOCAL,
    ADB,
}

/**
 * App-layer composition root (process-scoped).
 *
 * - [initialize] wires AdbRuntime once and fixes the app's ADB identity path.
 * - [localEngine] / [adbEngine] build an [ATEngine] with the selected backend.
 * - [connectAuto] / [adbState] drive the single AdbRuntime lifecycle owner.
 */
object AppRuntime {

    private var adbKeyDir: String? = null
    private var filesDirRef: File? = null
    private var cliBridge: AdbCliBridgeServer? = null

    /** Wire the ADB runtime + fix the app identity dir. Idempotent. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (!AdbRuntime.isInitialized()) {
            AdbRuntime.initialize(app)
        }
        if (adbKeyDir == null) {
            filesDirRef = app.filesDir
            val dir = File(app.filesDir, "adb_identity")
            dir.mkdirs()
            adbKeyDir = dir.absolutePath
        }
        // AT.CLI-0: provision the shell-hosted `adb` CLI (asset extract + PATH)
        // and start the product CLI bridge. Product surface: present in BOTH
        // debug and release (NOT gated by BuildConfig.DEBUG).
        runCatching {
            CliFrontendProvisioner.provision(app)
            if (cliBridge == null) {
                val dir = app.filesDir
                cliBridge = AdbCliBridgeServer(dir)
                cliBridge?.start()
            }
        }
    }

    /** The app's own persistent ADB identity directory (private storage). */
    fun adbIdentityDir(): String = adbKeyDir
        ?: error("AppRuntime.initialize(context) not called")

    /** Current ADB lifecycle state (single owner = AdbRuntime). */
    fun adbState(): AdbRuntimeState = AdbRuntime.readinessState()

    /** Drive the single AdbRuntime auto-connect (READY gate). */
    fun connectAuto(): AdbRuntimeDiagnostic = AdbRuntime.connectAuto(adbIdentityDir())

    /** ATEngine with the Local backend (Local PTY -> /system/bin/sh). */
    fun localEngine(): ATEngine = ATEngine(oneShot = null, persistent = LocalPersistentBackend())

    /**
     * ATEngine with the ADB backend (reuses the AdbRuntime connection).
     * Placeholder endpoint: the AUTO lifecycle (connectAuto) has already
     * established the connection; the backend short-circuits on ONLINE.
     *
     * AT.CLI-0: also wires the one-shot backend so the shell-hosted `adb` CLI
     * (and any App consumer) can run `adb shell <cmd>` one-shot executions
     * through the SAME AdbRuntime / ADB Engine. App-layer composition only —
     * no AT/ADB Engine change.
     */
    fun adbEngine(): ATEngine {
        val placeholder = AdbConnectionRequest("127.0.0.1", 0, adbIdentityDir())
        return ATEngine(
            oneShot = AdbOneShotBackend(placeholder),
            persistent = AdbPersistentBackend(placeholder),
        )
    }

    /**
     * AT.CLI-0: the product CLI bridge server (Unix Domain Socket, app-internal).
     * Consumed by the shell-hosted `adb` CLI frontend. Null if provisioning
     * failed. This is a consumer/frontend adapter, NOT an AT/ADB Engine API.
     */
    fun cliBridge(): AdbCliBridgeServer? = cliBridge
}
