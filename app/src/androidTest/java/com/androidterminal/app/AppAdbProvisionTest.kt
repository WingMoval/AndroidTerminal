// AndroidTerminal V3 �?Developer ADB Provisioning harness (AT.HUMAN-ADB-0 §23).
//
// DEBUG / TEST ONLY. NOT release source set. NOT First-run onboarding.
//
// Purpose: provision the MAIN APP's OWN persistent ADB identity
// (filesDir/adb_identity) so the Human Preview can auto-connect to the device's
// Wireless Debugging adbd WITHOUT re-pairing on every launch.
//
// Flow (single AdbRuntime owner):
//   1. AppRuntime.initialize(ctx)           -> wire AdbRuntime + fix app keyDir
//   2. connectAuto -> fails (fresh untrusted identity; loadOrCreate ran)
//   3. discoverPairing (user must open "使用配对码配对设�? dialog)
//   4. AdbEngine.pair(host, pair_port, CODE, keyDir)   (code via -e code)
//   5. connectAuto -> READY
//   6. id -> uid=2000(shell)
//
// The pairing code is a TRANSIENT user input (-e code), NEVER written to docs /
// evidence / git / long-term log.

package com.androidterminal.app

import com.at.composition.android.ATAdbReadinessState

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.at.engine.ATExecutionOutcome
import com.at.engine.ATExecutionRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppAdbProvisionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val pairingCode get() = args.getString("code") ?: "000000"

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    @Test
    fun provisionAppIdentity() {
        // 1. Wire the SAME composition root the app uses at runtime.
        AppRuntime.initialize(ctx)
        val keyDir = AppRuntime.adbIdentityDir()
        assertTrue("identity dir exists", keyDir.isDirectory)

        // 2. First connectAuto: creates identity (loadOrCreate) + fails (untrusted).
        AppRuntime.connectAuto()
        assertTrue("BEFORE_PAIR_CONNECT=FAIL_EXPECTED; got ${AppRuntime.adbState()}",
            AppRuntime.adbState() != ATAdbReadinessState.READY)
        assertTrue("APP_ADB_IDENTITY_SELF_GENERATED=YES (adbkey exists in $keyDir)",
            File(keyDir, "adbkey").exists())
        assertTrue("identity public key exists", File(keyDir, "adbkey.pub").exists())
        val pubBefore = File(keyDir, "adbkey.pub").readText()

        // 3. Discover the pairing service (user has the pairing dialog open).
        val endpoints = AppRuntime.adbControl.discoverPairing().endpoints
        assertTrue("pairing service must be discovered while dialog open; got $endpoints",
            endpoints.isNotEmpty())
        val endpoint = endpoints.first()
        val hostIp = endpoint.host ?: "127.0.0.1"

        // 4. Pair with the SAME app identity (via the composition facade —
        //    the single pairing boundary is INTERNAL to the composition).
        val result = AppRuntime.adbControl.pair(hostIp, endpoint.port, pairingCode)
        assertTrue("pair must succeed, got $result", result.success)

        // 5. connectAuto -> READY with the SAME identity.
        assertTrue("connectAuto after pair must be READY",
            await(30_000) { AppRuntime.connectAuto(); AppRuntime.adbState() == ATAdbReadinessState.READY })

        // Identity continuity: same public key across pair/connect.
        val pubAfter = File(keyDir, "adbkey.pub").readText()
        assertTrue("identity continuity (same pub key)", pubAfter == pubBefore)

        // 6. Verify via the AT Engine consumer path (uid=2000 shell).
        val r = AppRuntime.adbEngine().execute(ATExecutionRequest("id"))
        assertTrue("id outcome COMPLETED, got ${r.outcome}", r.outcome == ATExecutionOutcome.COMPLETED)
        assertTrue("id uid=2000(shell), got: ${r.stdout}", r.stdout.contains("uid=2000"))
    }
}
