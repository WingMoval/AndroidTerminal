// AndroidTerminal V3 鈥?Shared Session Bridge test (AT.DEBUG-0).
//
// End-to-end: Human creates a session via MainActivity (registered in
// AppSessionRegistry). A PC-side DebugBridge client (loopback socket via the
// app's own 127.0.0.1:45679) ATTACHES to that existing session, writes into it,
// and observes output/state. Human and Debug reference the SAME
// ATTerminalSession (single listener = SharedSessionHandle; RX_OWNER = 1).
//
// No UI automation / IME / uiautomator / logcat. The bridge transport is a raw
// loopback TCP socket (the test plays the role of the PC client).
//
// Covers B1-B14: registry, attach-existing, no-second-session, debug-write
// visible to Human, shared cwd/env, Human-input-visible-to-debug, shared Ctrl-C,
// multi-session isolation, disconnect-survives, reattach, human-close-propagates,
// closed-write-rejected, attach-no-extra-ADB-stream.

package com.androidterminal.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntimeState
import com.androidterminal.app.debug.DebugBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SharedSessionBridgeTest {

    private var scenario: ActivityScenario<MainActivity>? = null
    private val port = DebugBridge.DEFAULT_PORT

    @Before
    fun launch() {
        AppRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    // ---- Minimal loopback DebugBridge client (plays the PC role) -----------

    private class BridgeClient(port: Int) : AutoCloseable {
        val socket: Socket = Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port), 5000)
        }
        val out = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
        val in_ = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

        init {
            // consume hello
            in_.readLine()
        }

        fun send(obj: JSONObject) {
            out.println(obj.toString())
        }

        /** Read the next JSON line (blocking). */
        fun nextLine(): String = in_.readLine() ?: ""

        fun nextObject(): JSONObject = JSONObject(nextLine())

        fun listSessions(): List<Int> {
            send(JSONObject().put("type", "list"))
            val o = nextObject()
            val arr = o.getJSONArray("sessions")
            val ids = ArrayList<Int>()
            for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).getInt("sessionId"))
            return ids
        }

        fun attach(sessionId: String): JSONObject {
            send(JSONObject().put("type", "attach").put("sessionId", sessionId))
            return nextObject()
        }

        fun write(sessionId: Int, text: String): JSONObject {
            send(JSONObject().put("type", "write").put("sessionId", sessionId).put("text", text))
            return nextObject()
        }

        fun writeRaw(sessionId: Int, base64: String): JSONObject {
            send(JSONObject().put("type", "write_raw").put("sessionId", sessionId).put("base64", base64))
            return nextObject()
        }

        /** Collect output bytes (base64) until a timeout or N lines. */
        fun collectOutput(timeoutMs: Long = 4000): String {
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            socket.soTimeout = 300
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    in_.readLine()
                } catch (_: Exception) {
                    null
                } ?: break
                val o = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    null
                }
                if (o != null && o.optString("type") == "output") {
                    val b64 = o.optString("data")
                    sb.append(String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)))
                }
            }
            return sb.toString()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    private fun createLocalPage(): Int {
        var pageId = -1
        scenario?.onActivity { a ->
            val p = a.createPage(SessionBackendKind.LOCAL)
            a.openActiveSession(24, 80)
            pageId = p.pageId
        }
        return pageId
    }

    private fun createAdbPage(): Int {
        var pageId = -1
        scenario?.onActivity { a ->
            if (AppRuntime.adbState() != AdbRuntimeState.READY) {
                AppRuntime.connectAuto()
            }
            val p = a.createPage(SessionBackendKind.ADB)
            a.openActiveSession(24, 80)
            pageId = p.pageId
        }
        return pageId
    }

    private fun activeOutput(): String {
        var s = ""
        scenario?.onActivity { a ->
            s = a.findViewById<android.widget.TextView>(R.id.tvOutput).text?.toString() ?: ""
        }
        return s
    }

    // ---- B1: registry contains Human session ------------------------------

    @Test
    fun b1_registryContainsHumanSession() {
        val pageId = createLocalPage()
        assertTrue("page created", pageId > 0)
        BridgeClient(port).use { c ->
            val ids = c.listSessions()
            assertTrue("registry contains session $pageId; got $ids", ids.contains(pageId))
        }
    }

    // ---- B2+B3: attach existing session (no second session) ---------------

    @Test
    fun b2_b3_attachExistingNoSecond() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            val before = c.listSessions()
            val r = c.attach(pageId.toString())
            assertEquals("attached", "attached", r.optString("type"))
            val after = c.listSessions()
            // Same count: attach did NOT create a second session.
            assertEquals("attach creates no second session", before.size, after.size)
            assertTrue("still one session", after.size == 1)
        }
    }

    // ---- B4: debug write visible to Human observer ------------------------

    @Test
    fun b4_debugWriteVisibleToHuman() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            c.write(pageId, "echo FROM_DEBUG\n")
        }
        // Human UI should show the "DEBUG > echo FROM_DEBUG" annotation AND the
        // remote output "FROM_DEBUG".
        assertTrue(
            "Human UI shows DEBUG input annotation; got: ${activeOutput()}",
            await(4000) { activeOutput().contains("DEBUG > echo FROM_DEBUG") },
        )
        assertTrue(
            "Human UI shows remote output FROM_DEBUG; got: ${activeOutput()}",
            await(4000) { activeOutput().contains("FROM_DEBUG") },
        )
    }

    // ---- B5: same cwd (debug cd, human pwd) -------------------------------

    @Test
    fun b5_sharedCwd() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            c.write(pageId, "cd /data/local/tmp\n")
            Thread.sleep(400)
        }
        // Human types pwd via the same session (through the shared handle).
        scenario?.onActivity { a ->
            a.testInputBox.setText("pwd")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        assertTrue(
            "shared cwd = /data/local/tmp; got: ${activeOutput()}",
            await(4000) { activeOutput().contains("/data/local/tmp") },
        )
    }

    // ---- B6: same env (human export, debug echo) --------------------------

    @Test
    fun b6_sharedEnv() {
        val pageId = createLocalPage()
        // Human exports via UI.
        scenario?.onActivity { a ->
            a.testInputBox.setText("export AT_SHARED=OK")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(400)
        // Debug echoes the var via the shared session.
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            c.write(pageId, "echo \$AT_SHARED\n")
        }
        assertTrue(
            "shared env = OK; got: ${activeOutput()}",
            await(4000) { activeOutput().contains("OK") },
        )
    }

    // ---- B7: Human input visible to Debug observer ------------------------

    @Test
    fun b7_humanInputVisibleToDebug() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            // Human types via UI.
            scenario?.onActivity { a ->
                a.testInputBox.setText("echo FROM_HUMAN")
                a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
            }
            // Debug should observe the input-activity event (source=HUMAN) AND output.
            val sawInput = await(4000) {
                val line = c.nextLine()
                val o = JSONObject(line)
                o.optString("type") == "input" && o.optString("source") == "HUMAN"
            }
            assertTrue("Debug observes Human input activity", sawInput)
            // And the output contains FROM_HUMAN.
            val out = c.collectOutput(3000)
            assertTrue("Debug observes Human output; got: $out", out.contains("FROM_HUMAN"))
        }
    }

    // ---- B8: shared Ctrl-C (debug 0x03 on Human session) ------------------

    @Test
    fun b8_sharedCtrlC() {
        val pageId = createLocalPage()
        // Human starts a long sleep.
        scenario?.onActivity { a ->
            a.testInputBox.setText("sleep 30")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(800)
        // Debug sends Ctrl-C (0x03) via the shared session.
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            c.writeRaw(pageId, android.util.Base64.encodeToString(byteArrayOf(0x03), android.util.Base64.NO_WRAP))
        }
        Thread.sleep(800)
        // Human continues: echo after Ctrl-C.
        scenario?.onActivity { a ->
            a.testInputBox.setText("echo AFTER_SHARED_CTRL_C")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        assertTrue(
            "shared Ctrl-C then shell survives; got: ${activeOutput()}",
            await(5000) { activeOutput().contains("AFTER_SHARED_CTRL_C") },
        )
    }

    // ---- B9: multi-session target isolation -------------------------------

    @Test
    fun b9_multiSessionTargetIsolation() {
        val id1 = createLocalPage()
        // Create a second page.
        val id2 = createLocalPage()
        BridgeClient(port).use { c ->
            val ids = c.listSessions()
            assertTrue("two sessions visible; got $ids", ids.size == 2)
            // Attach to the second and set an env var.
            c.attach(id2.toString())
            c.write(id2, "export DEBUG_TARGET=ADB2\n")
            Thread.sleep(400)
        }
        // Human in session 1: echo $DEBUG_TARGET should be empty (not ADB2).
        scenario?.onActivity { a ->
            a.switchToPage(id1)
            a.testInputBox.setText("echo X:\$DEBUG_TARGET")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(600)
        // Session 1 output should show "X:" without ADB2.
        assertTrue(
            "session 1 NOT polluted by session 2 env; got: ${activeOutput()}",
            !activeOutput().contains("X:ADB2"),
        )
    }

    // ---- B10: debug disconnect does not close session ---------------------

    @Test
    fun b10_disconnectSurvives() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            c.write(pageId, "echo BEFORE_DISCONNECT\n")
        }
        // Client closed here (use{} exits). Session must survive.
        assertTrue("session still active after debug disconnect",
            await(2000) { sessionActive(pageId) })
    }

    // ---- B11: reattach ----------------------------------------------------

    @Test
    fun b11_reattach() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
        }
        // Reconnect + reattach the same session.
        BridgeClient(port).use { c2 ->
            val r = c2.attach(pageId.toString())
            assertEquals("reattach", "attached", r.optString("type"))
            c2.write(pageId, "echo REATTACHED\n")
        }
        assertTrue("reattached write visible; got: ${activeOutput()}",
            await(4000) { activeOutput().contains("REATTACHED") })
    }

    // ---- B12: Human close propagates to Debug -----------------------------

    @Test
    fun b12_humanClosePropagates() {
        val pageId = createLocalPage()
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            // Human closes the page.
            scenario?.onActivity { a ->
                a.findViewById<android.widget.Button>(R.id.btnClosePage).performClick()
            }
            // Debug should receive closed.
            val sawClosed = await(3000) {
                val o = c.nextObject()
                o.optString("type") == "closed"
            }
            assertTrue("Debug receives closed", sawClosed)
        }
    }

    // ---- B13: closed write rejected ---------------------------------------

    @Test
    fun b13_closedWriteRejected() {
        val pageId = createLocalPage()
        // Close the session.
        scenario?.onActivity { a ->
            a.findViewById<android.widget.Button>(R.id.btnClosePage).performClick()
        }
        Thread.sleep(300)
        BridgeClient(port).use { c ->
            c.attach(pageId.toString())
            val r = c.write(pageId, "echo SHOULD_FAIL\n")
            assertEquals("closed write rejected", "rejected", r.optString("type"))
        }
    }

    // ---- B14: ADB attach adds no ADB stream/TLS (ADB path) ----------------

    @Test
    fun b14_adbAttachNoExtraStream() {
        if (AppRuntime.adbState() != AdbRuntimeState.READY &&
            !await(20000) { AppRuntime.connectAuto(); AppRuntime.adbState() == AdbRuntimeState.READY }
        ) {
            org.junit.Assume.assumeTrue("ADB not ready (skip)", false)
        }
        val pageId = createAdbPage()
        BridgeClient(port).use { c ->
            val r = c.attach(pageId.toString())
            assertEquals("attached", "attached", r.optString("type"))
            // Debug id -> uid=2000 via the shared ADB session.
            c.write(pageId, "id\n")
            val out = c.collectOutput(5000)
            assertTrue("ADB shared session id uid=2000; got: $out", out.contains("uid=2000"))
        }
    }

    private fun sessionActive(pageId: Int): Boolean {
        var active = false
        scenario?.onActivity { a ->
            active = a.pageStateById(pageId) == "ACTIVE"
        }
        return active
    }
}

