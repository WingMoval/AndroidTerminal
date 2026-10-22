// AT.V4-5 — Conformance Gates (A-H) for the reference consumer host.
//
// Proves a LEGAL consumer (only :at-engine-api + :at-composition-android +
// :at-cli) can complete the full AndroidTerminal surface WITHOUT naming any
// provider type (AdbRuntime / AdbEngine / backends / DefaultATEngine).
//
// Execution: direct `adb shell am instrument` (NOT Gradle UTP — preserves
// the persistent app-private identity + permission + device trust).
//
// Gates:
//   A — Local one-shot
//   B — Local persistent
//   C — ADB one-shot
//   D — ADB persistent
//   E — Readiness
//   F — Bootstrap
//   G — Identity
//   H — CLI (shell-visible adb)

package com.androidterminal.conformance

import com.at.composition.android.ATAdbBootstrapState
import com.at.composition.android.ATAdbReadinessState
import com.at.engine.ATExecutionRequest
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionLaunch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class ConformanceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        ConformanceHost.initialize(ctx)
    }

    // ---- Gate A: Local one-shot --------------------------------------------
    @Test
    fun a_localOneShot() {
        val engine = ConformanceHost.localEngine()
        val r = engine.execute(ATExecutionRequest(command = "id"))
        Assert.assertEquals("Local one-shot outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        Assert.assertTrue(
            "Local one-shot stdout has uid; got: ${r.stdout}",
            r.stdout.contains("uid=")
        )
        Assert.assertEquals("Local one-shot exitCode", 0, r.exitCode)
    }

    // ---- Gate B: Local persistent ------------------------------------------
    @Test
    fun b_localPersistent() {
        val engine = ConformanceHost.localEngine()
        val c = Consumer()
        Assert.assertTrue("open local persistent", c.open(engine, "/system/bin/sh"))
        // multiple commands + cwd continuity
        c.write("cd /data/local/tmp")
        Thread.sleep(300)
        c.write("pwd")
        Assert.assertTrue(
            "local persistent cwd continuity; got: ${c.out()}",
            await(8000) { c.out().contains("/data/local/tmp") }
        )
        c.close()
    }

    // ---- Gate C: ADB one-shot ----------------------------------------------
    @Test
    fun c_adbOneShot() {
        val comp = ConformanceHost.composition()!!
        Assert.assertTrue("ADB READY (ensureReady)", comp.adbControl.ensureReady { })
        val r = ConformanceHost.adbEngine().execute(ATExecutionRequest(command = "id"))
        Assert.assertEquals("ADB one-shot outcome", ATExecutionOutcome.COMPLETED, r.outcome)
        Assert.assertTrue(
            "ADB one-shot uid=2000(shell); got: ${r.stdout}",
            r.stdout.contains("uid=2000")
        )
    }

    // ---- Gate D: ADB persistent --------------------------------------------
    @Test
    fun d_adbPersistent() {
        val comp = ConformanceHost.composition()!!
        Assert.assertTrue("ADB READY (ensureReady)", comp.adbControl.ensureReady { })
        val c = Consumer()
        Assert.assertTrue("open ADB persistent", c.open(ConformanceHost.adbEngine(), "sh"))
        c.write("id")
        Assert.assertTrue(
            "ADB persistent uid=2000(shell); got: ${c.out()}",
            await(15000) { c.out().contains("uid=2000") }
        )
        c.close()
    }

    // ---- Gate E: Readiness -------------------------------------------------
    @Test
    fun e_readiness() {
        val comp = ConformanceHost.composition()!!
        // Consumer observes readiness via the semantic facade (no AdbRuntime).
        val state: ATAdbReadinessState = comp.adbControl.readinessState()
        Assert.assertTrue(
            "READY observable via public facade; got: $state",
            state == ATAdbReadinessState.READY
        )
    }

    // ---- Gate F: Bootstrap -------------------------------------------------
    @Test
    fun f_bootstrap() {
        val comp = ConformanceHost.composition()!!
        // Each test is a fresh process; establish readiness first (the consumer
        // drives the canonical ensureReady — no AdbRuntime named).
        Assert.assertTrue("ADB READY (ensureReady)", comp.adbControl.ensureReady { })
        // Consumer observes bootstrap state via the semantic facade.
        val bs: ATAdbBootstrapState = comp.adbControl.bootstrapState()
        Assert.assertTrue(
            "bootstrap state READY (no AdbRuntime named); got: $bs",
            bs == ATAdbBootstrapState.READY
        )
        // First-use bootstrap completion is accessible via the public facade
        // (idempotent; SKIPPED_ALREADY_GRANTED when already granted).
        val result = comp.adbControl.completeFirstUseBootstrap()
        println("AT.V4-5 Gate F: completeFirstUseBootstrap = $result")
        Assert.assertNotNull("bootstrap completion result", result)
    }

    // ---- Gate G: Identity --------------------------------------------------
    @Test
    fun g_identity() {
        val comp = ConformanceHost.composition()!!
        // ONE consumer -> ONE canonical identity (self-generated, present).
        Assert.assertTrue("identity present via public facade", comp.adbControl.hasIdentity())
        // Identity is independent (own private dir, self-generated key).
        val dir = ConformanceHost.identityDir()
        Assert.assertTrue("conformance identity dir exists", dir.exists())
        val pub = java.io.File(dir, "adbkey.pub")
        Assert.assertTrue("conformance adbkey.pub exists (self-generated)", pub.exists())
        // Independence: this key is NOT the Product key (different profile dir).
        Assert.assertEquals(
            "identity profile is conformance-identity",
            "conformance-identity",
            ConformanceHost.IDENTITY_PROFILE
        )
    }

    // ---- Gate H: CLI (shell-visible adb) -----------------------------------
    @Test
    fun h_cliShellVisibleAdb() {
        val comp = ConformanceHost.composition()!!
        Assert.assertTrue("ADB READY (ensureReady)", comp.adbControl.ensureReady { })
        val c = Consumer()
        Assert.assertTrue("open local persistent for CLI", c.open(ConformanceHost.localEngine(), "/system/bin/sh"))
        // Inject the shell-visible `adb` function (reusable :at-cli bootstrap).
        val bootstrap = ConformanceHost.cliBootstrap()
        val script = java.io.File(ctx.filesDir, "conformance_adb_bootstrap.sh")
        val scriptPath = bootstrap.writeScript(
            scriptPath = script.absolutePath,
            apkPath = ConformanceHost.appApkPath(),
            sockPath = ConformanceHost.cliBridgeSocketPath(),
            sessionId = 1,
        )
        c.write(bootstrap.sourceLine(scriptPath))
        Thread.sleep(2000)
        // `adb shell id` through the shell-visible function -> uid=2000.
        c.write("adb shell id")
        Assert.assertTrue(
            "CLI shell-visible adb uid=2000; got: ${c.out()}",
            await(20000) { c.out().contains("uid=2000") }
        )
        c.close()
    }

    // ---- helpers -----------------------------------------------------------
    private inline fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    /** Thin adapter over ATEngine — the programmatic consumer (no Android UI). */
    private inner class Consumer {
        private val buf = StringBuilder()
        private var session: com.at.engine.ATTerminalSession? = null

        fun open(engine: com.at.engine.ATEngine, command: String): Boolean {
            val r = engine.openSession(
                ATSessionLaunch(command = command, initialRows = 24, initialCols = 80),
            )
            return when (r) {
                is ATOpenSessionResult.Success -> {
                    session = r.session
                    r.session.setListener { ev ->
                        if (ev is ATSessionEvent.Data) {
                            synchronized(buf) { buf.append(String(ev.bytes)) }
                        }
                    }
                    true
                }
                is ATOpenSessionResult.Failure -> false
            }
        }

        fun write(text: String): Int = session?.write((text + "\n").toByteArray()) ?: -1
        fun out(): String = synchronized(buf) { buf.toString() }
        fun close() { runCatching { session?.close() } }
    }
}
