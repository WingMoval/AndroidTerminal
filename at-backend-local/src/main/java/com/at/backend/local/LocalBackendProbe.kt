// Local Backend — contract conformance STUB (AT.CONTRACT-0).
//
// STUB / CONTRACT PROBE. NOT a runtime implementation.
//
// Purpose: prove the AT Engine backend contract can be implemented naturally by
// a Local provider (V2 ATsh LocalShellExecutor / LocalPtySession evidence).
//
// Real Local Backend wiring = AT.PERSIST-0 (DEFERRED).

package com.at.backend.local

import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATOneShotBackend
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend

/**
 * Contract conformance probe for the one-shot capability.
 *
 * Local mapping (ATsh.T0/T0.5 evidence): ProcessBuilder -> /system/bin/sh -c.
 * STUB: no real process is spawned here; [execute] returns UNSUPPORTED until
 * AT.PERSIST-0 wires LocalShellExecutor.
 */
class LocalOneShotBackendProbe : ATOneShotBackend {
    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        // STUB: AT.PERSIST-0 wires the migrated Local process executor here.
        return ATExecutionResult(
            outcome = ATExecutionOutcome.UNSUPPORTED,
            stdout = "",
            stderr = "",
            exitCode = -1,
            hasError = true,
            error = "Local one-shot backend not wired (AT.PERSIST-0)",
        )
    }
}

/**
 * Contract conformance probe for the persistent capability.
 *
 * Local mapping (ATsh.T1 evidence): posix_openpt -> fork -> /system/bin/sh -i.
 * STUB: no real PTY is opened; the session object would be backed by a Local
 * PTY session in AT.PERSIST-0.
 */
class LocalPersistentBackendProbe : ATPersistentBackend {
    override fun openSession(launch: ATPersistentBackend.Launch): ATOpenSessionResult {
        // STUB: AT.PERSIST-0 wires the Local PTY provider here.
        return ATOpenSessionResult.Failure("Local persistent backend not wired (AT.PERSIST-0)")
    }
}