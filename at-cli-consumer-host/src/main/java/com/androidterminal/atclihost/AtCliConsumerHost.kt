// :at-cli-consumer-host — consumer composition root (AT.CLI-CONSUMER-0).
//
// TEST / VALIDATION ONLY. A NON-AndroidTerminal consumer that wires:
//   - its OWN AT composition (via ATAndroid.create);
//   - the :at-cli reusable bridge (same-UID UDS) + shell-visible `adb` function.
//
// V4-4: Migrated to use ATAndroidComposition.
//
// This host NEVER depends on :app and NEVER copies the AndroidTerminal key.
// It proves the reusable CLI consumer capability is host-agnostic: any
// embedding APK can obtain the full Local Terminal -> adb -> uid=2000(shell)
// environment by composing :at-cli + its own AT composition.
//
// Two paths (mirrors AdbOnlyHost, adds Local + CLI):
//   (A) lifecycle  -> ATAndroidComposition.adbControl (delegates to AdbRuntime)
//   (B) execution  -> ATAndroidComposition.adbEngine -> ADB Backend -> ADB Engine
//   (C) CLI bridge -> :at-cli AdbCliBridgeServer + LocalCliBootstrap

package com.androidterminal.atclihost

import android.content.Context
import android.content.pm.PackageManager
import com.adb.engine.AdbConsumerBootstrap
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeDiagnostic
import com.adb.engine.AdbRuntimeState
import com.at.cli.AdbCliBridgeServer
import com.at.cli.ATCliExecution
import com.at.cli.LocalCliBootstrap
import com.at.composition.android.ATAndroid
import com.at.composition.android.ATAndroidConfig
import com.at.composition.android.ATAndroidComposition
import com.at.engine.ATEngine
import java.io.File

/**
 * The CLI consumer composition root.
 *
 * V4-4: Uses ATAndroid.create(context) to obtain the composition.
 *
 * - [initialize] wires the ATAndroidComposition + starts the :at-cli bridge.
 * - [localEngine] / [adbEngine] delegate to the composition.
 * - [cliBootstrap] returns the host's shell-visible `adb` function bootstrap.
 * - [cliExecution] is the host-provided ATCliExecution the bridge routes into.
 *
 * For test compatibility, the following methods are preserved:
 *   - adbIdentityDir()
 *   - appApkPath()
 *   - cliBridgeSocketPath()
 *   - cliBridgeRunning()
 *   - adbState()
 *   - connectAuto()
 */
object AtCliConsumerHost {

    private var composition: ATAndroidComposition? = null
    private var appContextRef: Context? = null
    private var filesDirRef: File? = null
    private var cliBridge: AdbCliBridgeServer? = null
    private var keyDir: String? = null

    /** The host's AtCliMain entrypoint class (shared in :at-cli). */
    const val CLI_MAIN_CLASS = "com.at.cli.AtCliMain"

    /**
     * Wire the ATAndroidComposition + start the :at-cli bridge. Idempotent.
     *
     * The identity dir is THIS host's own private storage
     * (filesDir/at-cli-identity). It is never the AndroidTerminal key.
     * On first use it is empty -> UNPAIRED / NEW_IDENTITY.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (composition == null) {
            appContextRef = app
            filesDirRef = app.filesDir
            // Track the identity dir for test compatibility.
            val dir = File(app.filesDir, "at-cli-identity")
            dir.mkdirs()
            keyDir = dir.absolutePath
            // Create the composition with the host's identity profile.
            composition = ATAndroid.create(
                context = app,
                config = ATAndroidConfig(identityProfile = "at-cli-identity"),
            )
        }
        // Start the reusable :at-cli bridge (same-UID UDS, host-private).
        if (cliBridge == null) {
            val dir = app.filesDir
            cliBridge = AdbCliBridgeServer(dir, cliExecution(sessionId = 0))
            cliBridge?.start()
        }
    }

    /** The composition (null if not initialized). */
    fun composition(): ATAndroidComposition? = composition

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
    fun localEngine(): ATEngine = composition?.localEngine
        ?: error("AtCliConsumerHost.initialize(context) not called")

    /** The host's own AT composition (ADB backend; reuses AdbRuntime conn). */
    fun adbEngine(): ATEngine = composition?.adbEngine
        ?: error("AtCliConsumerHost.initialize(context) not called")

    /**
     * The host's REUSABLE ADB Consumer Bootstrap (for test compatibility).
     *
     * This is a DIRECT delegation to the bootstrap bound to the host's identity.
     * The bridge does NOT use this — it uses the composition's adbControl.
     */
    fun consumerBootstrap(): AdbConsumerBootstrap =
        AdbConsumerBootstrap(adbIdentityDir())

    /** Current ADB lifecycle state (delegates to AdbRuntime). */
    fun adbState(): AdbRuntimeState = AdbRuntime.readinessState()

    /** Drive the single AdbRuntime auto-connect (READY gate). */
    fun connectAuto(): AdbRuntimeDiagnostic = AdbRuntime.connectAuto(adbIdentityDir())

    /**
     * The host-provided ATCliExecution the :at-cli bridge routes into.
     *
     * V4-4: Delegates to the composition's adbEngine + adbControl.
     */
    fun cliExecution(sessionId: Int = 0): ATCliExecution = object : ATCliExecution {
        override fun adbEngine(): ATEngine = AtCliConsumerHost.adbEngine()

        override fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean {
            val comp = composition ?: error("AtCliConsumerHost.initialize(context) not called")
            // Fast path: already READY.
            if (comp.adbControl.readinessState().isReady) {
                onStatusLine("connected")
                return true
            }
            // AT.CONSUMER-BOOTSTRAP-0: HONEST first-use signal. No identity ->
            // the user must pair first (clear message, not a bare "failed").
            if (!comp.adbControl.hasIdentity()) {
                onStatusLine("pairing required: no ADB identity found")
                onStatusLine(
                    "open Settings -> Wireless Debugging -> Pair device with pairing code, " +
                        "then run: adb pair <host>:<port> <code>"
                )
                return false
            }
            // Not READY (identity present): run the Runtime-owned ensureReady
            // sequence, forwarding status lines. The consumer host has no Human
            // UI projection, so every line goes to onStatusLine (-> T_STATUS
            // fallback to the CLI client).
            val ready = comp.adbControl.ensureReady { line ->
                onStatusLine(line)
            }
            // Conservative post-failure hint (C4): identity exists but connect
            // failed — offer pairing as a remediation (not an assertion).
            if (!ready) {
                onStatusLine(
                    "not connected (identity present). If pairing is needed: " +
                        "adb pair <host>:<port> <code>"
                )
            }
            return ready
        }

        override fun pairingState(): String =
            when {
                composition?.adbControl?.hasIdentity() == true &&
                    composition?.adbControl?.readinessState()?.isReady == true -> "READY"
                composition?.adbControl?.hasIdentity() == false -> "PAIRING_REQUIRED"
                else -> "CONNECT_FAILED"
            }

        override fun pair(host: String, port: Int, code: String): String {
            val result = composition?.adbControl?.pair(host, port, code)
                ?: error("AtCliConsumerHost.initialize(context) not called")
            return when (result.outcome) {
                com.adb.engine.AdbPairingResultV2.Outcome.SUCCESS ->
                    "pairing succeeded (guid=${result.deviceGuid})\n"
                com.adb.engine.AdbPairingResultV2.Outcome.REJECTED ->
                    "pairing rejected${if (result.error.isNotBlank()) ": ${result.error}" else ""}\n"
                com.adb.engine.AdbPairingResultV2.Outcome.SERVICE_NOT_FOUND ->
                    "pairing service not found\n"
                com.adb.engine.AdbPairingResultV2.Outcome.MULTIPLE_ENDPOINTS ->
                    "multiple pairing endpoints; use adb pair <host>:<port> <code>\n"
                com.adb.engine.AdbPairingResultV2.Outcome.FAILED ->
                    "pairing failed${if (result.error.isNotBlank()) ": ${result.error}" else ""}\n"
            }
        }

        override fun pairDiscovered(code: String): String {
            val result = composition?.adbControl?.pairDiscovered(code)
                ?: error("AtCliConsumerHost.initialize(context) not called")
            return when (result.outcome) {
                com.adb.engine.AdbPairingResultV2.Outcome.SUCCESS ->
                    "pairing succeeded (guid=${result.deviceGuid})\n"
                com.adb.engine.AdbPairingResultV2.Outcome.REJECTED ->
                    "pairing rejected${if (result.error.isNotBlank()) ": ${result.error}" else ""}\n"
                com.adb.engine.AdbPairingResultV2.Outcome.SERVICE_NOT_FOUND ->
                    "pairing service not found\n"
                com.adb.engine.AdbPairingResultV2.Outcome.MULTIPLE_ENDPOINTS ->
                    "multiple pairing endpoints; use adb pair <host>:<port> <code>\n"
                com.adb.engine.AdbPairingResultV2.Outcome.FAILED ->
                    "pairing failed${if (result.error.isNotBlank()) ": ${result.error}" else ""}\n"
            }
        }
    }

    /** Stop the bridge (teardown; closes CLI-owned interactive sessions). */
    fun stopCliBridge() {
        runCatching { cliBridge?.stop() }
    }
}
