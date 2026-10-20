// AT Engine API — public engine contract (V4-1).
//
// Layer-2 public consumer contract. This module contains ONLY the verified
// engine facade that a Consumer needs to use AT Engine.
//
// NO Android Context / ADB internals / backend SPI types in this module.
//
// V4-1: ATEngine split into interface (API) + implementation (engine).
// SEMANTIC_DELTA = NONE (same public surface: execute + openSession).

package com.at.engine

/**
 * AT Engine public contract (Layer-2).
 *
 * Composes one-shot execution and persistent terminal capabilities, both
 * delegated to injected backends. The facade itself knows nothing about ADB /
 * PTY / process implementation.
 *
 * The backends are injected (composition); they implement the capability
 * contracts. Backend wiring happens at the consumer/composition root, not here.
 *
 * V4-1: Split from concrete class into interface + implementation.
 * - `:at-engine-api` owns this interface (public contract).
 * - `:at-engine` owns `DefaultATEngine` (implementation).
 */
interface ATEngine {

    /** Run one command through the one-shot backend. */
    fun execute(request: ATExecutionRequest): ATExecutionResult

    /** Open a persistent session through the persistent backend. */
    fun openSession(launch: ATSessionLaunch): ATOpenSessionResult

    companion object {
        // V4-1: `empty()` factory removed from API interface.
        // Use `com.at.engine.impl.DefaultATEngine(null, null)` directly
        // (requires `:at-engine` dependency, which is fine for tests/internal use).
    }
}
