// AT Engine — facade skeleton (DRAFT).
//
// AT.CONTRACT-0 §11: a minimal facade surface so the two execution modes are
// peers and both route through backends. This is a CONTRACT SKELETON only; the
// real runtime orchestration arrives with AT.PERSIST-0/1.
//
// IMPLEMENTATION SHAPE = PROVISIONAL.

package com.at.engine

/**
 * Minimal AT Engine facade.
 *
 * Composes one-shot execution and persistent terminal capabilities, both
 * delegated to injected backends. The facade itself knows nothing about ADB /
 * PTY / process implementation.
 *
 * The backends are injected (composition); they implement the capability
 * contracts. Backend wiring happens at the consumer/composition root, not here.
 */
class ATEngine(
    /** One-shot backend (may be null until a slice wires it). */
    private val oneShot: ATOneShotBackend?,
    /** Persistent backend (may be null until a slice wires it). */
    private val persistent: ATPersistentBackend?,
) {

    /** Run one command through the one-shot backend. */
    fun execute(request: ATExecutionRequest): ATExecutionResult {
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

    /** Open a persistent session through the persistent backend. */
    fun openSession(launch: ATPersistentBackend.Launch): ATOpenSessionResult {
        val b = persistent
            ?: return ATOpenSessionResult.Failure("no persistent backend wired")
        return b.openSession(launch)
    }

    companion object {
        /** An engine with no backends (for tests / pre-wiring state). */
        fun empty(): ATEngine = ATEngine(null, null)
    }
}