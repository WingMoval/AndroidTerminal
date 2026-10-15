// AndroidTerminal V3 — Debug Protocol (AT.DEBUG-0).
//
// Pure, Android-free JSONL codec for the developer-only DebugBridge. NOT an AT
// public contract, NOT a future AG protocol, NOT an AT.CLI protocol, NOT a
// product IPC contract.
//
// DEBUG_PROTOCOL_STATUS = DEVELOPER_ONLY / NON_CANONICAL
//
// Transport: one JSON object per line (\n-delimited), UTF-8, no framing.
// Minimal messages: hello / list_sessions / attach / detach / write / ping
// + server events: sessions / attached / detached / input / output / state /
// closed / error / pong.

package com.androidterminal.app.debug

import org.json.JSONArray
import org.json.JSONObject

object DebugProtocol {

    const val PROTOCOL_ID = "at-debug-0"

    // ---- Client -> device -------------------------------------------------

    sealed class ClientMsg

    data object Ping : ClientMsg()
    data object ListSessions : ClientMsg()
    data class Attach(val sessionId: String) : ClientMsg() // "active" or "<int>"
    data object Detach : ClientMsg()
    data class Write(val sessionId: Int, val text: String?) : ClientMsg()
    data class WriteRaw(val sessionId: Int, val base64: String) : ClientMsg()

    /** Parse one client line. Returns null on blank/unrecognized. */
    fun parseClientLine(line: String): ClientMsg? {
        val t = line.trim()
        if (t.isEmpty()) return null
        return try {
            val o = JSONObject(t)
            when (o.optString("type")) {
                "ping" -> Ping
                "list" -> ListSessions
                "attach" -> Attach(o.optString("sessionId", "active"))
                "detach" -> Detach
                "write" -> Write(
                    sessionId = o.optInt("sessionId", -1),
                    text = o.optString("text", null),
                )
                "write_raw" -> WriteRaw(o.optInt("sessionId", -1), o.optString("base64"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Device -> client -------------------------------------------------

    fun hello(): String = enc {
        put("type", "hello")
        put("protocol", PROTOCOL_ID)
    }

    fun sessions(list: List<SessionInfoJson>): String = enc {
        put("type", "sessions")
        put("sessions", JSONArray().apply {
            for (s in list) {
                put(JSONObject().apply {
                    put("sessionId", s.sessionId)
                    put("backendKind", s.backendKind)
                    put("state", s.state)
                    put("isActivePage", s.isActivePage)
                    put("attachedClientCount", s.attachedClientCount)
                })
            }
        })
    }

    fun attached(sessionId: Int): String = enc {
        put("type", "attached")
        put("sessionId", sessionId)
    }

    fun detached(sessionId: Int): String = enc {
        put("type", "detached")
        put("sessionId", sessionId)
    }

    /** Input activity (local annotation, NOT remote stdout). */
    fun input(sessionId: Int, source: String, text: String?): String = enc {
        put("type", "input")
        put("sessionId", sessionId)
        put("source", source)
        if (text != null) put("text", text)
    }

    /** Remote output bytes (base64 to keep JSONL safe for raw terminal bytes). */
    fun output(sessionId: Int, base64: String): String = enc {
        put("type", "output")
        put("sessionId", sessionId)
        put("data", base64)
    }

    fun state(sessionId: Int, state: String): String = enc {
        put("type", "state")
        put("sessionId", sessionId)
        put("state", state)
    }

    fun closed(sessionId: Int): String = enc {
        put("type", "closed")
        put("sessionId", sessionId)
    }

    fun pong(): String = enc { put("type", "pong") }

    fun error(message: String): String = enc {
        put("type", "error")
        put("message", message)
    }

    fun accepted(sessionId: Int): String = enc {
        put("type", "accepted")
        put("sessionId", sessionId)
    }

    fun rejected(message: String): String = enc {
        put("type", "rejected")
        put("message", message)
    }

    private fun enc(block: JSONObject.() -> Unit): String = JSONObject().apply(block).toString()
}

/** Minimal session info for the protocol (decoupled from App types). */
data class SessionInfoJson(
    val sessionId: Int,
    val backendKind: String,
    val state: String,
    val isActivePage: Boolean,
    val attachedClientCount: Int,
)
