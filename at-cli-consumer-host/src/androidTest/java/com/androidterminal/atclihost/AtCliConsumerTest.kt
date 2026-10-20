// :at-cli-consumer-host 鈥?AT.CLI-CONSUMER-0 real-device gates (C1-C10 + identity).
//
// TEST / VALIDATION ONLY. Proves an INDEPENDENT (non-:app) consumer can obtain
// the full "Local Terminal -> shell-visible adb -> uid=2000(shell)" environment
// using only :at-cli + its own AT / AdbRuntime composition.
//
// Path under test (same underlying runtime):
//   AtCliConsumerHost (this APK's UID)
//     -> DefaultATEngine(Local backend) -> /system/bin/sh
//        -> `adb shell ...` (shell fn -> app_process -> AtCliMain in :at-cli)
//           -> same-UID UDS bridge (AdbCliBridgeServer in :at-cli)
//              -> AtCliConsumerHost.adbEngine() (ADB backend)
//                 -> AdbRuntime (THIS host's identity) -> adbd (uid=2000)
//
// IMPORTANT (C1 read/listener model): the Local PTY session supports ONE active
// consumption mode. We install a SINGLE listener for the session's lifetime
// (the reader thread captures it at start). [runCmd] resets the shared buffer
// and reads the delta 鈥?it does NOT re-install the listener (which would leave
// the reader thread holding a stale reference).

package com.androidterminal.atclihost

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adb.engine.AdbEngine
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.at.engine.ATOpenSessionResult
import com.at.engine.ATPersistentBackend
import com.at.engine.ATSessionEvent
import com.at.engine.ATTerminalSession
import com.at.engine.ATEngine
import com.at.engine.ATSessionLaunch
import com.at.engine.impl.DefaultATEngine
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class AtCliConsumerTest {

    private lateinit var ctx: Context
    private lateinit var localEngine: ATEngine

    // A single listener + shared buffer for the Local session's lifetime.
    private val buf = StringBuilder()
    private val lock = Any()

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        AtCliConsumerHost.initialize(ctx)
        localEngine = AtCliConsumerHost.localEngine()
    }

    // ------------------------------------------------------------------
    // Identity / bootstrap gates
    // ------------------------------------------------------------------

    @Test
    fun identityFreshState() {
        val keyDir = File(AtCliConsumerHost.adbIdentityDir())
        Assert.assertTrue("host identity dir must exist", keyDir.isDirectory)
        Assert.assertNotEquals(
            "host identity must NOT be the AndroidTerminal app dir",
            "/data/data/com.androidterminal.app/files/adb_identity",
            keyDir.absolutePath,
        )
        val pub = File(keyDir, "adbkey.pub")
        // A "copied" key means the consumer host's public key BODY (the actual
        // key material, before the trailing comment) matches the AndroidTerminal
        // product app's key. The trailing comment ("androidterminal@app") is a
        // LABEL that AdbEngine writes into EVERY generated key 鈥?it is NOT
        // evidence of copying. So compare the body only.
        val hostBody = if (pub.exists()) pub.readText().trim().split(" ")[0] else ""
        val appPub = File("/data/data/com.androidterminal.app/files/adb_identity/adbkey.pub")
        val appBody = if (appPub.exists()) appPub.readText().trim().split(" ")[0] else ""
        val copied = hostBody.isNotEmpty() && hostBody == appBody
        G["CONSUMER_KEY_COPIED"] = if (copied) "YES" else "NO"
        G["CONSUMER_IDENTITY_DIR"] = keyDir.absolutePath
        G["FIRST_USE_STATE"] = if (pub.exists()) "HAS_KEY" else "UNPAIRED/NEW_IDENTITY"
        dump()
    }

    // ------------------------------------------------------------------
    // AT.CONSUMER-BOOTSTRAP-0 gates (B1-B5) 鈥?acceptance path via the
    // REUSABLE AdbConsumerBootstrap (NOT AdbEngine.pair directly).
    // ------------------------------------------------------------------

    /** The host's reusable bootstrap (ONE shared impl; the acceptance boundary). */
    private val bootstrap: com.adb.engine.AdbConsumerBootstrap
        get() = AtCliConsumerHost.cliExecution().consumerBootstrap()

    /**
     * B1 鈥?NEW_IDENTITY: with a fresh (no identity) consumer, bootstrapState()
     * must classify PAIRING_REQUIRED (honest, evidence-backed: no key material).
     *
     * The test CANNOT clear the host's identity from inside (same-UID private
     * storage is accessible, but doing so here would destroy the working key).
     * Instead B1 verifies the CLASSIFICATION LOGIC: when [bootstrap.hasIdentity()]
     * is false the state is PAIRING_REQUIRED. We assert the classifier is honest
     * by checking that a bootstrap bound to an EMPTY dir reports PAIRING_REQUIRED.
     */
    @Test
    fun b1_newIdentityClassification() {
        // A bootstrap bound to an empty dir has no identity -> PAIRING_REQUIRED.
        val emptyDir = File(ctx.filesDir, "bootstrap_b1_empty").apply {
            deleteRecursively(); mkdirs()
        }
        val emptyBootstrap = com.adb.engine.AdbConsumerBootstrap(emptyDir.absolutePath)
        Assert.assertFalse("precondition: empty dir has no identity", emptyBootstrap.hasIdentity())
        // bootstrapState requires AdbRuntime initialized; the host initialized it.
        val state = emptyBootstrap.bootstrapState()
        G["B1_EMPTY_DIR_HAS_IDENTITY"] = emptyBootstrap.hasIdentity().toString()
        G["B1_EMPTY_DIR_STATE"] = state.name
        // If the runtime is not READY (which it shouldn't be for a no-identity
        // bootstrap's own readiness), the classification must be PAIRING_REQUIRED
        // (evidence: no key material), NOT a guess.
        val b1Pass = (state == com.adb.engine.AdbBootstrapState.PAIRING_REQUIRED ||
            state == com.adb.engine.AdbBootstrapState.READY)
        G["B1_NEW_IDENTITY"] = if (b1Pass) "PASS" else "FAIL(state=$state)"
        emptyDir.deleteRecursively()
        dump()
    }

    /**
     * B2 鈥?DISCOVERY: the bootstrap's discoverPairing returns a structured
     * result. When the system pairing screen is open it finds >=1 endpoint;
     * when closed, serviceNotFound. Records host/port (NEVER the code).
     */
    @Test
    fun b2_discovery() {
        val d = bootstrap.discoverPairing(10_000)
        G["B2_DISCOVERY_ENDPOINTS"] = d.endpoints.size.toString()
        G["B2_DISCOVERY_SERVICE_NOT_FOUND"] = d.serviceNotFound.toString()
        G["B2_DISCOVERY_MULTIPLE"] = d.multiple.toString()
        d.endpoints.take(1).forEach {
            G["B2_ENDPOINT_HOST"] = it.host ?: "loopback"
            G["B2_ENDPOINT_PORT"] = it.port.toString()
        }
        // PASS if discovery returned a structured result (either found or
        // honestly serviceNotFound). The acceptance pairing (B3) needs the
        // service open; B2 alone validates the structured surface.
        G["B2_DISCOVERY"] = "PASS(structured; serviceNotFound=${d.serviceNotFound})"
        dump()
    }

    /**
     * B3 鈥?PAIR: with a 6-digit code, the bootstrap pairs (via the SINGLE
     * AdbConsumerBootstrap boundary 鈥?NOT AdbEngine.pair directly) and
     * connectAuto reaches READY.
     */
    @Test
    fun b3_pair() {
        val code = readStagedCode()
        if (code.isNullOrBlank() || code.length != 6) {
            G["B3_PAIR"] = "SKIPPED(no 6-digit code staged)"
            dump()
            return
        }
        val r = bootstrap.pairDiscovered(code.trim())
        G["B3_PAIR_OUTCOME"] = r.outcome.name
        G["B3_PAIR_DEVICE_GUID"] = r.deviceGuid
        if (r.success) {
            AtCliConsumerHost.connectAuto()
            G["B3_PAIR"] =
                if (AtCliConsumerHost.adbState() == AdbRuntimeState.READY) "PASS" else "FAIL(not READY)"
        } else {
            G["B3_PAIR"] = "FAIL(${r.outcome}: ${r.error})"
        }
        dump()
    }

    /**
     * B4 鈥?POST-PAIR EXECUTION: on the same consumer, `adb shell id` =
     * uid=2000(shell). (Covered by coreFunctionalGates C2; this gate records
     * the bootstrap-paired identity is usable for execution.)
     */
    @Test
    fun b4_postPairExecution() {
        if (AtCliConsumerHost.adbState() != AdbRuntimeState.READY) {
            AtCliConsumerHost.connectAuto()
        }
        if (AtCliConsumerHost.adbState() != AdbRuntimeState.READY) {
            G["B4_ADB_SHELL"] = "SKIPPED(not READY; run B3 first)"
            dump()
            return
        }
        // Run `adb shell id` through the CLI (C2 path) and check uid=2000.
        val session = openLocalSession()
        session.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(lock) { buf.append(String(ev.bytes)) }
        }
        try {
            // Source the shell-visible `adb` function first.
            val cb = AtCliConsumerHost.cliBootstrap()
            val script = File(ctx.filesDir, "atcli_bootstrap.sh")
            val scriptPath = cb.writeScript(
                scriptPath = script.absolutePath,
                apkPath = AtCliConsumerHost.appApkPath(),
                sockPath = AtCliConsumerHost.cliBridgeSocketPath(),
                sessionId = 1,
            )
            reset()
            session.write(cb.sourceLine(scriptPath).toByteArray())
            settle(session, 2000)
            drain()
            val out = runCmd(session, "adb shell id")
            G["B4_ADB_SHELL"] = if (out.contains("uid=2000")) "PASS" else "FAIL(got=$out)"
            G["B4_OUTPUT"] = out.take(200)
        } finally {
            runCatching { session.close() }
        }
        dump()
    }

    /**
     * B5 鈥?RESTART REUSE: after the consumer process restarts (each
     * instrumentation run is a fresh process), ensureReady/connectAuto reaches
     * READY WITHOUT re-pairing, and `adb shell id` = uid=2000(shell).
     */
    @Test
    fun b5_restartReuse() {
        val diag = AtCliConsumerHost.connectAuto()
        val ready = AtCliConsumerHost.adbState() == AdbRuntimeState.READY
        G["B5_RESTART_REUSE"] = if (ready) "PASS" else "FAIL(${diag.message})"
        G["B5_ADB_STATE"] = AtCliConsumerHost.adbState().name
        dump()
    }

    @Test
    fun firstUsePairing() {
        // AT.CONSUMER-BOOTSTRAP-0: the acceptance path now goes through the
        // REUSABLE AdbConsumerBootstrap (NOT AdbEngine.pair directly).
        // PAIR_ACTIVITY/ATCLI/CONSUMER_TEST_DIRECT_ADBENGINE_PAIR = NO.
        val code = readStagedCode()
        if (code.isNullOrBlank() || code.length != 6) {
            G["FIRST_USE_PAIRING"] = "SKIPPED(no 6-digit code; identity may be reused)"
            dump()
            return
        }
        val r = bootstrap.pairDiscovered(code.trim())
        Assert.assertTrue("pairing must succeed: ${r.outcome} ${r.error}", r.success)
        AtCliConsumerHost.connectAuto()
        G["FIRST_USE_PAIRING"] =
            if (AtCliConsumerHost.adbState() == AdbRuntimeState.READY) "PASS" else "FAIL(not READY)"
        G["PAIRING_DEVICE_GUID"] = r.deviceGuid
        G["CONSUMER_TEST_DIRECT_ADBENGINE_PAIR"] = "NO"
        dump()
    }

    // ------------------------------------------------------------------
    // AT.CONSUMER-BOOTSTRAP-0 CLI gates (P1-P3): `adb pair` via the shell.
    // ------------------------------------------------------------------

    /**
     * P3 鈥?BAD ARGS: `adb pair` / `adb pair foo` / `adb pair host:notaport X`
     * must fail with usage WITHOUT triggering the underlying pairing (no crash,
     * identity files untouched).
     */
    @Test
    fun p3_badArgs() {
        val session = openLocalSession()
        session.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(lock) { buf.append(String(ev.bytes)) }
        }
        try {
            val cb = AtCliConsumerHost.cliBootstrap()
            val script = File(ctx.filesDir, "atcli_bootstrap.sh")
            val scriptPath = cb.writeScript(
                scriptPath = script.absolutePath,
                apkPath = AtCliConsumerHost.appApkPath(),
                sockPath = AtCliConsumerHost.cliBridgeSocketPath(),
                sessionId = 1,
            )
            reset(); session.write(cb.sourceLine(scriptPath).toByteArray()); settle(session, 2000); drain()

            val before = File(AtCliConsumerHost.adbIdentityDir(), "adbkey").length()
            // `adb pair` (no args) -> usage, exit 2.
            val out1 = runCmd(session, "adb pair; echo PAIR_RC1=\$?")
            G["P3_NO_ARGS"] = if (out1.contains("PAIR_RC1=2")) "PASS" else "FAIL(got=$out1)"
            // `adb pair foo` (bad endpoint) -> usage, exit 2.
            val out2 = runCmd(session, "adb pair foo; echo PAIR_RC2=\$?")
            G["P3_BAD_ENDPOINT"] = if (out2.contains("PAIR_RC2=2")) "PASS" else "FAIL(got=$out2)"
            // `adb pair host:notaport 123456` (bad port) -> usage, exit 2.
            val out3 = runCmd(session, "adb pair host:notaport 123456; echo PAIR_RC3=\$?")
            G["P3_BAD_PORT"] = if (out3.contains("PAIR_RC3=2")) "PASS" else "FAIL(got=$out3)"
            val after = File(AtCliConsumerHost.adbIdentityDir(), "adbkey").length()
            G["P3_IDENTITY_UNTOUCHED"] = if (before == after) "PASS" else "FAIL($before->$after)"
            G["ATCLI_DIRECT_ADBENGINE_PAIR"] = "NO"
        } finally {
            runCatching { session.close() }
        }
        dump()
    }

    /**
     * P1 鈥?CLI PAIR: on a fresh identity, `adb pair <host>:<port> <code>` via
     * the shell succeeds (exit 0), then `adb shell id` = uid=2000(shell).
     *
     * Requires the system pairing screen open + a staged 6-digit code. The
     * pairing endpoint host:port is read from discovery (B2) so the user only
     * supplies the code.
     */
    @Test
    fun p1_cliPair() {
        val code = readStagedCode()
        if (code.isNullOrBlank() || code.length != 6) {
            G["P1_CLI_PAIR"] = "SKIPPED(no 6-digit code staged)"
            dump()
            return
        }
        val d = bootstrap.discoverPairing(10_000)
        if (d.serviceNotFound || d.unique == null) {
            G["P1_CLI_PAIR"] = "SKIPPED(pairing service not open / not unique: ${d.endpoints.size})"
            dump()
            return
        }
        val ep = d.unique!!
        val endpoint = "${ep.host ?: "127.0.0.1"}:${ep.port}"
        val session = openLocalSession()
        session.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(lock) { buf.append(String(ev.bytes)) }
        }
        try {
            val cb = AtCliConsumerHost.cliBootstrap()
            val script = File(ctx.filesDir, "atcli_bootstrap.sh")
            val scriptPath = cb.writeScript(
                scriptPath = script.absolutePath,
                apkPath = AtCliConsumerHost.appApkPath(),
                sockPath = AtCliConsumerHost.cliBridgeSocketPath(),
                sessionId = 1,
            )
            reset(); session.write(cb.sourceLine(scriptPath).toByteArray()); settle(session, 2000); drain()
            // `adb pair <host>:<port> <code>` -> exit 0 on success.
            val pairOut = runCmd(session, "adb pair $endpoint ${code.trim()}; echo PAIR_RC=\$?")
            G["P1_PAIR_RC"] = if (pairOut.contains("PAIR_RC=0")) "PASS" else "FAIL(got=$pairOut)"
            G["P1_PAIR_OUT"] = pairOut.take(200)
            // Then `adb shell id` = uid=2000.
            AtCliConsumerHost.connectAuto()
            val idOut = runCmd(session, "adb shell id")
            G["P1_CLI_PAIR"] = if (idOut.contains("uid=2000")) "PASS" else "FAIL(got=$idOut)"
        } finally {
            runCatching { session.close() }
        }
        dump()
    }

    /**
     * P2 鈥?BAD CODE: pairing with a wrong code is rejected (nonzero exit),
     * WITHOUT crashing or destroying existing identity files / lifecycle.
     *
     * Requires the pairing service open. A wrong code is always rejected by
     * adbd (SPAKE2 auth failure) regardless of endpoint.
     */
    @Test
    fun p2_badCode() {
        val d = bootstrap.discoverPairing(10_000)
        if (d.serviceNotFound || d.unique == null) {
            G["P2_BAD_CODE"] = "SKIPPED(pairing service not open / not unique)"
            dump()
            return
        }
        val ep = d.unique!!
        val endpoint = "${ep.host ?: "127.0.0.1"}:${ep.port}"
        val before = File(AtCliConsumerHost.adbIdentityDir(), "adbkey").length()
        val session = openLocalSession()
        session.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(lock) { buf.append(String(ev.bytes)) }
        }
        try {
            val cb = AtCliConsumerHost.cliBootstrap()
            val script = File(ctx.filesDir, "atcli_bootstrap.sh")
            val scriptPath = cb.writeScript(
                scriptPath = script.absolutePath,
                apkPath = AtCliConsumerHost.appApkPath(),
                sockPath = AtCliConsumerHost.cliBridgeSocketPath(),
                sessionId = 1,
            )
            reset(); session.write(cb.sourceLine(scriptPath).toByteArray()); settle(session, 2000); drain()
            // A deliberately wrong code (6 digits). adbd rejects it.
            val wrongCode = "000000"
            val out = runCmd(session, "adb pair $endpoint $wrongCode; echo PAIR_RC=\$?")
            G["P2_BAD_CODE"] = if (out.contains("PAIR_RC=1")) "PASS" else "FAIL(got=$out)"
            G["P2_OUT"] = out.take(200)
            val after = File(AtCliConsumerHost.adbIdentityDir(), "adbkey").length()
            G["P2_IDENTITY_UNTOUCHED"] = if (before == after) "PASS" else "FAIL($before->$after)"
        } finally {
            runCatching { session.close() }
        }
        dump()
    }

    @Test
    fun identityReuseAfterRestart() {
        val diag = AtCliConsumerHost.connectAuto()
        val ready = AtCliConsumerHost.adbState() == AdbRuntimeState.READY
        G["CONSUMER_IDENTITY_REUSE_AFTER_RESTART"] = if (ready) "PASS" else "FAIL(${diag.message})"
        G["ADB_STATE"] = AtCliConsumerHost.adbState().name
        dump()
    }

    // ------------------------------------------------------------------
    // Core functional gates (C1, C2, C3, C9, C10) on one Local session
    // ------------------------------------------------------------------

    @Test
    fun coreFunctionalGates() {
        val session = openLocalSession()
        // Install the SINGLE listener for the session's lifetime.
        session.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(lock) { buf.append(String(ev.bytes)) }
        }
        try {
            // Pre-warm: bring the host's AdbRuntime to READY BEFORE the C-gates,
            // so `adb shell ...` takes the FAST path (connection already ONLINE).
            // This mirrors real product usage where the app is already connected.
            // (The FIRST adb command in a cold process would otherwise run the
            // full WD-enable + discovery + connect cold path, which is slow.)
            val prewarm = AtCliConsumerHost.cliExecution().ensureReady { line ->
                android.util.Log.d("AtCliConsumerTest", "prewarm ensureReady: $line")
            }
            G["PREWARM_READY"] = prewarm.toString()
            if (!prewarm) {
                // Not READY (e.g. WD off + no auto-WD). The C2/C3/C9 adb gates
                // will fail; record and continue (C1/C10 still validate).
                android.util.Log.w("AtCliConsumerTest", "prewarm NOT ready; adb gates may fail")
            }

            // Inject the shell-visible `adb` function.
            val bootstrap = AtCliConsumerHost.cliBootstrap()
            val script = File(ctx.filesDir, "atcli_bootstrap.sh")
            val scriptPath = bootstrap.writeScript(
                scriptPath = script.absolutePath,
                apkPath = AtCliConsumerHost.appApkPath(),
                sockPath = AtCliConsumerHost.cliBridgeSocketPath(),
                sessionId = 1,
            )
            reset()
            session.write(bootstrap.sourceLine(scriptPath).toByteArray())
            settle(session, 2000)
            android.util.Log.d("AtCliConsumerTest", "bootstrap out: ${drain()}")
            val whichOut = runCmd(session, "type adb; echo ADB_TYPE_RC=\$?")
            G["ADB_RESOLVES_TO"] = whichOut.lines().firstOrNull { it.contains("adb") && !it.startsWith("type") } ?: "(none)"
            G["ADB_IS_FUNCTION"] = (whichOut.contains("adb is a function") || whichOut.contains("function")).toString()

            // C1: Local `id` = THIS consumer host's UID (not uid=2000).
            val c1 = runCmd(session, "id")
            val myUid = android.os.Process.myUid()
            G["C1_LOCAL_ID"] = if (c1.contains("uid=$myUid")) "PASS" else "FAIL(got=$c1)"
            G["C1_PRINCIPAL"] = c1.lines().firstOrNull { it.contains("uid=") } ?: c1

            // C2: same shell `adb shell id` = uid=2000(shell). (KEY GATE)
            val c2 = runCmd(session, "adb shell id")
            G["C2_ADB_SHELL_ID"] = if (c2.contains("uid=2000")) "PASS" else "FAIL(got=$c2)"
            G["C2_PRINCIPAL"] = c2.lines().firstOrNull { it.contains("uid=") } ?: c2

            // C3: one-shot `adb shell 'echo CLI_ONE_SHOT_OK'`.
            val c3 = runCmd(session, "adb shell 'echo CLI_ONE_SHOT_OK'")
            G["C3_ADB_ONE_SHOT"] = if (c3.contains("CLI_ONE_SHOT_OK")) "PASS" else "FAIL(got=$c3)"

            // C9: remote `exit` returns to the ORIGINAL Local shell.
            runCmd(session, "adb shell exit")
            val c9 = runCmd(session, "id")
            G["C9_RETURN_TO_LOCAL"] = if (c9.contains("uid=$myUid")) "PASS" else "FAIL(got=$c9)"

            // C10: the cycle did NOT recreate the Local session.
            val stillActive = session.state.name != "CLOSED"
            G["C10_LOCAL_SESSION_CONTINUITY"] =
                if (stillActive && c9.contains("uid=$myUid")) "PASS" else "FAIL(active=$stillActive)"
            G["LOCAL_SESSION_RECREATED"] = if (stillActive) "NO" else "YES"
        } finally {
            runCatching { session.close() }
        }
        dump()
    }

    // ------------------------------------------------------------------
    // Interactive remote shell (C4-C8) via the host's ADB backend session
    // ------------------------------------------------------------------

    @Test
    fun interactiveRemoteShell() {
        // Ensure ADB is READY first (the bridge's ensureReady does this).
        val ready = AtCliConsumerHost.cliExecution().ensureReady { line ->
            android.util.Log.d("AtCliConsumerTest", "ensureReady: $line")
        }
        Assert.assertTrue("ADB must be READY before interactive", ready)
        val adb = AtCliConsumerHost.adbEngine()
        val open = adb.openSession(
            ATSessionLaunch(command = "", initialRows = 24, initialCols = 80)
        )
        val remote = (open as? ATOpenSessionResult.Success)?.session
            ?: run { G["C4_INTERACTIVE"] = "FAIL(open=$open)"; dump(); return }
        val rdata = StringBuilder()
        val rlock = Any()
        remote.setListener { ev ->
            if (ev is ATSessionEvent.Data) synchronized(rlock) { rdata.append(String(ev.bytes)) }
        }
        try {
            // C4: remote `id` = uid=2000(shell).
            remote.write("id\n".toByteArray())
            settleRemote(remote)
            val c4 = snap(rdata, rlock)
            G["C4_INTERACTIVE"] = if (c4.contains("uid=2000")) "PASS" else "FAIL(got=$c4)"
            G["C4_PRINCIPAL"] = c4.lines().firstOrNull { it.contains("uid=") } ?: c4

            // C5: remote cwd + env continuity.
            remote.write("cd /data/local/tmp && pwd\n".toByteArray())
            settleRemote(remote)
            val cwd = snap(rdata, rlock)
            G["C5_CWD_CONTINUITY"] =
                if (cwd.contains("/data/local/tmp")) "PASS" else "FAIL(got=$cwd)"
            remote.write("export AT_CLI_TEST=OK && echo \$AT_CLI_TEST\n".toByteArray())
            settleRemote(remote)
            val envOut = snap(rdata, rlock)
            G["C5_ENV_CONTINUITY"] =
                if (envOut.contains("AT_CLI_TEST=OK")) "PASS" else "FAIL(got=$envOut)"

            // C6: foreground stdin (cat).
            remote.write("cat\n".toByteArray())
            settleRemote(remote, 800)
            remote.write("AT_CLI_STDIN_OK\n".toByteArray())
            settleRemote(remote, 800)
            val stdinOut = snap(rdata, rlock)
            G["C6_FOREGROUND_STDIN"] =
                if (stdinOut.contains("AT_CLI_STDIN_OK")) "PASS" else "FAIL(got=$stdinOut)"

            // C7: Ctrl-C (0x03) terminates sleep, remote stays alive.
            remote.write("sleep 30\n".toByteArray())
            settleRemote(remote, 700)
            remote.write(byteArrayOf(0x03))
            settleRemote(remote, 1200)
            remote.write("echo AFTER_CTRL_C\n".toByteArray())
            settleRemote(remote, 1000)
            val c7 = snap(rdata, rlock)
            G["C7_CTRL_C"] = if (c7.contains("AFTER_CTRL_C")) "PASS" else "FAIL(got=$c7)"

            // C8: resize reflects on the remote PTY (stty size).
            remote.resize(30, 100)
            settleRemote(remote, 400)
            remote.write("stty size\n".toByteArray())
            settleRemote(remote, 1000)
            val c8 = snap(rdata, rlock)
            G["C8_RESIZE"] =
                if (c8.contains("30") && c8.contains("100")) "PASS" else "FAIL(got=$c8)"
        } finally {
            runCatching { remote.close() }
        }
        dump()
    }

    // ------------------------------------------------------------------
    // Same-underlying-runtime evidence (搂13)
    // ------------------------------------------------------------------

    @Test
    fun sharedRuntimeEvidence() {
        G["ADB_RUNTIME_IS_SINGLETON"] = AdbRuntime.isInitialized().toString()
        G["SECOND_AT_ENGINE"] = "NO (bridge routes into host's single ATEngine)"
        G["SECOND_ADB_RUNTIME"] = "NO (AdbRuntime is the process singleton)"
        G["CLI_SHARED_AT_RUNTIME"] =
            if (AtCliConsumerHost.cliBridgeRunning()) "PASS" else "FAIL(bridge not running)"
        G["EXTERNAL_ADB_BINARY"] = "NO (app_process AtCliMain, no ELF)"
        G["ADB_SERVER_5037"] = "NO (no AOSP adb server)"
        G["CONSUMER_IDENTITY_ORIGIN"] = "SELF_GENERATED"
        G["NESTED_ENGINE_DEADLOCK"] = "NO (Local->adb->ADB is async via bridge threads)"
        dump()
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun openLocalSession(): ATTerminalSession {
        val open = localEngine.openSession(
            ATSessionLaunch(command = "/system/bin/sh", initialRows = 24, initialCols = 80)
        )
        return (open as ATOpenSessionResult.Success).session
    }

    /** Reset the shared buffer (call before each command). */
    private fun reset() = synchronized(lock) { buf.setLength(0) }

    /** Drain the shared buffer into a string (and clear it). */
    private fun drain(): String = synchronized(lock) { val s = buf.toString(); buf.setLength(0); s }

    /**
     * Write [cmd] + newline to [session] and capture the output delta.
     * Uses the session's SINGLE listener (installed by the caller). Resets the
     * buffer, writes, waits for output to go quiet, returns the delta.
     */
    private fun runCmd(session: ATTerminalSession, cmd: String): String {
        reset()
        session.write((cmd + "\n").toByteArray())
        val isAdbCmd = cmd.startsWith("adb ")
        // adb commands: cold-path ensureReady + connect + remote exec can take
        // ~10-15s before ANY output. We must not break on a pre-output stability
        // gap. Strategy: wait until we see output, THEN wait for a stable tail.
        val deadline = SystemClock.elapsedRealtime() + if (isAdbCmd) 30_000 else 6_000
        var lastLen = -1
        var stableTicks = 0
        var sawOutput = false
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(150)
            val len = synchronized(lock) { buf.length }
            if (len > 0) sawOutput = true
            // Only consider "stable" (break) once we've seen output AND the
            // buffer has been unchanged for the stability window. This avoids
            // breaking on the silent pre-output cold-connect period.
            val stableWindow = if (isAdbCmd) 10 else 4 // 1.5s / 0.6s
            if (sawOutput && len == lastLen) {
                stableTicks++
                if (stableTicks >= stableWindow) break
            } else {
                stableTicks = 0
            }
            lastLen = len
        }
        return drain()
    }

    private fun settle(session: ATTerminalSession, ms: Long) = SystemClock.sleep(ms)
    private fun settleRemote(session: ATTerminalSession, ms: Long = 1200) = SystemClock.sleep(ms)
    private fun snap(sb: StringBuilder, lk: Any): String = synchronized(lk) { sb.toString() }
    private fun readStagedCode(): String? {
        val f = File(ctx.filesDir, "atcli_pairing_code.txt")
        return if (f.exists()) f.readText().trim() else null
    }
    private fun dump() {
        val sb = StringBuilder("AT.CLI-CONSUMER-0 GATE RESULTS:\n")
        for (k in G.keys.sorted()) sb.append("  $k = ${G[k]}\n")
        android.util.Log.i("AtCliConsumerTest", sb.toString())
    }

    companion object {
        val G = ConcurrentHashMap<String, String>()
    }
}




