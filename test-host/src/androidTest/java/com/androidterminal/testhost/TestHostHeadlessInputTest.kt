// Test Host 鈥?headless input regression (AT.CONTRACT-0.5 T2).
//
// Independent consumer: no Human UI. Proves the AT Engine public boundary can
// be driven programmatically (create session, write bytes, capture output)
// exactly as the headless path is expected to behave.
//
// Uses the SAME contract as Human UI: AT Engine 鈫?LocalPersistentBackend 鈫?
// Local PTY 鈫?/system/bin/sh. No LocalPtyNative/JNI access.

package com.androidterminal.testhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.at.backend.local.LocalPersistentBackend
import com.at.engine.ATEngine
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestHostHeadlessInputTest {

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    @Test
    fun headlessWriteAndCapture() {
        val output = StringBuilder()
        val engine = DefaultATEngine(oneShot = null, persistent = LocalPersistentBackend())
        val r = engine.openSession(
            ATSessionLaunch(command = "/system/bin/sh", initialRows = 24, initialCols = 80),
        )
        assertTrue("open should succeed, got $r", r is ATOpenSessionResult.Success)
        val session: ATTerminalSession = (r as ATOpenSessionResult.Success).session
        session.setListener { ev -> if (ev is ATSessionEvent.Data) output.append(String(ev.bytes)) }

        // Byte-stream write: text + explicit newline.
        session.write("echo HEADLESS_OK\n".toByteArray())
        assertTrue("HEADLESS_OK should appear", await(3000) { output.contains("HEADLESS_OK") })
        assertEquals(ATSessionState.ACTIVE, session.state)

        // Multiple writes, same session.
        session.write("echo SECOND\n".toByteArray())
        assertTrue("SECOND should appear", await(3000) { output.contains("SECOND") })

        session.close()
        assertEquals(ATSessionState.CLOSED, session.state)
    }
}



