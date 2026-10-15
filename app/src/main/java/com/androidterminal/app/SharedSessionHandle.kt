// AndroidTerminal V3 — Shared Session Handle (AT.DEBUG-0, App/Host layer).
//
// A thin Host-layer wrapper around ONE existing ATTerminalSession. It is NOT an
// execution engine / NOT a second session. It provides:
//
//   - a single session listener that FANS OUT output/state events to
//     Human observers AND Debug observers (RX_OWNER stays 1: the session has
//     exactly one listener = this handle).
//   - a SERIALIZED write path so Human and Debug writes never byte-interleave
//     (each write(bytes) is one atomic unit on a single writer thread).
//   - input-activity events (source = HUMAN / DEBUG) for UI annotation.
//
// The session lifecycle authority remains the Human page (SessionPage /
// AppTerminal). This handle only references the existing session; it never
// creates, owns, or destroys it.
//
// SessionBackendKind and the handle live in :app (Host domain), NOT in
// :at-engine. AT Engine public contract is unchanged.

package com.androidterminal.app

import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Source of an input write into the shared session. */
enum class InputSource { HUMAN, DEBUG }

/** A fan-out observer of the shared session (Human UI / Debug bridge). */
interface SessionObserver {
    fun onOutput(bytes: ByteArray) {}
    fun onStateChange(state: ATSessionEvent) {}
    fun onInputActivity(source: InputSource, bytes: ByteArray) {}
}

/**
 * Host-layer shared handle over ONE existing ATTerminalSession.
 *
 * @param sessionId stable app-level session id (SessionPage.pageId).
 * @param backendKind the provider identity of this session.
 * @param session the existing ATTerminalSession (created by the Human page).
 */
class SharedSessionHandle(
    val sessionId: Int,
    val backendKind: SessionBackendKind,
    private val session: ATTerminalSession,
) {

    private val observers = LinkedHashSet<SessionObserver>()
    private val writeLock = Object()
    private val writeSeq = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "at-shared-write").apply { isDaemon = true }
    }

    /**
     * Register the single session listener. This handle is the ONLY listener on
     * the session; it fans out to all registered observers. Called once after
     * the session is open.
     */
    fun attachSessionListener() {
        session.setListener { ev -> fanOut(ev) }
    }

    fun addObserver(o: SessionObserver): Boolean = synchronized(observers) { observers.add(o) }
    fun removeObserver(o: SessionObserver): Boolean = synchronized(observers) { observers.remove(o) }

    fun state(): ATSessionState = session.state
    fun isClosed(): Boolean = closed.get() || session.state == ATSessionState.CLOSED

    /**
     * Serialized write: enqueues a single non-interleaving write unit. Human
     * and Debug writes both go through here; each write(bytes) is atomic.
     * Also emits an input-activity event to observers (for UI annotation).
     *
     * @return true when accepted, false when the session is closed.
     */
    fun write(source: InputSource, bytes: ByteArray): Boolean {
        if (closed.get() || session.state == ATSessionState.CLOSED) return false
        val snapshot = bytes.copyOf()
        writer.execute {
            val n = synchronized(writeLock) {
                writeSeq.incrementAndGet()
                runCatching { session.write(snapshot) }.getOrDefault(-1)
            }
            if (n < 0) {
                markClosed()
            }
        }
        // Input-activity is a local UI annotation (NOT remote stdout).
        notifyInputActivity(source, snapshot)
        return true
    }

    fun resize(rows: Int, cols: Int): Boolean =
        if (closed.get()) false else runCatching { session.resize(rows, cols) }.getOrDefault(false)

    /** Close the underlying session (Human lifecycle authority calls this). */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { session.close() }
        }
    }

    private fun markClosed() {
        if (closed.compareAndSet(false, true)) {
            notifyState(ATSessionEvent.Closed)
        }
    }

    private fun fanOut(ev: ATSessionEvent) {
        when (ev) {
            is ATSessionEvent.Data -> {
                val b = ev.bytes
                forEachObserver { it.onOutput(b) }
            }
            else -> notifyState(ev)
        }
    }

    private fun notifyState(ev: ATSessionEvent) {
        if (ev is ATSessionEvent.Closed) closed.set(true)
        forEachObserver { it.onStateChange(ev) }
    }

    private fun notifyInputActivity(source: InputSource, bytes: ByteArray) {
        forEachObserver { it.onInputActivity(source, bytes) }
    }

    private fun forEachObserver(block: (SessionObserver) -> Unit) {
        val copy = synchronized(observers) { observers.toList() }
        for (o in copy) {
            try {
                block(o)
            } catch (_: Throwable) {
                // Observer fault isolation: one observer must not break others.
            }
        }
    }
}
