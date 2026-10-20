// AT Engine SPI — backend capability contracts (V4-1).
//
// Layer-3/4 implementation contract. This module contains ONLY the backend
// SPI interfaces that concrete backends implement.
//
// NO Android Context / ADB internals / concrete backend implementations
// in this module.
//
// V4-1: Physical extraction of existing V3 backend SPI.
// SEMANTIC_DELTA = NONE (interfaces are moved, not redesigned).

package com.at.engine

/**
 * One-shot execution backend capability.
 *
 * Local mapping: ProcessBuilder -> /system/bin/sh -c (ATsh.T0/T0.5).
 * ADB mapping: AdbEngine.executeShell -> shell,v2,raw (V3 ADB Engine).
 */
interface ATOneShotBackend {
    /** Execute one command and block until it completes or fails. */
    fun execute(request: ATExecutionRequest): ATExecutionResult
}

/**
 * Persistent terminal backend capability.
 *
 * Local mapping: Local PTY (posix_openpt/fork/exec -> sh -i) (ATsh.T1).
 * ADB mapping: AdbInteractiveStream -> shell,v2,pty (ADB.I0 / V3 primitive).
 *
 * The backend provides the interactive byte transport + process lifecycle.
 * It does NOT provide Terminal Emulator / Screen State (AT domain, deferred).
 *
 * V4-1: `Launch` nested class replaced by `ATSessionLaunch` from `:at-engine-api`
 * (eliminates SPI leak in public API).
 */
interface ATPersistentBackend {

    /**
     * Open a persistent session.
     * Implementations return [ATOpenSessionResult.Success] with a session
     * object that wraps this backend's transport, or [ATOpenSessionResult.Failure].
     */
    fun openSession(launch: ATSessionLaunch): ATOpenSessionResult
}
