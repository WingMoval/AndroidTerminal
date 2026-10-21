// AT.CLI-0.1A — ADB lifecycle status hub (read-only AdbRuntime observer).
//
// Bridges the single AdbRuntime lifecycle (the ONE lifecycle owner) to the
// product's Human-facing sessions:
//   - subscribes a READ-ONLY AdbRuntimeListener (via the composition
//     control facade — the hub itself does NOT own the lifecycle)
//   - fans out Human-visible `[adb] ...` status lines to subscribed Local
//     sessions (Host/SYSTEM output)
//
// It does NOT: create a second AdbRuntime, second recovery owner, second
// transport observer owner. It only observes the existing single AdbRuntime
// state and fans out Human-visible text.
//
// V4-4.1: observes via AppRuntime.adbControl (composition facade) instead of
// a direct AdbRuntime import; status mapping uses the composition-owned
// ATAdbReadinessState semantic type. Status line semantics UNCHANGED.

package com.androidterminal.app.cli

import android.util.Log
import com.at.composition.android.ATAdbLifecycleListenerHandle
import com.at.composition.android.ATAdbReadinessState
import com.androidterminal.app.AppRuntime
import com.androidterminal.app.AppSessionRegistry

class AdbCliStatusHub(private val registry: AppSessionRegistry) {

    companion object {
        private const val TAG = "AdbCliStatusHub"
    }

    private val subscribed = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private var handle: ATAdbLifecycleListenerHandle? = null

    /** Register the read-only AdbRuntime listener (idempotent). */
    fun start() {
        if (handle != null) return
        // V4-4.1: observes via the composition semantic surface (the facade
        // projects the provider state into ATAdbReadinessState internally).
        handle = AppRuntime.adbControl.addLifecycleListener { old, new ->
            onTransition(old, new)
        }
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

    /** Whether a session is currently subscribed. */
    fun isSubscribed(sessionId: Int): Boolean = subscribed.contains(sessionId)

    /**
     * AT.CLI-0.1B: command-time ADB status line (Host/SYSTEM event) to ONE
     * specific Local session. Does NOT go through the short-lived AdbCliMain
     * process. The Human sees it immediately as ensureReady progresses.
     */
    fun emitCommandStatus(sessionId: Int, status: String) {
        if (sessionId <= 0) return
        val text = status + "\n"
        runCatching {
            val h = registry.lookup(sessionId)
                ?: return
            h.emitSystemOutput(text.toByteArray(Charsets.UTF_8))
        }.onFailure { Log.w(TAG, "emitCommandStatus to $sessionId failed", it) }
    }

    private fun onTransition(old: ATAdbReadinessState, new: ATAdbReadinessState) {
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
     * duplication on repeated same-state. (Same mapping as pre-V4-4.1, now over
     * the composition semantic state.)
     */
    private fun statusLine(old: ATAdbReadinessState, new: ATAdbReadinessState): String? = when (new) {
        ATAdbReadinessState.READY -> when (old) {
            ATAdbReadinessState.RECOVERING -> "[adb] connected\n"
            ATAdbReadinessState.TRANSPORT_LOST -> "[adb] connected\n"
            else -> "[adb] connected\n"
        }
        ATAdbReadinessState.TRANSPORT_LOST -> "[adb] disconnected\n"
        ATAdbReadinessState.RECOVERING -> "[adb] reconnecting...\n"
        ATAdbReadinessState.RECOVERY_FAILED -> "[adb] disconnected\n"
        ATAdbReadinessState.USER_ACTION_REQUIRED ->
            "[adb] user action required: enable Wireless Debugging\n"
        ATAdbReadinessState.CONNECTING -> null // in-progress; surfaced via command-time STATUS
        else -> null
    }
}
