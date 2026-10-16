// AT.CLI-0 — app_process ENTRYPOINT PROBE (P1–P6). NOT production CLI.
//
// Drives the REAL Local /system/bin/sh (untrusted_app domain) via the
// SharedSessionHandle observer model (the production observation path) to run:
//
//   /system/bin/app_process -Djava.class.path=<base.apk> <stub> \
//       com.androidterminal.app.cli.CliProbe <argv...>
//
// and asserts P1–P6 from the fan-out output. No full adb shell.
//
// P1 Local PTY can exec /system/bin/app_process
// P2 app_process loads the in-APK CliProbe main class
// P3 argv passthrough
// P4 stdin/stdout/stderr inherit the Local PTY
// P5 CliProbe connects the existing Product CLI Bridge (HELLO/HELLO_ACK)
// P6 Local shell / TTY intact after return

package com.androidterminal.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class AppProcessProbeTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launch() {
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }
    }

    @After
    fun tearDown() {
        // Keep scenario alive for the class (process exit releases it).
    }

    /** Run [cmd] in the real Local PTY via the observer model; return output. */
    private fun runInLocalShell(cmd: String, marker: String, timeoutMs: Long = 12000): String {
        val observed = StringBuilder()
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1000)
            val handle = a.activeSharedHandle()
            if (opened && handle != null) {
                val observer = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        synchronized(observed) {
                            observed.append(String(bytes, StandardCharsets.UTF_8))
                        }
                    }
                }
                handle.addObserver(observer)
                handle.write(InputSource.DEBUG, "$cmd; echo $marker\n".toByteArray(StandardCharsets.UTF_8))
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val snap = synchronized(observed) { observed.toString() }
                    if (snap.contains(marker)) break
                    Thread.sleep(100)
                }
                Thread.sleep(400)
                handle.removeObserver(observer)
            }
            a.testCloseActivePage()
            try {
                java.io.File(a.filesDir, "probe_diag.txt").writeText(
                    "cmd=[$cmd]\nobserved=[${synchronized(observed) { observed.toString() }}]\n"
                )
            } catch (_: Exception) {}
        }
        return synchronized(observed) { observed.toString() }
    }

    /** The installed base.apk path for this app (for -Djava.class.path). */
    private fun baseApk(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.applicationInfo.sourceDir
    }

    /** The full app_process probe command. */
    private fun probeCmd(extraArgs: String = ""): String {
        val apk = baseApk()
        val args = if (extraArgs.isBlank()) "ARG_ONE ARG_TWO" else extraArgs
        // app_process argv: <argv0-stub> <class> <args...>
        return "/system/bin/app_process -Djava.class.path=$apk /system/bin " +
            "com.androidterminal.app.cli.CliProbe $args"
    }

    // P1: Local PTY (untrusted_app) can exec /system/bin/app_process at all.
    @Test
    fun p1_appProcessExec() {
        // A trivial app_process invocation (run CliProbe with no bridge need yet).
        val out = runInLocalShell(probeCmd(), "PPROBE1_DONE")
        try {
            java.io.File(filesDirSafe(), "p1_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        // P1 passes if app_process ran the class (we see CLIPROBE_ output) and
        // the shell returned (PPROBE1_DONE marker present). NOT "Permission denied".
        assertTrue(
            "P1: app_process exec'd from Local PTY (out=[$out])",
            out.contains("CLIPROBE_ARGC") && out.contains("PPROBE1_DONE")
        )
        assertTrue(
            "P1: not a SELinux exec denial (out=[$out])",
            !out.contains("Permission denied") && !out.contains("RC=126")
        )
    }

    // P2: app_process loads the in-APK CliProbe main class.
    @Test
    fun p2_classLoad() {
        val out = runInLocalShell(probeCmd(), "PPROBE2_DONE")
        try {
            java.io.File(filesDirSafe(), "p2_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        assertTrue("P2: CliProbe class loaded+ran (out=[$out])", out.contains("CLIPROBE_ARGC=2"))
        assertTrue("P2: clean run marker", out.contains("PPROBE2_DONE"))
    }

    // P3: argv passthrough (ALPHA BETA arrive as args[0..1]).
    @Test
    fun p3_argv() {
        var out = ""
        // app_process JVM startup can be slow / occasionally killed; retry until
        // the argv report is observed.
        for (attempt in 1..3) {
            out = runInLocalShell(probeCmd("ALPHA BETA"), "PPROBE3_DONE")
            if (out.contains("CLIPROBE_ARGC=2")) break
        }
        try {
            java.io.File(filesDirSafe(), "p3_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        assertTrue("P3: argc=2 (out=[$out])", out.contains("CLIPROBE_ARGC=2"))
        assertTrue("P3: arg0=ALPHA (out=[$out])", out.contains("CLIPROBE_ARG_0=ALPHA"))
        assertTrue("P3: arg1=BETA (out=[$out])", out.contains("CLIPROBE_ARG_1=BETA"))
    }

    // P4: stdout + stderr inherit the Local PTY (both appear in fan-out output).
    @Test
    fun p4_stdio() {
        val out = runInLocalShell(probeCmd(), "PPROBE4_DONE")
        try {
            java.io.File(filesDirSafe(), "p4_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        assertTrue("P4: stdout inherited (out=[$out])", out.contains("CLIPROBE_DONE"))
        assertTrue("P4: stderr inherited (out=[$out])", out.contains("CLIPROBE_STDERR=ok"))
    }

    // P5: CliProbe connects the existing Product CLI Bridge (HELLO/HELLO_ACK).
    @Test
    fun p5_bridge() {
        // Ensure the bridge is up (owned by the app process / MainActivity).
        scenario?.onActivity { a ->
            a.testCloseActivePage() // settle
        }
        val out = runInLocalShell(probeCmd(), "PPROBE5_DONE")
        try {
            java.io.File(filesDirSafe(), "p5_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        assertTrue(
            "P5: bridge HELLO/HELLO_ACK from CliProbe (out=[$out])",
            out.contains("CLIPROBE_BRIDGE=OK")
        )
    }

    // P6: after CliProbe returns, the Local shell / TTY is intact (next cmd runs).
    @Test
    fun p6_returnTtyIntact() {
        // Run the probe, then a follow-up echo in the SAME Local session.
        val observed = StringBuilder()
        scenario?.onActivity { a ->
            a.createPage(SessionBackendKind.LOCAL)
            val opened = a.openActiveSession()
            Thread.sleep(1000)
            val handle = a.activeSharedHandle()
            if (opened && handle != null) {
                val observer = object : SessionObserver {
                    override fun onOutput(bytes: ByteArray) {
                        synchronized(observed) {
                            observed.append(String(bytes, StandardCharsets.UTF_8))
                        }
                    }
                }
                handle.addObserver(observer)
                // Probe first (--no-stdin so it does not consume the shell's
                // next command). Wait for CLIPROBE_DONE (probe finished) before
                // reading the RC marker — avoids matching the command echo.
                handle.write(
                    InputSource.DEBUG,
                    "${probeCmd("--no-stdin")}; echo PP6RC_$?\n".toByteArray(StandardCharsets.UTF_8)
                )
                // Wait until the probe has fully returned (CLIPROBE_DONE seen).
                sleepUntil(observed, "CLIPROBE_DONE", 15000)
                Thread.sleep(600) // let `echo PP6RC_$?` land after the probe exits
                // Follow-up in the SAME shell (TTY intact check).
                handle.write(InputSource.DEBUG, "echo PP6_ALIVE_MARKER\n".toByteArray(StandardCharsets.UTF_8))
                sleepUntil(observed, "PP6_ALIVE_MARKER", 6000)
                Thread.sleep(400)
                handle.removeObserver(observer)
            }
            a.testCloseActivePage()
            try {
                java.io.File(a.filesDir, "p6_diag.txt").writeText(
                    "observed=[${synchronized(observed) { observed.toString() }}]\n"
                )
            } catch (_: Exception) {}
        }
        val out = synchronized(observed) { observed.toString() }
        try {
            java.io.File(filesDirSafe(), "p6_diag.txt").writeText("out=[$out]\n")
        } catch (_: Exception) {}
        assertTrue("P6: probe returned RC=0 (out=[$out])", out.contains("PP6RC_0"))
        assertTrue("P6: shell alive after probe (out=[$out])", out.contains("PP6_ALIVE_MARKER"))
    }

    private fun sleepUntil(observed: StringBuilder, marker: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = synchronized(observed) { observed.toString() }
            if (snap.contains(marker)) return
            Thread.sleep(100)
        }
    }

    private fun filesDirSafe(): java.io.File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.filesDir
    }
}
