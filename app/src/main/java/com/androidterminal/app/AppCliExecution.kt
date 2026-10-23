// AT.CLI-CONSUMER-0 — :app's ATCliExecution adapter.
//
// The reusable CLI bridge (in :at-cli) is host-agnostic: it routes CLI
// one-shot / interactive into a host-provided [ATCliExecution]. This is the
// ANDROIDTERMINAL PRODUCT host's adapter: it wires the bridge into the
// product's own composition (ATAndroidComposition).
//
// V4-4: Migrated to use ATAndroidComposition.adbControl.
//
// The bridge NEVER creates a second AdbRuntime / AT Engine / identity. It only
// calls into the product's existing composition.
//
// The Human-facing status projection (AdbCliStatusHub, the read-only
// AdbRuntime lifecycle observer that fans out `[adb] ...` to the bound Local
// session) stays in :app — it is a product/UI concern, not a reusable CLI
// concern.
//
// STATUS SEMANTICS (preserved from AT.CLI-0.1A/0.1B, pre-extraction):
//   - The bridge binds the Local session via HELLO (sessionId).
//   - ensureReady SUBSCRIBES the session to ADB lifecycle status, then runs the
//     Runtime-owned ensureReady sequence.
//   - FAST PATH (already READY): if this is the session's FIRST use AND the
//     runtime is already READY, the lifecycle transition already fired before
//     the session subscribed, so we BACKFILL "connected" (no duplicate — the
//     transition won't fire again for the same state).
//   - COLD PATH: intermediate status lines (checking/enabling/discovering/
//     connecting/retrying/user-action-required) are projected LIVE; "connected"
//     is owned by the READY lifecycle transition (NOT re-emitted from the
//     statusListener — that would duplicate).

package com.androidterminal.app

import com.at.cli.ATCliExecution
import com.at.cli.ATCliPairingResult
import com.at.cli.ATCliPairingState
import com.at.composition.android.ATAdbPairingResult
import com.at.engine.ATEngine

/**
 * The AndroidTerminal product's ATCliExecution: routes the reusable CLI bridge
 * into the product's own shared ATEngine + AdbRuntime composition.
 *
 * The bridge constructs one [AppCliExecution] per connected CLI client fd and
 * passes the bound [sessionId] to [ensureReady] (from the HELLO payload).
 */
class AppCliExecution : ATCliExecution {

    override fun adbEngine(): ATEngine = AppRuntime.adbEngine()

    override fun ensureReady(sessionId: Int, onStatusLine: (String) -> Unit): Boolean {
        // Subscribe the bound session to AdbRuntime lifecycle status (so async
        // transitions reach it). Track whether this is the FIRST subscribe for
        // the fast-path "connected" backfill.
        val sid = if (sessionId > 0) sessionId else null
        val firstSubscribe = sid != null && !AppRuntime.isAdbStatusSubscribed(sid)
        if (sid != null) AppRuntime.subscribeAdbStatus(sid)

        // FAST PATH: already READY. Backfill "connected" only on the session's
        // FIRST use (the lifecycle transition fired before the session
        // subscribed, so it won't re-emit). Matches pre-extraction behavior.
        if (AppRuntime.adbState().isReady) {
            if (firstSubscribe) emitStatus(sid, onStatusLine, "connected")
            return true
        }

        // AT.CONSUMER-BOOTSTRAP-0: HONEST first-use signal. If the consumer has
        // NO usable identity (no key material), the Runtime's generic "connection
        // failed" is misleading — the real reason is "you need to pair first".
        // Classify via the composition's adbControl BEFORE running the cold
        // sequence, and surface a clear pairing-required message.
        val control = AppRuntime.adbControl
        if (!control.hasIdentity()) {
            emitStatus(sid, onStatusLine, "pairing required: no ADB identity found")
            emitStatus(
                sid, onStatusLine,
                "open Settings -> Wireless Debugging -> Pair device with pairing code, " +
                    "then run: adb pair <host>:<port> <code>"
            )
            return false
        }

        // COLD PATH: run the Runtime-owned ensureReady sequence, forwarding
        // INTERMEDIATE status lines LIVE (Host projection for the bound session
        // + T_STATUS fallback via onStatusLine). "connected" is owned by the
        // READY lifecycle transition — NOT re-emitted here (no duplicate).
        val ready = control.ensureReady { line ->
            if (line != "connected") emitStatus(sid, onStatusLine, line)
        }
        // Conservative post-failure classification (C4): identity EXISTS but the
        // connect failed. Do NOT assert "must pair" (a Boolean failure is not
        // auth evidence), but offer pairing as a remediation so the user has a
        // clear next step instead of a bare "connection failed".
        if (!ready) {
            emitStatus(
                sid, onStatusLine,
                "not connected (identity present). If pairing is needed: " +
                    "adb pair <host>:<port> <code>"
            )
        }
        return ready
    }

    /**
     * Project a status line to EXACTLY ONE path (mirrors the pre-extraction
     * AdbCliBridgeServer.emitStatus single-projection semantics — emitting to
     * both would duplicate the line in the session):
     *   - bound Human session (subscribed) → Host/SYSTEM projection (live).
     *   - otherwise (non-Human / unbound)  → bridge T_STATUS fallback
     *     (onStatusLine; the short-lived AdbCliMain buffers until EXIT).
     */
    private fun emitStatus(sid: Int?, onStatusLine: (String) -> Unit, line: String) {
        if (sid != null && AppRuntime.isAdbStatusSubscribed(sid)) {
            // Bound Human session: HOST projection (live, immediate).
            AppRuntime.emitAdbCommandStatus(sid, line)
        } else {
            // No valid / subscribed session: T_STATUS fallback.
            onStatusLine(line)
        }
    }

    override fun pairingState(): ATCliPairingState =
        when {
            AppRuntime.adbControl.hasIdentity() && AppRuntime.adbState().isReady -> ATCliPairingState.READY
            !AppRuntime.adbControl.hasIdentity() -> ATCliPairingState.PAIRING_REQUIRED
            else -> ATCliPairingState.CONNECT_FAILED
        }

    override fun pair(host: String, port: Int, code: String): ATCliPairingResult {
        val result = toCliResult(AppRuntime.adbControl.pair(host, port, code))
        if (result.success) {
            // AT.APP-RELEASE-2: CLI pairing also triggers first-use bootstrap
            // (WRITE_SECURE_SETTINGS self-grant for post-bootstrap Auto-WD).
            // Previously only PairActivity UI pairing triggered this.
            AppRuntime.adbControl.completeFirstUseBootstrap()
        }
        return result
    }

    override fun pairDiscovered(code: String): ATCliPairingResult {
        val result = toCliResult(AppRuntime.adbControl.pairDiscovered(code))
        if (result.success) {
            AppRuntime.adbControl.completeFirstUseBootstrap()
        }
        return result
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
}
