// AT Engine API — public session contract (V4-1).
//
// Layer-2 public consumer contract. This module contains ONLY the verified
// session semantic types that a Consumer needs to use AT Engine.
//
// NO Android Context / ADB internals / backend SPI types in this module.
//
// V4-1: Physical extraction of existing V3 verified contracts.
// SEMANTIC_DELTA = NONE (types are moved, not redesigned).

package com.at.engine

/**
 * Minimum lifecycle of a persistent terminal session.
 *
 * Deliberately small (based on T1/I0 verified needs): a session is CREATED,
 * becomes ACTIVE, and terminates via EXITED / FAILED / CLOSED. No extra
 * DETACHED/REATTACHING states are invented (attach/detach = DEFERRED).
 */
enum class ATSessionState {
    /** Being opened (spawn/connect in progress). */
    OPENING,

    /** Open and able to read/write. */
    ACTIVE,

    /** The launched process exited (exit code available). */
    EXITED,

    /** The session failed (backend/transport error, not process exit). */
    FAILED,

    /** Closed by the caller ([ATTerminalSession.close]). */
    CLOSED,
}

/**
 * Terminal events surfaced by a persistent session.
 *
 * The session is a byte-stream contract: output arrives as raw bytes, and
 * terminal lifecycle changes arrive as discrete events. This is the minimum
 * event vocabulary both Local and ADB evidence support.
 *
 * @property data output bytes (the PTY/stream's merged stdout+stderr).
 * @property resized a new terminal size was applied.
 * @property exited the process exited with the given code.
 * @property eof the stream reached EOF without an explicit exit code.
 * @property failed a backend/transport failure occurred.
 * @property closed the session was closed.
 */
sealed class ATSessionEvent {
    data class Data(val bytes: ByteArray) : ATSessionEvent()
    data class Resized(val rows: Int, val cols: Int) : ATSessionEvent()
    data class Exited(val exitCode: Int) : ATSessionEvent()
    object Eof : ATSessionEvent()
    data class Failed(val error: String) : ATSessionEvent()
    object Closed : ATSessionEvent()
}

/**
 * A persistent terminal session (AT Engine domain).
 *
 * This is the logical session object the AT Engine exposes. It aggregates a
 * backend instance and owns the session lifecycle. It is explicitly NOT the
 * backend stream: the backend provides the underlying interactive transport,
 * the session owns the AT-level semantics.
 *
 * Future (DEFERRED): a Terminal Emulator + Screen State will sit BETWEEN the
 * backend bytes and the consumer; this contract does NOT invent their API.
 *
 * IMPLEMENTATION SHAPE = PROVISIONAL (callback-based; may become
 * suspend/Flow later — Canonical V1 §27 + AT.CONTRACT-0 §9).
 */
interface ATTerminalSession {

    /** Current session state. */
    val state: ATSessionState

    /**
     * Write input bytes to the session (keyboard/agent input).
     * @return bytes accepted, or -1 if the session is not writable.
     */
    fun write(bytes: ByteArray): Int

    /**
     * Read output bytes that have arrived (blocking with a timeout).
     * @return bytes, empty on timeout, or null on EOF/error.
     */
    fun read(timeoutMs: Int): ByteArray?

    /**
     * Deliver output events to a consumer. IMPLEMENTATION SHAPE PROVISIONAL.
     * The semantic contract is: the consumer receives bytes + lifecycle events.
     */
    fun setListener(listener: (ATSessionEvent) -> Unit)

    /**
     * Request a new terminal size.
     * @return true when the resize was accepted by the backend.
     */
    fun resize(rows: Int, cols: Int): Boolean

    /**
     * Close the session. Idempotent.
     * @param terminateProcess true = also kill the underlying process/stream.
     */
    fun close(terminateProcess: Boolean = true)
}

/**
 * Result of opening a terminal session.
 */
sealed class ATOpenSessionResult {
    data class Success(val session: ATTerminalSession) : ATOpenSessionResult()
    data class Failure(val reason: String) : ATOpenSessionResult()
}

/**
 * Launch description for a persistent session (V4-1: extracted from
 * ATPersistentBackend.Launch to eliminate SPI leak in public API).
 *
 * SEMANTIC_DELTA = NONE (same fields, same defaults, same semantics).
 * OLD = ATPersistentBackend.Launch
 * NEW = ATSessionLaunch
 */
data class ATSessionLaunch(
    /** Program/command to launch (default "/system/bin/sh" for both). */
    val command: String,
    /** Terminal size at open; 0/0 = backend default. */
    val initialRows: Int = 0,
    val initialCols: Int = 0,
    /** Optional environment overrides (DEFERRED for most providers). */
    val env: Map<String, String> = emptyMap(),
)
