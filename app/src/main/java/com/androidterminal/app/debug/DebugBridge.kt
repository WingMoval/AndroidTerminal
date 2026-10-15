// AndroidTerminal V3 — DebugBridge (AT.DEBUG-0, DEBUG BUILD ONLY).
//
// A developer-only, loopback TCP JSONL bridge that lets a PC client (via
// `adb forward tcp:45679 tcp:45679`) ATTACH to a Human-visible TerminalSession
// already open in the app. It is NOT a second Terminal Runtime / ADB execution
// path / AT Engine / hidden session. It is a programmatic FRONTEND over the
// existing SharedSessionHandle:
//
//   PC -> adb forward -> 127.0.0.1:45679 -> DebugBridge
//        -> AppSessionRegistry.lookup(sessionId) -> SharedSessionHandle
//            -> existing ATTerminalSession.write(bytes)
//
//   output/state fan-out: ATTerminalSession (single listener = SharedSessionHandle)
//        -> observers (Human UI + this bridge) -> broadcast to PC clients
//
// SECURITY: loopback-only bind (127.0.0.1), debug-build gate (BuildConfig.DEBUG),
// single client. NO nonce/handshake/authorization (structural security only).
//
// PC_ADB_ROLE = DEBUG_TRANSPORT_ONLY (adb forward is just the PC->loopback tunnel;
// it does NOT execute terminal commands). The embedded ADB Engine executes.

package com.androidterminal.app.debug

import android.util.Base64
import android.util.Log
import com.androidterminal.app.AppSessionRegistry
import com.androidterminal.app.InputSource
import com.androidterminal.app.SessionInfo
import com.androidterminal.app.SessionObserver
import com.androidterminal.app.SharedSessionHandle
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DebugBridge(
    private val registry: AppSessionRegistry,
    private val port: Int = DEFAULT_PORT,
) {

    companion object {
        const val DEFAULT_PORT = 45679
        private const val TAG = "AtDebugBridge"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    // Single ordered writer thread for all client broadcasts (fixes
    // NetworkOnMainThreadException + guarantees JSONL line ordering).
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "at-debug-send").apply { isDaemon = true }
    }

    private val clients = ArrayList<ClientWriter>()
    private val attached = LinkedHashMap<Int, MutableSet<ClientWriter>>() // sessionId -> writers
    private val writerObservers = LinkedHashMap<ClientWriter, MutableList<BridgeObserver>>()

    /** Per-session observer: forwards shared-session events to attached clients. */
    private inner class BridgeObserver(val sessionId: Int) : SessionObserver {
        override fun onOutput(bytes: ByteArray) {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            broadcastTo(sessionId, DebugProtocol.output(sessionId, b64))
        }

        override fun onStateChange(state: ATSessionEvent) {
            val stateStr = when (state) {
                is ATSessionEvent.Closed -> {
                    broadcastTo(sessionId, DebugProtocol.closed(sessionId))
                    return
                }
                is ATSessionEvent.Resized -> "RESIZED"
                is ATSessionEvent.Exited -> "EXITED"
                is ATSessionEvent.Failed -> "FAILED"
                else -> "STATE"
            }
            broadcastTo(sessionId, DebugProtocol.state(sessionId, stateStr))
        }

        override fun onInputActivity(source: InputSource, bytes: ByteArray) {
            val text = String(bytes, StandardCharsets.UTF_8)
            broadcastTo(sessionId, DebugProtocol.input(sessionId, source.name, text))
        }
    }

    val status: String
        get() = when {
            !running.get() -> "OFF"
            clients.isEmpty() -> "LISTENING"
            else -> "CONNECTED"
        }

    /** Port for UI display (debug builds only). */
    fun portForUi(): Int = port

    /** Bind loopback + start accept loop. Returns false on failure. */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "at-debug-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "DebugBridge listening on 127.0.0.1:$port")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "DebugBridge bind failed: ${t.message}")
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        val copy = synchronized(clients) { clients.toList() }
        for (c in copy) c.close()
        sendExecutor.shutdown()
        Log.i(TAG, "DebugBridge stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                if (!running.get()) break else continue
            }
            Thread({ handleClient(socket) }, "at-debug-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val writer = ClientWriter(socket)
        synchronized(clients) { clients.add(writer) }
        runCatching { writer.send(DebugProtocol.hello()) }
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            while (running.get()) {
                val line = try {
                    reader.readLine()
                } catch (_: Exception) {
                    break // socket reset/closed by client
                } ?: break
                val msg = DebugProtocol.parseClientLine(line) ?: continue
                try {
                    when (msg) {
                        is DebugProtocol.Ping -> writer.send(DebugProtocol.pong())
                        is DebugProtocol.ListSessions ->
                            writer.send(DebugProtocol.sessions(listSnapshot()))
                        is DebugProtocol.Attach -> handleAttach(writer, msg.sessionId)
                        is DebugProtocol.Detach -> handleDetach(writer)
                        is DebugProtocol.Write -> handleWrite(writer, msg.sessionId,
                            msg.text?.toByteArray(StandardCharsets.UTF_8))
                        is DebugProtocol.WriteRaw -> handleWrite(writer, msg.sessionId,
                            Base64.decode(msg.base64, Base64.NO_WRAP))
                    }
                } catch (t: Throwable) {
                    runCatching { writer.send(DebugProtocol.error(t.message ?: "handler error")) }
                }
            }
        } finally {
            // Disconnect: remove writer + detach from all sessions; session NOT closed.
            synchronized(clients) { clients.remove(writer) }
            val ids: List<Int>
            synchronized(attached) {
                ids = attached.keys.filter { attached[it]?.contains(writer) == true }.toList()
            }
            for (id in ids) {
                detachWriterFromSession(writer, id)
            }
            // Remove this writer's observers from the handles they were attached to.
            val obs: List<BridgeObserver>? = synchronized(attached) { writerObservers.remove(writer) }
            if (obs != null) {
                for (o in obs) {
                    registry.lookup(o.sessionId)?.removeObserver(o)
                }
            }
            writer.close()
        }
    }

    /** Remove a writer from a session's attached set (observer removal is separate). */
    private fun detachWriterFromSession(writer: ClientWriter, id: Int) {
        synchronized(attached) {
            val set = attached[id] ?: return
            set.remove(writer)
            if (set.isEmpty()) attached.remove(id)
        }
    }

    private fun handleAttach(writer: ClientWriter, sessionId: String) {
        val handle: SharedSessionHandle? = if (sessionId == "active") {
            registry.resolveActive()
        } else {
            sessionId.toIntOrNull()?.let { registry.lookup(it) }
        }
        if (handle == null) {
            writer.send(DebugProtocol.error("no such session: $sessionId"))
            return
        }
        val observer = BridgeObserver(handle.sessionId)
        // Attach: register observer on the EXISTING session handle (no new session).
        handle.addObserver(observer)
        synchronized(attached) {
            attached.getOrPut(handle.sessionId) { LinkedHashSet() }.add(writer)
            writerObservers.getOrPut(writer) { ArrayList() }.add(observer)
        }
        writer.send(DebugProtocol.attached(handle.sessionId))
        Log.i(TAG, "client attached to session ${handle.sessionId}")
    }

    private fun handleDetach(writer: ClientWriter) {
        val ids: List<Int>
        synchronized(attached) {
            ids = attached.keys.filter { attached[it]?.contains(writer) == true }.toList()
        }
        for (id in ids) {
            detachWriterFromSession(writer, id)
            writer.send(DebugProtocol.detached(id))
        }
    }

    private fun handleWrite(writer: ClientWriter, sessionId: Int, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            writer.send(DebugProtocol.error("empty write"))
            return
        }
        val handle = registry.lookup(sessionId)
        if (handle == null) {
            writer.send(DebugProtocol.error("no such session: $sessionId"))
            return
        }
        if (handle.isClosed()) {
            writer.send(DebugProtocol.rejected("SESSION_CLOSED"))
            return
        }
        // Write into the EXISTING session (serialized path). No new execution context.
        val ok = handle.write(InputSource.DEBUG, bytes)
        writer.send(if (ok) DebugProtocol.accepted(sessionId)
                    else DebugProtocol.rejected("SESSION_CLOSED"))
    }

    private fun listSnapshot(): List<SessionInfoJson> =
        registry.list().map { s ->
            SessionInfoJson(
                sessionId = s.sessionId,
                backendKind = s.backendKind.name,
                state = s.state.name,
                isActivePage = s.isActivePage,
                attachedClientCount = attached[s.sessionId]?.size ?: 0,
            )
        }

    private fun broadcastTo(sessionId: Int, line: String) {
        val targets = synchronized(attached) { attached[sessionId]?.toList() ?: emptyList() }
        if (targets.isEmpty()) return
        sendExecutor.execute {
            val dead = ArrayList<ClientWriter>()
            for (c in targets) {
                if (!c.send(line)) dead.add(c)
            }
            for (d in dead) {
                synchronized(attached) {
                    val set = attached[sessionId]
                    if (set != null) set.remove(d)
                }
                d.close()
            }
        }
    }

    private inner class ClientWriter(private val socket: Socket) {
        private val out: PrintWriter =
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)

        @Synchronized
        fun send(line: String): Boolean {
            return try {
                out.println(line)
                out.flush()
                true
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            runCatching { out.close() }
            runCatching { socket.close() }
        }
    }
}
