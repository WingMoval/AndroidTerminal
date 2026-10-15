// AndroidTerminal V3 — App Session Registry (AT.DEBUG-0, App/Host layer).
//
// A process-scoped, thin registry mapping stable app-level sessionId ->
// SharedSessionHandle (which wraps the existing Human-created ATTerminalSession).
//
// It is Host orchestration, NOT the AT Engine and NOT a session owner:
//   - does NOT discover / connect / pair / manage ADB
//   - does NOT manage TLS or execute ADB protocol
//   - does NOT create or close sessions (Human page = lifecycle authority)
//
// DebugBridge uses it to list/attach Human-visible sessions without creating
// hidden sessions.

package com.androidterminal.app

/** A snapshot of a registered session for listing (no internals exposed). */
data class SessionInfo(
    val sessionId: Int,
    val backendKind: SessionBackendKind,
    val state: com.at.engine.ATSessionState,
    val isActivePage: Boolean,
    val attachedClientCount: Int,
)

/**
 * Process-scoped registry of Human-visible shared sessions.
 *
 * @param activeProvider returns the currently active page's sessionId (or null).
 */
class AppSessionRegistry(private val activeProvider: () -> Int?) {

    private val handles = LinkedHashMap<Int, SharedSessionHandle>()

    fun register(handle: SharedSessionHandle) {
        synchronized(handles) { handles[handle.sessionId] = handle }
    }

    fun unregister(sessionId: Int) {
        synchronized(handles) { handles.remove(sessionId) }
    }

    fun lookup(sessionId: Int): SharedSessionHandle? =
        synchronized(handles) { handles[sessionId] }

    /** Resolve "active" to the current active page's session (or null). */
    fun resolveActive(): SharedSessionHandle? {
        val id = activeProvider() ?: return null
        return lookup(id)
    }

    fun list(): List<SessionInfo> =
        synchronized(handles) {
            handles.values.map { h ->
                SessionInfo(
                    sessionId = h.sessionId,
                    backendKind = h.backendKind,
                    state = h.state(),
                    isActivePage = activeProvider() == h.sessionId,
                    attachedClientCount = 0, // populated by DebugBridge
                )
            }
        }
}
