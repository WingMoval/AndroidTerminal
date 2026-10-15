// AndroidTerminal V3 鈥?DebugBridge screenshot evidence (AT.DEBUG-0).
//
// Creates a real ADB session via the Human path (MainActivity -> SessionPage),
// attaches a loopback DebugBridge client, writes commands through the bridge,
// and captures screenshots showing the shared-session UX (DEBUG > annotation +
// remote output + shared cwd/env). Evidence-only; assertions are light.

package com.androidterminal.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntimeState
import com.androidterminal.app.debug.DebugBridge
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DebugBridgeScreenshotTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    private class Client(port: Int) : AutoCloseable {
        val socket = java.net.Socket().apply {
            connect(java.net.InetSocketAddress("127.0.0.1", port), 5000)
        }
        val out = java.io.PrintWriter(socket.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8)
        val in_ = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        init { in_.readLine() } // hello
        fun send(o: JSONObject) = out.println(o.toString())
        fun next() = in_.readLine() ?: ""
        override fun close() {
            runCatching { socket.close() }
        }
    }

    @Before
    fun launch() {
        AppRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() { scenario?.close() }

    private fun shot(name: String) {
        try {
            val shot: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "at_debug_0")
            dir.mkdirs()
            FileOutputStream(File(dir, "$name.png")).use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (t: Throwable) {
            android.util.Log.w("AtDebug0", "screenshot failed", t)
        }
    }

    private fun waitReady(): Boolean {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            if (AppRuntime.adbState() == AdbRuntimeState.READY) return true
            AppRuntime.connectAuto()
            Thread.sleep(300)
        }
        return AppRuntime.adbState() == AdbRuntimeState.READY
    }

    private fun outputText(): String {
        var s = ""
        scenario?.onActivity { a ->
            s = a.findViewById<android.widget.TextView>(R.id.tvOutput).text?.toString() ?: ""
        }
        return s
    }

    @Test
    fun captureDebugBridgeEvidence() {
        // Ensure ADB READY, create a real ADB session via the Human path.
        if (!waitReady()) {
            org.junit.Assume.assumeTrue("ADB not ready", false)
        }
        var pageId = -1
        scenario?.onActivity { a ->
            val p = a.createPage(SessionBackendKind.ADB)
            a.openActiveSession(24, 80)
            pageId = p.pageId
        }
        Thread.sleep(800)

        // d1: DebugBridge CONNECTED + session visible.
        Client(45679).use { c ->
            c.send(JSONObject().put("type", "list"))
            c.next()
            shot("d1_debug_connected")
        }

        // d2: debug write id -> UI shows "DEBUG > id" + uid=2000(shell).
        Client(45679).use { c ->
            c.send(JSONObject().put("type", "attach").put("sessionId", pageId.toString()))
            c.next()
            c.send(JSONObject().put("type", "write").put("sessionId", pageId).put("text", "id\n"))
            Thread.sleep(1200)
            shot("d2_debug_input_ui")
        }

        // d3: debug cd /data/local/tmp, human pwd -> /data/local/tmp.
        Client(45679).use { c ->
            c.send(JSONObject().put("type", "write").put("sessionId", pageId).put("text", "cd /data/local/tmp\n"))
            Thread.sleep(500)
        }
        scenario?.onActivity { a ->
            a.testInputBox.setText("pwd")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(1200)
        shot("d3_shared_cwd")

        // d4: human export, debug echo -> shared env.
        scenario?.onActivity { a ->
            a.testInputBox.setText("export AT_SHARED=OK")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(400)
        Client(45679).use { c ->
            c.send(JSONObject().put("type", "write").put("sessionId", pageId).put("text", "echo \$AT_SHARED\n"))
            Thread.sleep(1000)
        }
        shot("d4_shared_env")

        // d5: human sleep 30, debug Ctrl-C -> shared interrupt.
        scenario?.onActivity { a ->
            a.testInputBox.setText("sleep 30")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(800)
        Client(45679).use { c ->
            c.send(JSONObject().put("type", "write_raw").put("sessionId", pageId).put("base64",
                android.util.Base64.encodeToString(byteArrayOf(0x03), android.util.Base64.NO_WRAP)))
            Thread.sleep(1000)
        }
        scenario?.onActivity { a ->
            a.testInputBox.setText("echo AFTER_SHARED_CTRL_C")
            a.findViewById<android.widget.Button>(R.id.btnSend).performClick()
        }
        Thread.sleep(1200)
        shot("d5_shared_ctrlc")

        // d6: multi-session target 鈥?create a 2nd ADB page.
        scenario?.onActivity { a ->
            val p2 = a.createPage(SessionBackendKind.ADB)
            a.openActiveSession(24, 80)
        }
        Thread.sleep(800)
        shot("d6_multi_session_target")
    }
}

