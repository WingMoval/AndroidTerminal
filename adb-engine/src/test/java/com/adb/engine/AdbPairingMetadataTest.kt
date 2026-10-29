// AT.REPAIRING-UX-1.CLOSEOUT — AdbPairingMetadata unit tests.
//
// Covers: fingerprint binding, metadata lifecycle, security invariants.
// Pure host-side tests (no Android, no device, no AdbRuntime).

package com.adb.engine

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.nio.file.Files

class AdbPairingMetadataTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("pairing_meta_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // M1: fresh install / no metadata → current key not paired
    // ------------------------------------------------------------------

    @Test
    fun `M1 no metadata - isCurrentKeyPaired returns false`() {
        // Create a dummy adbkey.pub so computeFingerprint can work
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        assertFalse("No metadata file should mean not paired", meta.isCurrentKeyPaired())
    }

    @Test
    fun `M1 no metadata - pairedFingerprint returns null`() {
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        assertNull(meta.pairedFingerprint())
    }

    // ------------------------------------------------------------------
    // M2: successful pair → metadata written
    // ------------------------------------------------------------------

    @Test
    fun `M2 markPaired writes fingerprint and guid`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)
        assertNotNull("Fingerprint should be computable", fp)

        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp!!, "test-guid-123")

        assertTrue("Metadata file should exist", File(tempDir, "pairing_meta").exists())
        assertEquals(fp, meta.pairedFingerprint())
        assertEquals("test-guid-123", meta.deviceGuid())
        assertTrue("isCurrentKeyPaired should be true after markPaired", meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // M3/M4: metadata readable after restart (simulated by re-reading)
    // ------------------------------------------------------------------

    @Test
    fun `M3 metadata readable after re-instantiation (simulates app restart)`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta1 = AdbPairingMetadata(tempDir.absolutePath)
        meta1.markPaired(fp, "guid-abc")

        // Simulate restart: new instance
        val meta2 = AdbPairingMetadata(tempDir.absolutePath)
        assertEquals(fp, meta2.pairedFingerprint())
        assertEquals("guid-abc", meta2.deviceGuid())
        assertTrue(meta2.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // M5: key fingerprint changes → old metadata invalid
    // ------------------------------------------------------------------

    @Test
    fun `M5 key change invalidates old metadata`() {
        // Write first key
        writeFakePubKey("AAAA" + "A".repeat(80))
        val fp1 = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp1, "guid-1")
        assertTrue(meta.isCurrentKeyPaired())

        // Simulate key rotation: write different key
        writeFakePubKey("BBBB" + "B".repeat(80))
        assertFalse("Different key should invalidate old metadata", meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // M6: metadata missing → pairing required
    // ------------------------------------------------------------------

    @Test
    fun `M6 missing metadata file - isCurrentKeyPaired false`() {
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        // Explicitly delete if it exists
        File(tempDir, "pairing_meta").delete()
        assertFalse(meta.isCurrentKeyPaired())
        assertNull(meta.pairedFingerprint())
    }

    // ------------------------------------------------------------------
    // M7: metadata malformed → safe fallback, no crash
    // ------------------------------------------------------------------

    @Test
    fun `M7 malformed metadata - safe fallback no crash`() {
        writeFakePubKey()
        File(tempDir, "pairing_meta").writeText("garbage\nno equals sign\n###")
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        // Should not throw
        val fp = meta.pairedFingerprint()
        // Either null or some garbage string, but no exception
        assertFalse("Malformed metadata should not report as paired", meta.isCurrentKeyPaired())
    }

    @Test
    fun `M7b empty metadata file - safe fallback`() {
        writeFakePubKey()
        File(tempDir, "pairing_meta").writeText("")
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        assertFalse(meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // M8: pair failure → metadata NOT written
    // ------------------------------------------------------------------

    @Test
    fun `M8 pair failure does not write metadata`() {
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        // Simulate: pair() returns failure, so markPaired is never called
        // We verify that simply NOT calling markPaired leaves no file
        assertFalse(File(tempDir, "pairing_meta").exists())
        assertFalse(meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // M9: pair success → timestamp updated
    // ------------------------------------------------------------------

    @Test
    fun `M9 markPaired updates timestamp`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        val before = System.currentTimeMillis()
        meta.markPaired(fp, "guid-ts")
        val after = System.currentTimeMillis()

        val content = File(tempDir, "pairing_meta").readText()
        assertTrue("Should contain paired_at", content.contains("paired_at="))
        // Extract timestamp
        val tsLine = content.lines().first { it.startsWith("paired_at=") }
        val ts = tsLine.substringAfter("paired_at=").toLong()
        assertTrue("Timestamp should be >= before", ts >= before)
        assertTrue("Timestamp should be <= after", ts <= after)
    }

    // ------------------------------------------------------------------
    // M10: pairing code never persisted
    // ------------------------------------------------------------------

    @Test
    fun `M10 pairing code not stored in metadata`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp, "guid-no-code")

        val content = File(tempDir, "pairing_meta").readText()
        // The code is never passed to markPaired, so it can't be stored
        // Verify only expected fields are present
        val keys = content.lines()
            .filter { it.contains('=') }
            .map { it.substringBefore('=') }
            .toSet()
        assertTrue("Should have fingerprint key", keys.contains("paired_key_fingerprint"))
        assertTrue("Should have guid key", keys.contains("device_guid"))
        assertTrue("Should have timestamp key", keys.contains("paired_at"))
        // No unexpected keys
        val expected = setOf("paired_key_fingerprint", "device_guid", "paired_at")
        assertEquals("Only expected fields should be present", expected, keys)
    }

    // ------------------------------------------------------------------
    // I3: pairing does NOT regenerate key
    // ------------------------------------------------------------------

    @Test
    fun `I3 markPaired does not modify adbkey or adbkey pub`() {
        writeFakePubKey()
        val pubBefore = File(tempDir, "adbkey.pub").readText()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!

        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp, "guid-i3")

        val pubAfter = File(tempDir, "adbkey.pub").readText()
        assertEquals("adbkey.pub should be unchanged by markPaired", pubBefore, pubAfter)
        assertEquals(fp, AdbPairingMetadata.computeFingerprint(tempDir.absolutePath))
    }

    // ------------------------------------------------------------------
    // B4: mismatched fingerprint → not paired
    // ------------------------------------------------------------------

    @Test
    fun `B4 mismatched fingerprint metadata - isCurrentKeyPaired false`() {
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        // Write metadata with WRONG fingerprint
        meta.markPaired("0000000000000000000000000000000000000000000000000000000000000000", "guid-b4")
        assertFalse("Mismatched fingerprint should not be paired", meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // B5: no metadata → intended UNKNOWN_TRUST behavior
    // ------------------------------------------------------------------

    @Test
    fun `B5 no metadata - safe for UNKNOWN_TRUST probe`() {
        writeFakePubKey()
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        // No metadata file exists
        assertFalse(meta.isCurrentKeyPaired())
        // This means bootstrapState will return PAIRING_REQUIRED
        // (for fresh install) or the caller can choose to probe
        // The metadata layer itself is safe: no crash, clear false
    }

    // ------------------------------------------------------------------
    // Security: no private key, no full cert in metadata
    // ------------------------------------------------------------------

    @Test
    fun `security - metadata contains no private key material`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp, "guid-sec")

        val content = File(tempDir, "pairing_meta").readText()
        assertFalse("Should not contain 'PRIVATE'", content.contains("PRIVATE"))
        assertFalse("Should not contain 'BEGIN RSA'", content.contains("BEGIN RSA"))
        assertFalse("Should not contain 'BEGIN PRIVATE'", content.contains("BEGIN PRIVATE"))
        // Fingerprint is 64 hex chars
        val fpLine = content.lines().first { it.startsWith("paired_key_fingerprint=") }
        val storedFp = fpLine.substringAfter("paired_key_fingerprint=")
        assertEquals("Fingerprint should be 64 hex chars", 64, storedFp.length)
        assertTrue("Fingerprint should be lowercase hex", storedFp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ------------------------------------------------------------------
    // clear() removes metadata
    // ------------------------------------------------------------------

    @Test
    fun `clear removes metadata file`() {
        writeFakePubKey()
        val fp = AdbPairingMetadata.computeFingerprint(tempDir.absolutePath)!!
        val meta = AdbPairingMetadata(tempDir.absolutePath)
        meta.markPaired(fp, "guid-clear")
        assertTrue(File(tempDir, "pairing_meta").exists())

        meta.clear()
        assertFalse("File should be deleted after clear()", File(tempDir, "pairing_meta").exists())
        assertFalse(meta.isCurrentKeyPaired())
    }

    // ------------------------------------------------------------------
    // Helper: write a fake adbkey.pub for fingerprint testing
    // ------------------------------------------------------------------

    private fun writeFakePubKey(base64Body: String = "QUJD" + "A".repeat(80)) {
        File(tempDir, "adbkey.pub").writeText("$base64Body androidterminal@app\n")
    }
}
