// AT Engine API — public execution/session contract (V4-1).
//
// Layer-2 public consumer contract. This module contains ONLY the verified
// execution/session semantic types that a Consumer needs to use AT Engine.
//
// NO Android Context / ADB internals / backend SPI types in this module.
//
// V4-1: Physical extraction of existing V3 verified contracts.
// SEMANTIC_DELTA = NONE (types are moved, not redesigned).

package com.at.engine

/**
 * Outcome of an AT Engine one-shot execution.
 *
 * The core distinction this carries (Canonical V1 §9 / V2 ExecResult
 * semantics): a non-zero *process* exit code is NOT an execution failure.
 * Only a failure of the execution path itself (transport / infrastructure /
 * unsupported request) produces a non-COMPLETED outcome. This lets a consumer
 * trust [ATExecutionResult.stderr] as genuine process stderr.
 */
enum class ATExecutionOutcome {
    /** The execution ran to completion (any device/process exit code). */
    COMPLETED,

    /** The underlying transport/backend failed before a result could be produced. */
    TRANSPORT_ERROR,

    /** The request was not supported by the selected backend. */
    UNSUPPORTED,
}

/**
 * Result of an AT Engine one-shot execution.
 *
 * Three strictly separated channels:
 *  - [stdout] / [stderr] = verbatim process output (the backend's bytes)
 *  - [outcome] / [hasError] / [error] = AT Engine / transport layer
 *  - [exitCode] = process exit code (only meaningful when [outcome]==COMPLETED)
 */
data class ATExecutionResult(
    val outcome: ATExecutionOutcome,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val hasError: Boolean,
    val error: String?,
) {
    /** True when the execution path itself failed (not merely a non-zero exit). */
    val executionFailed: Boolean get() = outcome != ATExecutionOutcome.COMPLETED
}

/**
 * Input to an AT Engine one-shot execution.
 *
 * Minimal for v0: a command line is the only real requirement shared by Local
 * (`sh -c <command>`) and ADB (`shell,v2,raw:<command>`). A structured
 * argv/program form is DEFERRED until a vertical slice needs it.
 *
 * @property command the command line to run (backend interprets how to spawn).
 * @property timeoutMs execution timeout; 0 = no timeout. Whether timeouts are
 *        honored is a backend capability (see [ATOneShotBackend]).
 */
data class ATExecutionRequest(
    val command: String,
    val timeoutMs: Int = 0,
)
