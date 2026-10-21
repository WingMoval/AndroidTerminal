// :at-cli-consumer-host — consumer composition root (AT.CLI-CONSUMER-0).
//
// TEST / VALIDATION ONLY. A NON-AndroidTerminal consumer that wires:
//   - its OWN AT composition (via ATAndroid.create);
//   - the :at-cli reusable bridge (same-UID UDS) + shell-visible `adb` function.
//
// V4-4.1: CONSUMER SEMANTIC TYPE CLOSURE.
//
// AtCliConsumerHost no longer imports ANY com.adb.engine type. All ADB
// semantics go through the composition control facade (ATAndroidAdbControl),
// which returns composition-owned semantic types (ATAdbReadinessState /
// ATAdbPairingResult) or primitives.
//
// This host NEVER depends on :app and NEVER copies the AndroidTerminal key.
// It proves the reusable CLI consumer capability is host-agnostic: any
// embedding APK can obtain the full Local Terminal -> adb -> uid=2000(shell)
// environment by composing :at-cli + its own AT composition.
//
// Identity: filesDir/at-cli-identity (this host's OWN app-private storage,
// self-generated). Path semantics UNCHANGED from V3.

package com.androidterminal.atclihost

import android.content.Context
import com.at.cli.AdbCliBridgeServer
import com.at.cli.ATCliExecution
import com.at.cli.ATCliPairingResult
import com.at.cli.ATCliPairingState
import com.at.cli.LocalCliBootstrap
import com.at.composition.android.ATAndroid
import com.at.composition.android.ATAndroidConfig
import com.at.composition.android.ATAndroidComposition
import com.at.composition.android.ATAdbBootstrapState
import com.at.composition.android.ATAdbPairingResult
import com.at.composition.android.ATAdbReadinessState
import com.at.engine.ATEngine

/**
 * The CLI consumer composition root.
 *
 * - [initialize] wires the ATAndroidComposition + starts the :at-cli bridge.
 * - [localEngine] / [adbEngine] delegate to the composition.
 * - [adbState] / [ensureReady] observe/drive the single AdbRuntime via the
 *   composition facade (the host does NOT own the lifecycle).
 * - [cliBootstrap] returns the host's shell-visible `adb` function bootstrap.
 * - [cliExecution] is the host-provided ATCliExecution the bridge routes into.
 */
object AtCliConsumerHost {

    private var composition: ATAndroidComposition? = null
    private var appContextRef: Context? = null
    private var cliBridge: AdbCliBridgeServer? = null

    /** The host's AtCliMain entrypoint class (shared in :at-cli). */
    const val CLI_MAIN_CLASS = "com.at.cli.AtCliMain"

    /**
     * Wire the ATAndroidComposition + start the :at-cli bridge. Idempotent.
     *
     * The identity dir is THIS host's own private storage
     * (filesDir/at-cli-identity). It is never the AndroidTerminal key.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (composition == null) {
            appContextRef = app
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

    /** Current ADB lifecycle state (composition semantic projection). */
    fun adbState(): ATAdbReadinessState =
        composition?.adbControl?.readinessState()
            ?: error("AtCliConsumerHost.initialize(context) not called")

    /** Drive the single AdbRuntime readiness sequence (via composition facade). */
    fun ensureReady(onStatusLine: (String) -> Unit = { }): Boolean =
        composition?.adbControl?.ensureReady(onStatusLine)
            ?: error("AtCliConsumerHost.initialize(context) not called")

    /**
     * The host's ADB identity directory (app-private storage, self-generated).
     *
     * V4-4.1: exposed as [File] so Consumer tests can verify identity
     * separation (key NOT copied from the product app) WITHOUT naming any
     * provider type. Path semantics UNCHANGED (filesDir/at-cli-identity).
     */
    fun adbIdentityDir(): java.io.File =
        java.io.File(
            appContextRef?.filesDir
                ?: error("AtCliConsumerHost.initialize(context) not called"),
            "at-cli-identity",
        )

    /**
     * Test-only (V4-4.1): whether a controlled identity directory holds usable
     * identity material (adbkey / adbkey.pub). Pure filesystem check — the
     * SAME predicate the provider bootstrap uses, evaluated WITHOUT naming
     * any provider type (used by the B1 honest-classification gate).
     */
    fun hasIdentityForDir(dir: java.io.File): Boolean =
        java.io.File(dir, "adbkey").exists() || java.io.File(dir, "adbkey.pub").exists()

    /**
     * Test-only (V4-4.1): the bootstrap CLASSIFICATION that the consumer
     * bootstrap bound to [dir] would report, evaluated against the host's
     * CURRENT composition readiness (via the semantic facade — NO provider
     * type named).
     *
     * The conservative C4 rules (SAME as the provider bootstrapState):
     *   READY when ready; USER_ACTION_REQUIRED_WD / ENDPOINT_NOT_FOUND
     *   pass-through; PAIRING_REQUIRED when the dir has NO key material
     *   (evidence-backed); otherwise CONNECT_FAILED.
     *
     * No second bootstrap state machine is created (the host's own bootstrap
     * is owned by the composition; this only re-reads the same state).
     */
    fun bootstrapClassificationForDir(dir: java.io.File): ATAdbBootstrapState {
        val readiness = composition?.adbControl?.readinessState()
            ?: return ATAdbBootstrapState.NOT_READY
        return when {
            readiness.isReady -> ATAdbBootstrapState.READY
            readiness == ATAdbReadinessState.USER_ACTION_REQUIRED ->
                ATAdbBootstrapState.USER_ACTION_REQUIRED_WD
            readiness == ATAdbReadinessState.ENDPOINT_NOT_FOUND ->
                ATAdbBootstrapState.ENDPOINT_NOT_FOUND
            !hasIdentityForDir(dir) -> ATAdbBootstrapState.PAIRING_REQUIRED
            else -> ATAdbBootstrapState.CONNECT_FAILED
        }
    }

    /**
     * Test-only (V4-4.1): whether the process's single AdbRuntime has been
     * initialized (via the composition facade — NO provider type named).
     * Used by ownership gates to prove SECOND_ADB_RUNTIME = NO.
     */
    fun isAdbRuntimeInitialized(): Boolean =
        composition?.adbControl?.isRuntimeInitialized() == true

    /**
     * The host-provided ATCliExecution the :at-cli bridge routes into.
     *
     * Delegates to the composition's adbEngine + adbControl (no provider types).
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
            // HONEST first-use signal. No identity -> the user must pair first.
            if (!comp.adbControl.hasIdentity()) {
                onStatusLine("pairing required: no ADB identity found")
                onStatusLine(
                    "open Settings -> Wireless Debugging -> Pair device with pairing code, " +
                        "then run: adb pair <host>:<port> <code>"
                )
                return false
            }
            // Not READY (identity present): run the Runtime-owned ensureReady
            // sequence, forwarding status lines (T_STATUS fallback — this host
            // has no Human UI projection).
            val ready = comp.adbControl.ensureReady { line -> onStatusLine(line) }
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

        override fun pairingState(): ATCliPairingState =
            when {
                composition?.adbControl?.hasIdentity() == true &&
                    composition?.adbControl?.readinessState()?.isReady == true -> ATCliPairingState.READY
                composition?.adbControl?.hasIdentity() == false -> ATCliPairingState.PAIRING_REQUIRED
                else -> ATCliPairingState.CONNECT_FAILED
            }

        override fun pair(host: String, port: Int, code: String): ATCliPairingResult =
            toCliResult(
                composition?.adbControl?.pair(host, port, code)
                    ?: error("AtCliConsumerHost.initialize(context) not called")
            )

        override fun pairDiscovered(code: String): ATCliPairingResult =
            toCliResult(
                composition?.adbControl?.pairDiscovered(code)
                    ?: error("AtCliConsumerHost.initialize(context) not called")
            )
    }

    /** Host mapping: composition semantic result -> CLI semantic result. */
    private fun toCliResult(r: ATAdbPairingResult): ATCliPairingResult =
        ATCliPairingResult(
            success = r.success,
            outcome = when (r.outcome) {
                ATAdbPairingResult.Outcome.SUCCESS -> ATCliPairingResult.Outcome.SUCCESS
                ATAdbPairingResult.Outcome.REJECTED -> ATCliPairingResult.Outcome.REJECTED
                ATAdbPairingResult.Outcome.SERVICE_NOT_FOUND -> ATCliPairingResult.Outcome.SERVICE_NOT_FOUND
                ATAdbPairingResult.Outcome.MULTIPLE_ENDPOINTS -> ATCliPairingResult.Outcome.MULTIPLE_ENDPOINTS
                ATAdbPairingResult.Outcome.FAILED -> ATCliPairingResult.Outcome.FAILED
            },
            deviceGuid = r.deviceGuid,
            error = r.error,
        )

    /** Stop the bridge (teardown; closes CLI-owned interactive sessions). */
    fun stopCliBridge() {
        runCatching { cliBridge?.stop() }
    }
}
