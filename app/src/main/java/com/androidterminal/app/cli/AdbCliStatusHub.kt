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

import android.os.Handler
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())

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

    private fun onTransition(old: AdbRuntimeState, new: AdbRuntimeState) {
        val text = statusLine(old, new) ?: return
        Log.i(TAG, "transition $old -> $new: $text")
        val ids = synchronized(subscribed) { subscribed.toList() }
        if (ids.isEmpty()) return
        // Deliver on the main thread (SharedSessionHandle/observers are main-UI).
        main.post {
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
