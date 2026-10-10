// ADB Backend — contract conformance STUB (AT.CONTRACT-0).
//
// STUB / CONTRACT PROBE. NOT a runtime implementation.
//
// Purpose: prove the AT Engine backend contract can be implemented naturally by
// the ADB Engine (V3 :adb-engine). It wires the AT contract types to the
// ADB-domain facade WITHOUT performing real ADB transport yet.
//
// Real ADB Backend wiring = AT.PERSIST-1 (DEFERRED).

package com.at.backend.adb

import com.adb.engine.AdbConnectionRequest
import com.adb.engine.AdbEngine
import com.adb.engine.AdbShellResult
import com.adb.engine.AdbShellStatus
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOneShotBackend
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATTerminalSession

/**
 * Contract conformance probe for the one-shot capability.
 *
 * It demonstrates the AT ↔ ADB semantic mapping for one-shot:
 *   ATExecutionResult(outcome=COMPLETED, stdout, stderr, exitCode)
 *     ← AdbShellResult(status=COMPLETED, stdout, stderr, exitCode)
 *
 * STUB: no real transport is performed here; [execute] currently returns
 * UNSUPPORTED until AT.PERSIST-1 wires a live connection.
 */
class AdbOneShotBackendProbe(
    /** Connection used by the real ADB Engine (identity + endpoint). */
    private val connection: AdbConnectionRequest,
) : ATOneShotBackend {

    private fun mapAdb(r: AdbShellResult): ATExecutionResult = ATExecutionResult(
        outcome = when (r.status) {
            AdbShellStatus.COMPLETED -> ATExecutionOutcome.COMPLETED
            else -> ATExecutionOutcome.TRANSPORT_ERROR
        },
        stdout = r.stdout,
        stderr = r.stderr,
        exitCode = r.exitCode,
        hasError = r.hasError,
        error = r.error,
    )

    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        // STUB: AT.PERSIST-1 wires AdbEngine.connect + executeShell here.
        // This mapping shows the semantic shape only.
        val r = AdbEngine.executeShell(request.command, request.timeoutMs)
        return mapAdb(r)
    }
}

/**
 * Contract conformance probe for the persistent capability.
 *
 * STUB: no real stream is opened. It exists to prove that an ADB Backend can
 * satisfy the [ATPersistentBackend] contract shape; the session object would be
 * backed by AdbInteractiveStream in AT.PERSIST-1.
 */
class AdbPersistentBackendProbe(
    private val connection: AdbConnectionRequest,
) : ATPersistentBackend {

    override fun openSession(launch: ATPersistentBackend.Launch): ATOpenSessionResult {
        // STUB: return a not-implemented failure until AT.PERSIST-1.
        return ATOpenSessionResult.Failure("ADB persistent backend not wired (AT.PERSIST-1)")
    }
}