// AT.CLI-0.1A — ADB lifecycle status hub (App/Host layer).
//
// Bridges the single AdbRuntime lifecycle (the ONE lifecycle owner) to the
// Human-visible Local terminal sessions that have actually USED adb.
//
// Responsibilities (Host orchestration only — NOT a lifecycle owner):
//   - subscribes a READ-ONLY AdbRuntimeListener (addLifecycleListener)
//   - tracks which Local sessionIds have used adb (SUBSCRIBE/UNSUBSCRIBE)
//   - on a lifecycle transition, emits a `[adb] ...` HOST/SYSTEM terminal event
//     to exactly the subscribed sessions (via SharedSessionHandle.emitSystemOutput)
//
// It does NOT: create a second AdbRuntime, second recovery owner, second
// endpoint store, or any reconnect/heartbeat. It only OBSERVES the existing
// AdbRuntime state and fans out Human-visible text.
//
// Multi-session rule: a session that never used adb never receives status. A
// session that used adb receives transitions. Closed sessions are unsubscribed
// immediately (no observer/registry leak).

package com.androidterminal.app.cli

import android.util.Log
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.androidterminal.app.AppSessionRegistry

class AdbCliStatusHub(private val registry: AppSessionRegistry) {

    companion object {
        private const val TAG = "AdbCliStatusHub"
    }

    private val subscribed = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private var handle: AdbRuntime.AdbRuntimeListenerHandle? = null

    /** Register the read-only AdbRuntime listener (idempotent). */
    fun start() {
        if (handle != null) return
        handle = AdbRuntime.addLifecycleListener { old, new -> onTransition(old, new) }
        Log.i(TAG, "started (read-only AdbRuntime listener)")
    }

    /** Stop and clear all subscriptions. */
    fun stop() {
        handle?.remove()
        handle = null
        synchronized(subscribed) { subscribed.clear() }
        Log.i(TAG, "stopped")
    }

    /** A Local session used adb: start receiving lifecycle status. */
    fun subscribe(sessionId: Int) {
        if (sessionId <= 0) return
        val added = synchronized(subscribed) { subscribed.add(sessionId) }
        if (added) Log.i(TAG, "subscribe session=$sessionId")
    }

    /** A Local session closed (or never used adb): stop receiving status. */
    fun unsubscribe(sessionId: Int) {
        val removed = synchronized(subscribed) { subscribed.remove(sessionId) }
        if (removed) Log.i(TAG, "unsubscribe session=$sessionId")
    }

    fun isSubscribed(sessionId: Int): Boolean =
        synchronized(subscribed) { sessionId in subscribed }

    /**
     * AT.CLI-0.1B: emit a COMMAND-TIME status line to a specific Local session
     * (the session that is currently running `adb shell <cmd>`). This is the
     * HOST/SYSTEM terminal event path — it does NOT go through the short-lived
     * AdbCliMain process (whose PTY stdout/stderr is not reliably live during
     * ensureReady). It rides the same observer fan-out as [onTransition], so
     * the Human sees it immediately as the Runtime progresses.
     *
     * [status] should be the bare status text (e.g. "checking connection...");
     * this method adds the `[adb] ` prefix and trailing newline.
     *
     * No-op if the session is closed or not in the registry.
     */
    fun emitCommandStatus(sessionId: Int, status: String) {
        if (sessionId <= 0 || status.isEmpty()) return
        val text = "[adb] $status\n"
        Log.i(TAG, "emitCommandStatus session=$sessionId: $status")
        // Synchronous delivery (NOT main.post): the caller is the bridge
        // thread running ensureReady. The observer's onOutput is invoked on
        // THIS thread. SharedSessionHandle.forEachObserver uses a
        // synchronized copy of the observer list, so concurrent
        // register/unregister is safe. The Human UI (MainActivity) renders
        // onOutput on its own thread; the Local PTY reader path (fanOut)
        // already delivers on the PTY reader thread, not main.
        runCatching {
            val h = registry.lookup(sessionId) ?: return
            if (h.isClosed()) {
                unsubscribe(sessionId)
                return
            }
            h.emitSystemOutput(text.toByteArray(Charsets.UTF_8))
        }.onFailure { Log.w(TAG, "emitCommandStatus to $sessionId failed", it) }
    }

    private fun onTransition(old: AdbRuntimeState, new: AdbRuntimeState) {
        val text = statusLine(old, new) ?: return
        Log.i(TAG, "transition $old -> $new: $text")
        val ids = synchronized(subscribed) { subscribed.toList() }
        if (ids.isEmpty()) return
        // Synchronous delivery (NOT main.post): the caller is the AdbRuntime
        // thread that fired the state transition. SharedSessionHandle.forEachObserver
        // is synchronized, so concurrent observer register/unregister is safe.
        // The Human UI (MainActivity) renders onOutput on its own thread; the
        // Local PTY reader path (fanOut) already delivers on the reader thread.
        for (id in ids) {
            runCatching {
                val h = registry.lookup(id) ?: return@runCatching
                if (h.isClosed()) {
                    // Closed session: unsubscribe (no leak, no stale delivery).
                    unsubscribe(id)
                    return@runCatching
                }
                h.emitSystemOutput(text.toByteArray(Charsets.UTF_8))
            }.onFailure { Log.w(TAG, "emit to $id failed", it) }
        }
    }

    /**
     * Map a lifecycle transition to a `[adb] ...` Human status line (or null when
     * there is nothing to surface). Only state transitions produce a line — no
     * duplication on repeated same-state.
     */
    private fun statusLine(old: AdbRuntimeState, new: AdbRuntimeState): String? = when (new) {
        AdbRuntimeState.READY -> when (old) {
            AdbRuntimeState.RECOVERING -> "[adb] connected\n"
            AdbRuntimeState.TRANSPORT_LOST -> "[adb] connected\n"
            else -> "[adb] connected\n"
        }
        AdbRuntimeState.TRANSPORT_LOST -> "[adb] disconnected\n"
        AdbRuntimeState.RECOVERING -> "[adb] reconnecting...\n"
        AdbRuntimeState.RECOVERY_FAILED -> "[adb] disconnected\n"
        AdbRuntimeState.USER_ACTION_REQUIRED ->
            "[adb] user action required: enable Wireless Debugging\n"
        AdbRuntimeState.CONNECTING -> null // in-progress; surfaced via command-time STATUS
        else -> null
    }
}
