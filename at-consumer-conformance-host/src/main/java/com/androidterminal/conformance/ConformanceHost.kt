// AT.V4-5 — Reference Consumer host (COMPOSE + CONSUME, NOT reimplement).
//
// This object proves a LEGAL consumer can drive the full AndroidTerminal
// surface using ONLY the frozen public Consumer Boundary:
//     :at-engine-api + :at-composition-android + :at-cli
//
// It does NOT name any provider type (AdbRuntime / AdbEngine / backends /
// DefaultATEngine). All ADB semantics go through the composition facade
// (ATAndroidAdbControl) which returns composition-owned semantic types.
//
// It owns its OWN app-private identity (filesDir/conformance-identity) —
// independent from Product (adb_identity) and ConsumerHost (at-cli-identity).

package com.androidterminal.conformance

import android.content.Context
import com.at.cli.AdbCliBridgeServer
import com.at.cli.ATCliExecution
import com.at.cli.ATCliPairingResult
import com.at.cli.ATCliPairingState
import com.at.cli.LocalCliBootstrap
import com.at.composition.android.ATAndroid
import com.at.composition.android.ATAndroidComposition
import com.at.composition.android.ATAndroidConfig
import com.at.engine.ATEngine

object ConformanceHost {

    private var composition: ATAndroidComposition? = null
    private var appContextRef: Context? = null
    private var cliBridge: AdbCliBridgeServer? = null

    /** This host's CLI main entrypoint (shared in :at-cli). */
    const val CLI_MAIN_CLASS = "com.at.cli.AtCliMain"

    /** The app-private identity profile (self-generated; NEVER copied). */
    const val IDENTITY_PROFILE = "conformance-identity"

    /**
     * Wire the ATAndroid composition + start the :at-cli bridge. Idempotent.
     * Uses the canonical composition entrypoint [ATAndroid.create].
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (composition == null) {
            appContextRef = app
            composition = ATAndroid.create(
                context = app,
                config = ATAndroidConfig(identityProfile = IDENTITY_PROFILE),
            )
        }
        if (cliBridge == null) {
            val dir = app.filesDir
            cliBridge = AdbCliBridgeServer(dir, cliExecution(sessionId = 0))
            cliBridge?.start()
        }
    }

    fun composition(): ATAndroidComposition? = composition

    /** Local ATEngine (public contract; no backend type named). */
    fun localEngine(): ATEngine = composition?.localEngine
        ?: error("ConformanceHost.initialize(context) not called")

    /** ADB ATEngine (public contract; reuses the single AdbRuntime connection). */
    fun adbEngine(): ATEngine = composition?.adbEngine
        ?: error("ConformanceHost.initialize(context) not called")

    /** The host's installed base.apk path (for the `adb` shell function). */
    fun appApkPath(): String {
        val p = appContextRef?.applicationInfo?.sourceDir
        return p ?: error("ConformanceHost.initialize(context) not called")
    }

    /** The :at-cli bridge UDS path. */
    fun cliBridgeSocketPath(): String = cliBridge?.socketPath.orEmpty()

    /** The host's shell-visible `adb` function bootstrap. */
    fun cliBootstrap(): LocalCliBootstrap = LocalCliBootstrap(CLI_MAIN_CLASS)

    /** The host's ADB identity dir (app-private; self-generated). */
    fun identityDir(): java.io.File =
        java.io.File(
            appContextRef?.filesDir ?: error("initialize(context) not called"),
            IDENTITY_PROFILE,
        )

    /**
     * The host-provided ATCliExecution the :at-cli bridge routes into.
     * Delegates to the composition's adbEngine + adbControl (no provider types).
     */
    fun cliExecution(sessionId: Int = 0): ATCliExecution = object : ATCliExecution {
        override fun adbEngine(): ATEngine = ConformanceHost.adbEngine()

        override fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean {
            val comp = composition ?: error("initialize(context) not called")
            if (comp.adbControl.readinessState().isReady) {
                onStatusLine("connected")
                return true
            }
            if (!comp.adbControl.hasIdentity()) {
                onStatusLine("pairing required: no ADB identity found")
                onStatusLine(
                    "open Settings -> Wireless Debugging -> Pair device with pairing code, " +
                        "then run: adb pair <host>:<port> <code>"
                )
                return false
            }
            val ready = comp.adbControl.ensureReady { line -> onStatusLine(line) }
            if (!ready) {
                onStatusLine("not connected (identity present). If pairing is needed: " +
                    "adb pair <host>:<port> <code>")
            }
            return ready
        }

        override fun pairingState(): ATCliPairingState = when {
            composition?.adbControl?.hasIdentity() == true &&
                composition?.adbControl?.readinessState()?.isReady == true -> ATCliPairingState.READY
            composition?.adbControl?.hasIdentity() == false -> ATCliPairingState.PAIRING_REQUIRED
            else -> ATCliPairingState.CONNECT_FAILED
        }

        override fun pair(host: String, port: Int, code: String): ATCliPairingResult =
            toCliResult(
                composition?.adbControl?.pair(host, port, code)
                    ?: error("initialize(context) not called")
            )

        override fun pairDiscovered(code: String): ATCliPairingResult =
            toCliResult(
                composition?.adbControl?.pairDiscovered(code)
                    ?: error("initialize(context) not called")
            )
    }

    /** Host mapping: composition semantic result -> CLI semantic result. */
    private fun toCliResult(r: com.at.composition.android.ATAdbPairingResult): ATCliPairingResult =
        ATCliPairingResult(
            success = r.success,
            outcome = when (r.outcome) {
                com.at.composition.android.ATAdbPairingResult.Outcome.SUCCESS -> ATCliPairingResult.Outcome.SUCCESS
                com.at.composition.android.ATAdbPairingResult.Outcome.REJECTED -> ATCliPairingResult.Outcome.REJECTED
                com.at.composition.android.ATAdbPairingResult.Outcome.SERVICE_NOT_FOUND -> ATCliPairingResult.Outcome.SERVICE_NOT_FOUND
                com.at.composition.android.ATAdbPairingResult.Outcome.MULTIPLE_ENDPOINTS -> ATCliPairingResult.Outcome.MULTIPLE_ENDPOINTS
                com.at.composition.android.ATAdbPairingResult.Outcome.FAILED -> ATCliPairingResult.Outcome.FAILED
            },
            deviceGuid = r.deviceGuid,
            error = r.error,
        )
}
