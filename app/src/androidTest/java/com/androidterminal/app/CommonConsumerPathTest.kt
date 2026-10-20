// AndroidTerminal V3 鈥?Common AT Consumer Path Validation (AT.HUMAN-ADB-0).
//
// Proves that a Debug/Test consumer can be a FIRST-CLASS AT Engine consumer
// WITHOUT any Human UI: no MainActivity / SessionPage / EditText / Button /
// IME / uiautomator / screen coordinates. Command results come from AT session
// output/events 鈥?never from logcat scraping.
//
// It obtains the SAME composition root (AppRuntime) the Human UI uses, so Human
// and Debug share ONE AT Engine public consumer surface / backend semantics.
// It does NOT create a second AdbRuntime / connection owner.
//
//   Debug Path: Debug/Test -> AppRuntime composition -> ATEngine -> Backend

package com.androidterminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntimeState
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Debug/Test consumer harness: a thin adapter over AppRuntime -> ATEngine.
 * No Android UI types are touched. It is the "Debug consumer" of the one
 * common AT consumer path (AT.HUMAN-ADB-0 搂33-搂37, 搂47).
 */
@RunWith(AndroidJUnit4::class)
class CommonConsumerPathTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Thin adapter: open/write/observe/resize/close via ATEngine only. */
    private class Consumer {
        val events = ConcurrentLinkedQueue<ATSessionEvent>()
        private val output = StringBuilder()
        var session: ATTerminalSession? = null
            private set

        private val listener: (ATSessionEvent) -> Unit = { ev ->
            events.add(ev)
            if (ev is ATSessionEvent.Data) {
                synchronized(output) { output.append(String(ev.bytes)) }
            }
        }

        fun open(engine: com.at.engine.ATEngine, command: String, rows: Int = 24, cols: Int = 80): Boolean {
            val r = engine.openSession(
                ATSessionLaunch(command = command, initialRows = rows, initialCols = cols),
            )
            return when (r) {
                is ATOpenSessionResult.Success -> {
                    session = r.session
                    r.session.setListener(listener)
                    true
                }
                is ATOpenSessionResult.Failure -> false
            }
        }

        fun write(text: String): Int = session?.write((text + "\n").toByteArray()) ?: -1
        fun writeRaw(bytes: ByteArray): Int = session?.write(bytes) ?: -1

        fun out(): String = synchronized(output) { output.toString() }

        fun resize(r: Int, c: Int): Boolean = session?.resize(r, c) ?: false
        fun close() = runCatching { session?.close() }

        val state: ATSessionState get() = session?.state ?: ATSessionState.CLOSED
    }

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    // ---- C1-C4: Local provider via ATEngine -------------------------------

    @Test
    fun c1_c4_localSession() {
        AppRuntime.initialize(ctx)
        val c = Consumer()
        val engine = AppRuntime.localEngine()
        assertTrue("C1: open Local session via ATEngine", c.open(engine, "/system/bin/sh"))
        assertEquals("session ACTIVE", ATSessionState.ACTIVE, c.state)

        // C2: write + observe via AT session output.
        c.write("echo DEBUG_LOCAL_OK")
        assertTrue("C2: Local write/observe", await(4000) { c.out().contains("DEBUG_LOCAL_OK") })

        // C3: resize.
        assertTrue("C3: Local resize accepted", c.resize(40, 120))

        // C4: close.
        c.close()
        assertTrue("C4: Local session closes", await(2000) { c.state == ATSessionState.CLOSED })
    }

    // ---- C5-C10: ADB provider via DefaultATEngine(single AdbRuntime owner) -------

    @Test
    fun c5_c10_adbSession() {
        AppRuntime.initialize(ctx)
        // C5: ADB must reach READY (identity already trusted by PairActivity /
        // AppAdbProvisionTest). This instrumentation process drives connectAuto
        // to establish the connection with the trusted identity.
        AppRuntime.connectAuto()
        assertTrue("C5: ADB READY (single AdbRuntime owner); got ${AppRuntime.adbState()}",
            AppRuntime.adbState() == AdbRuntimeState.READY)

        val c = Consumer()
        val engine = AppRuntime.adbEngine()
        // C6: open ADB persistent session (remote PTY via shell,v2,pty).
        assertTrue("C6: open ADB persistent session via ATEngine", c.open(engine, ""))

        // C7-C8: write "id\n", observe uid=2000(shell) from AT session output.
        c.write("id")
        assertTrue("C8: observe uid=2000(shell) from AT output; got: ${c.out()}",
            await(5000) { c.out().contains("uid=2000") })

        // C9: resize.
        assertTrue("C9: ADB resize accepted", c.resize(40, 120))

        // C10: close.
        c.close()
        assertTrue("C10: ADB session closes", await(2000) { c.state == ATSessionState.CLOSED })
    }

    // ---- ADB persistent cwd/env (consumer, no UI) -------------------------

    @Test
    fun c11_adbPersistentCwdEnv() {
        AppRuntime.initialize(ctx)
        AppRuntime.connectAuto()
        assertTrue("ADB READY", AppRuntime.adbState() == AdbRuntimeState.READY)
        val c = Consumer()
        assertTrue("open ADB session", c.open(AppRuntime.adbEngine(), ""))

        c.write("cd /data/local/tmp")
        Thread.sleep(400)
        c.write("pwd")
        assertTrue("ADB persistent cwd = /data/local/tmp; got: ${c.out()}",
            await(5000) { c.out().contains("/data/local/tmp") })

        c.write("export AT_DEBUG_PREVIEW=OK")
        Thread.sleep(400)
        c.write("echo \$AT_DEBUG_PREVIEW")
        assertTrue("ADB persistent env = OK; got: ${c.out()}",
            await(5000) { c.out().contains("OK") })
        c.close()
    }
}




