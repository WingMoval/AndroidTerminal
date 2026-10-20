// AT Engine — default implementation (V4-1).
//
// Layer-3 engine implementation. This module contains the concrete engine
// that delegates to injected backends.
//
// V4-1: Extracted from the old concrete ATEngine class in :at-engine.
// SEMANTIC_DELTA = NONE (same behavior, same null-handling).

package com.at.engine.impl

import com.at.engine.ATEngine
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionResult
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATOneShotBackend
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine

/**
 * Default AT Engine implementation (Layer-3).
 *
 * Composes one-shot execution and persistent terminal capabilities, both
 * delegated to injected backends.
 *
 * V4-1: Extracted from the old concrete ATEngine class.
 * SEMANTIC_DELTA = NONE (same behavior, same null-handling).
 */
class DefaultATEngine(
    /** One-shot backend (may be null until a slice wires it). */
    private val oneShot: ATOneShotBackend?,
    /** Persistent backend (may be null until a slice wires it). */
    private val persistent: ATPersistentBackend?,
) : ATEngine {

    override fun execute(request: ATExecutionRequest): ATExecutionResult {
        val b = oneShot
            ?: return ATExecutionResult(
                outcome = ATExecutionOutcome.UNSUPPORTED,
                stdout = "",
                stderr = "",
                exitCode = -1,
                hasError = true,
                error = "no one-shot backend wired",
            )
        return b.execute(request)
    }

    override fun openSession(launch: ATSessionLaunch): ATOpenSessionResult {
        val b = persistent
            ?: return ATOpenSessionResult.Failure("no persistent backend wired")
        return b.openSession(launch)
    }
}

