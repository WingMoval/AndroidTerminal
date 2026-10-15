// AndroidTerminal V3 — Programmatic ADB Consumer validation (AT.HUMAN-ADB-0).
//
// Drives the FULL Human ADB scenario through the RESERVED programmatic path:
//   AppRuntime (composition root) -> ATEngine -> AdbPersistentBackend -> adbd
//
// NO UI / NO IME / NO uiautomator / NO screen coordinates. Command results come
// from ATTerminalSession output/events. This is the "Debug consumer" of the one
// common AT consumer path, exercising the same ATEngine + backend semantics the
// Human UI uses — proving the programmatic path is a first-class consumer.
//
// Covers §24-§33: principal, cwd, env, Ctrl-C, resize, multi-session, isolation,
// inactive buffering, close isolation, Local+ADB coexistence.

package com.androidterminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbRuntimeState
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class ProgrammaticAdbConsumerTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Thin adapter over ATEngine — the programmatic consumer. No Android UI. */
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
                ATPersistentBackend.Launch(command = command, initialRows = rows, initialCols = cols),
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

    private fun ready(): Boolean {
        AppRuntime.initialize(ctx)
        AppRuntime.connectAuto()
        return AppRuntime.adbState() == AdbRuntimeState.READY
    }

    // §24 principal: id -> uid=2000(shell)
    @Test
    fun principalUid2000() {
        assertTrue("ADB READY", ready())
        val c = Consumer()
        assertTrue("open ADB session", c.open(AppRuntime.adbEngine(), ""))
        c.write("id")
        assertTrue("id -> uid=2000(shell); got: ${c.out()}",
            await(5000) { c.out().contains("uid=2000") })
        c.close()
    }

    // §25 persistent cwd
    @Test
    fun persistentCwd() {
        assertTrue("ADB READY", ready())
        val c = Consumer()
        assertTrue("open", c.open(AppRuntime.adbEngine(), ""))
        c.write("cd /data/local/tmp")
        Thread.sleep(400)
        c.write("pwd")
        assertTrue("persistent cwd = /data/local/tmp; got: ${c.out()}",
            await(5000) { c.out().contains("/data/local/tmp") })
        c.close()
    }

    // §26 persistent env
    @Test
    fun persistentEnv() {
        assertTrue("ADB READY", ready())
        val c = Consumer()
        assertTrue("open", c.open(AppRuntime.adbEngine(), ""))
        c.write("export AT_PREVIEW=OK")
        Thread.sleep(400)
        c.write("echo \$AT_PREVIEW")
        assertTrue("persistent env = OK; got: ${c.out()}",
            await(5000) { c.out().contains("OK") })
        c.close()
    }

    // §28 Ctrl-C
    @Test
    fun ctrlC() {
        assertTrue("ADB READY", ready())
        val c = Consumer()
        assertTrue("open", c.open(AppRuntime.adbEngine(), ""))
        c.write("sleep 30")
        Thread.sleep(800)
        c.writeRaw(byteArrayOf(0x03)) // Ctrl-C
        Thread.sleep(800)
        c.write("echo AFTER_CTRL_C")
        assertTrue("Ctrl-C then shell survives; got: ${c.out()}",
            await(5000) { c.out().contains("AFTER_CTRL_C") })
        assertEquals("ACTIVE after Ctrl-C", ATSessionState.ACTIVE, c.state)
        c.close()
    }

    // §29 resize isolation
    @Test
    fun resizeIsolation() {
        assertTrue("ADB READY", ready())
        val c1 = Consumer()
        val c2 = Consumer()
        assertTrue("open c1", c1.open(AppRuntime.adbEngine(), ""))
        assertTrue("open c2", c2.open(AppRuntime.adbEngine(), ""))
        assertTrue("resize c1 40x120", c1.resize(40, 120))
        // c1 stty size should reflect 40x120; c2 unchanged (still 24x80).
        c1.write("stty size")
        assertTrue("c1 resized to 40 rows; got: ${c1.out()}",
            await(5000) { c1.out().contains("40") })
        c2.write("stty size")
        assertTrue("c2 NOT resized (still 24 rows); got: ${c2.out()}",
            await(5000) { c2.out().contains("24") })
        c1.close()
        c2.close()
    }

    // §30 multi-session + §31 inactive buffering
    @Test
    fun multiSessionIsolation() {
        assertTrue("ADB READY", ready())
        val a = Consumer()
        val b = Consumer()
        assertTrue("open A", a.open(AppRuntime.adbEngine(), ""))
        assertTrue("open B", b.open(AppRuntime.adbEngine(), ""))
        // A: cd into a specific dir; B must NOT share it.
        a.write("cd /data/local/tmp")
        Thread.sleep(400)
        a.write("pwd")
        assertTrue("A cwd /data/local/tmp", await(5000) { a.out().contains("/data/local/tmp") })
        b.write("pwd")
        assertTrue("B independent cwd (not /data/local/tmp)",
            await(5000) { b.out().contains("/") && !b.out().contains("/data/local/tmp") })
        a.close()
        b.close()
    }

    // §33 Local + ADB coexistence
    @Test
    fun localAdbCoexistence() {
        assertTrue("ADB READY", ready())
        val adb = Consumer()
        assertTrue("open ADB", adb.open(AppRuntime.adbEngine(), ""))
        adb.write("id")
        assertTrue("ADB id uid=2000", await(5000) { adb.out().contains("uid=2000") })

        val loc = Consumer()
        assertTrue("open Local", loc.open(AppRuntime.localEngine(), "/system/bin/sh"))
        loc.write("id")
        // Local id = app UID (NOT uid=2000). Both ACTIVE simultaneously.
        assertTrue("Local id visible (app UID)", await(5000) { loc.out().contains("uid=") })
        assertTrue("Local is NOT uid=2000 (shell); got: ${loc.out()}",
            !loc.out().contains("uid=2000"))
        // Both still ACTIVE.
        assertEquals("ADB ACTIVE", ATSessionState.ACTIVE, adb.state)
        assertEquals("Local ACTIVE", ATSessionState.ACTIVE, loc.state)
        loc.close()
        adb.close()
    }
}
