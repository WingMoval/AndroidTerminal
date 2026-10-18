// :at-cli-consumer-host — consumer composition root (AT.CLI-CONSUMER-0).
//
// TEST / VALIDATION ONLY. A NON-AndroidTerminal consumer that wires:
//   - its OWN AdbRuntime (identity = filesDir/at-cli-identity, self-generated);
//   - its OWN AT composition (Local + ADB backends);
//   - the :at-cli reusable bridge (same-UID UDS) + shell-visible `adb` function.
//
// This host NEVER depends on :app and NEVER copies the AndroidTerminal key.
// It proves the reusable CLI consumer capability is host-agnostic: any
// embedding APK can obtain the full Local Terminal -> adb -> uid=2000(shell)
// environment by composing :at-cli + its own AT / AdbRuntime.
//
// Two paths (mirrors AdbOnlyHost, adds Local + CLI):
//   (A) lifecycle  -> AdbRuntime (initialize / ensureReady / connectAuto / state)
//   (B) execution  -> ATEngine -> Local / ADB Backend -> ADB Engine
//   (C) CLI bridge -> :at-cli AdbCliBridgeServer + LocalCliBootstrap

package com.androidterminal.atclihost

import android.content.Context
import android.content.pm.PackageManager
import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.at.backend.adb.AdbOneShotBackend
import com.at.backend.adb.AdbPersistentBackend
import com.at.backend.local.LocalPersistentBackend
import com.at.cli.AdbCliBridgeServer
import com.at.cli.ATCliExecution
import com.at.cli.LocalCliBootstrap
import com.at.engine.ATEngine
import java.io.File

/**
 * The CLI consumer composition root.
 *
 * - [initialize] wires AdbRuntime + fixes the host's self-generated identity
 *   dir + starts the :at-cli bridge. Idempotent.
 * - [localEngine] / [adbEngine] build ATEngine with the selected backend.
 * - [cliBootstrap] returns the host's shell-visible `adb` function bootstrap.
 * - [cliExecution] is the host-provided ATCliExecution the bridge routes into.
 */
object AtCliConsumerHost {

    private var keyDir: String? = null
    private var filesDirRef: File? = null
    private var appContextRef: Context? = null
    private var cliBridge: AdbCliBridgeServer? = null

    /** The host's AtCliMain entrypoint class (shared in :at-cli). */
    const val CLI_MAIN_CLASS = "com.at.cli.AtCliMain"

    /** Wire the ADB runtime + fix the host's SELF-GENERATED identity dir +
     *  start the :at-cli bridge. Idempotent.
     *
     *  The identity dir is THIS host's own private storage
     *  (filesDir/at-cli-identity). It is never the AndroidTerminal key.
     *  On first use it is empty -> UNPAIRED / NEW_IDENTITY.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (!AdbRuntime.isInitialized()) {
            AdbRuntime.initialize(app)
        }
        if (keyDir == null) {
            appContextRef = app
            filesDirRef = app.filesDir
            val dir = File(app.filesDir, "at-cli-identity")
            dir.mkdirs()
            keyDir = dir.absolutePath
        }
        // Start the reusable :at-cli bridge (same-UID UDS, host-private).
        if (cliBridge == null) {
            val dir = app.filesDir
            cliBridge = AdbCliBridgeServer(dir, cliExecution(sessionId = 0))
            cliBridge?.start()
        }
    }

    /** The host's OWN self-generated ADB identity directory (private). */
    fun adbIdentityDir(): String = keyDir
        ?: error("AtCliConsumerHost.initialize(context) not called")

    /** The host's installed base.apk path (for the `adb` shell function). */
    fun appApkPath(): String {
        val p = appContextRef?.applicationInfo?.sourceDir
        return p ?: error("AtCliConsumerHost.initialize(context) not called")
    }

    /** The :at-cli bridge UDS path (injected into the Local session). */
    fun cliBridgeSocketPath(): String = cliBridge?.socketPath.orEmpty()

    /** Whether the :at-cli bridge is up. */
    fun cliBridgeRunning(): Boolean = cliBridge?.isRunning() ?: false

    /** The host's shell-visible `adb` function bootstrap. */
    fun cliBootstrap(): LocalCliBootstrap = LocalCliBootstrap(CLI_MAIN_CLASS)

    /** The host's own AT composition (Local backend). */
    fun localEngine(): ATEngine = ATEngine(oneShot = null, persistent = LocalPersistentBackend())

    /** The host's own AT composition (ADB backend; reuses AdbRuntime conn). */
    fun adbEngine(): ATEngine {
        val kd = keyDir ?: error("AtCliConsumerHost.initialize(context) not called")
        val placeholder = AdbConnectionRequest("127.0.0.1", 0, kd)
        return ATEngine(
            oneShot = AdbOneShotBackend(placeholder),
            persistent = AdbPersistentBackend(placeholder),
        )
    }

    /** Current ADB lifecycle state (single owner = AdbRuntime). */
    fun adbState(): AdbRuntimeState = AdbRuntime.readinessState()

    /** Drive the single AdbRuntime auto-connect (READY gate). */
    fun connectAuto() = AdbRuntime.connectAuto(adbIdentityDir())

    /**
     * The host-provided ATCliExecution the :at-cli bridge routes into.
     *
     * This is the seam that keeps the bridge host-agnostic: the bridge calls
     * [ensureReady] / [adbEngine] / [adbIdentityDir] on THIS object, which wires
     * the host's own AdbRuntime + ATEngine. No :app class involved.
     */
    fun cliExecution(sessionId: Int = 0): ATCliExecution = object : ATCliExecution {
        override fun adbEngine(): ATEngine = AtCliConsumerHost.adbEngine()

        // The 2-arg ensureReady is the SPI abstract method. (The 1-arg
        // overload is inherited from the interface's default impl.)
        override fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean {
            // Fast path: already READY.
            if (AdbRuntime.readinessState().isReady) {
                onStatusLine("connected")
                return true
            }
            // Not READY: run the Runtime-owned ensureReady sequence, forwarding
            // status lines. The consumer host has no Human UI projection, so
            // every line goes to the onStatusLine callback (-> T_STATUS
            // fallback to the CLI client).
            val finalState = AdbRuntime.ensureReady(adbIdentityDir()) { line ->
                onStatusLine(line)
            }
            return finalState == AdbRuntimeState.READY
        }

        override fun adbIdentityDir(): String = AtCliConsumerHost.adbIdentityDir()
    }

    /** Stop the bridge (teardown; closes CLI-owned interactive sessions). */
    fun stopCliBridge() {
        runCatching { cliBridge?.stop() }
    }
}
