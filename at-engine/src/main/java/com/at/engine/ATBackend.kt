// AT Engine — backend capability contracts (DRAFT).
//
// AT.CONTRACT-0 §7. The AT Engine consumes backends through SMALL capability
// interfaces (composition), not one giant Backend interface:
//
//   ATEngine
//     ├─ ATOneShotBackend        (one-shot execution)
//     └─ ATPersistentBackend     (persistent terminal session)
//
// A provider may implement one or both. Local and ADB backends must be able to
// implement these naturally (AT.CONTRACT-0 §8 Provider Reality Check).
//
// IMPLEMENTATION SHAPE = PROVISIONAL. NOT frozen.

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
 */
interface ATPersistentBackend {

    /** Launch description for a persistent session. */
    data class Launch(
        /** Program/command to launch (default "/system/bin/sh" for both). */
        val command: String,
        /** Terminal size at open; 0/0 = backend default. */
        val initialRows: Int = 0,
        val initialCols: Int = 0,
        /** Optional environment overrides (DEFERRED for most providers). */
        val env: Map<String, String> = emptyMap(),
    )

    /**
     * Open a persistent session.
     * Implementations return [ATOpenSessionResult.Success] with a session
     * object that wraps this backend's transport, or [ATOpenSessionResult.Failure].
     */
    fun openSession(launch: Launch): ATOpenSessionResult
}