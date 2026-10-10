// AT Engine — persistent terminal / session domain types (DRAFT).
//
// AT.CONTRACT-0. Canonical V1 §5/§11/§12: Persistent Terminal is a first-class
// AT Engine capability. This file defines the minimum session lifecycle and
// byte-stream semantics shared by Local (ATsh.T1) and ADB (ADB.I0) evidence.
//
// IMPLEMENTATION SHAPE = PROVISIONAL. NOT frozen. No Emulator / Screen State
// API is invented here (those are DEFERRED and must not pollute the v0 session
// contract).

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