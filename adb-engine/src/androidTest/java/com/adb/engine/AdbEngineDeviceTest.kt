// ADB Engine — real-device parity validation (AT.V3-1).
//
// Drives the migrated AdbEngine facade against a real device over loopback.
// Usage (instrumentation args):
//   host    device loopback (default "127.0.0.1")
//   port    adbd wireless connect port (e.g. 39019)
//   keyDir  directory seeded with a trusted adbkey/adbkey.pub/adb_cert.pem
//
// Verifies: identity load, connect (Golden Chain), one-shot shell
// (stdout/stderr/exitCode), connection state.

package com.adb.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbEngineDeviceTest {

    private fun arg(name: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: default

    @Test
    fun adbEngineInteractiveStreamPrimitive() {
        val host = arg("host", "127.0.0.1")
        val port = arg("port", "39019").toInt()
        val keyDir = arg("keyDir", "")
        assertTrue("keyDir must be provided", keyDir.isNotEmpty())

        val conn = AdbEngine.connect(host, port, keyDir)
        assertTrue("connect should succeed", conn)

        // Open an interactive stream primitive (shell,v2,pty). NOT a
        // TerminalSession / ADB Backend — just the ADB stream primitive.
        val stream = AdbEngine.openInteractive("shell,v2,pty:")
        assertTrue("interactive stream should open", stream != null)
        val s = stream!!

        // Persistent I/O on the same stream.
        s.write("echo PTY_FIRST\n")
        Thread.sleep(300)
        val out1 = readUntil(s, "PTY_FIRST")
        assertTrue("pty should echo PTY_FIRST, got: $out1", out1.contains("PTY_FIRST"))

        s.write("echo PTY_SECOND\n")
        Thread.sleep(300)
        val out2 = readUntil(s, "PTY_SECOND")
        assertTrue("pty should echo PTY_SECOND, got: $out2", out2.contains("PTY_SECOND"))

        // Resize primitive.
        assertTrue("resize should succeed", s.resize(40, 120))
        Thread.sleep(200)

        // Clean close.
        s.close()
        assertTrue("stream should be closed", !s.isOpen)

        AdbEngine.disconnect()
    }

    private fun readUntil(stream: AdbInteractiveStream, marker: String, maxMs: Long = 3000): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val buf = ByteArray(4096)
            val n = stream.read(buf, 100)
            if (n > 0) {
                sb.append(String(buf, 0, n))
                if (sb.contains(marker)) return sb.toString()
            }
        }
        return sb.toString()
    }

    @Test
    fun adbEngineConnectAndOneShotShell() {
        val host = arg("host", "127.0.0.1")
        val port = arg("port", "39019").toInt()
        val keyDir = arg("keyDir", "")

        assertTrue("keyDir must be provided (seeded with trusted key)", keyDir.isNotEmpty())

        // Identity: load a seeded key (or generate if absent).
        val conn = AdbEngine.connect(host, port, keyDir)
        assertTrue("connect to $host:$port should succeed with trusted identity", conn)

        // Connection state should be ONLINE.
        assertEquals(AdbConnectionState.ONLINE, AdbEngine.connectionState())

        // One-shot shell: id -> uid=2000(shell)
        val r = AdbEngine.executeShell("id")
        assertEquals("shell should complete", AdbShellStatus.COMPLETED, r.status)
        assertTrue("stdout should contain uid=2000(shell), got: ${r.stdout}", r.stdout.contains("uid=2000(shell)"))
        assertEquals(0, r.exitCode)

        // Connection reuse: a second command on the SAME connection.
        val rReuse = AdbEngine.executeShell("echo REUSE_OK")
        assertEquals(AdbShellStatus.COMPLETED, rReuse.status)
        assertTrue("reuse should work, got: ${rReuse.stdout}", rReuse.stdout.contains("REUSE_OK"))

        // One-shot with non-zero exit (device exit != transport failure).
        val r2 = AdbEngine.executeShell("sh -c 'exit 7'")
        assertEquals(AdbShellStatus.COMPLETED, r2.status)
        assertEquals(7, r2.exitCode)

        // stderr separation.
        val r3 = AdbEngine.executeShell("echo err >&2")
        assertEquals(AdbShellStatus.COMPLETED, r3.status)
        assertTrue(r3.stderr.contains("err"))

        AdbEngine.disconnect()
        assertEquals(AdbConnectionState.DISCONNECTED, AdbEngine.connectionState())
    }
}