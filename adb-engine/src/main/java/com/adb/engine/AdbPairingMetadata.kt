// AdbPairingMetadata — persistent pairing-trust metadata bound to key identity.
//
// AT.REPAIRING-UX-1: closes the identity-lifecycle gap where a fresh install
// generates a NEW key (loadOrCreate) but the bootstrap cannot distinguish
// "newly generated, never paired" from "existing, previously paired".
//
// This store persists the public-key fingerprint of the key that was last
// successfully paired (plus the device GUID when available). On subsequent
// launches:
//   - fingerprint matches current key  -> PAIRED_KNOWN  (skip pairing)
//   - no metadata / fingerprint mismatch -> NEEDS_PAIRING (offer pairing)
//
// The metadata lives in the consumer's app-private identityDir alongside
// adbkey / adbkey.pub. It is NOT a second identity store — it is a small
// trust annotation on the EXISTING identity.
//
// Security: only the SHA-256 fingerprint of the public key is stored.
// No private key material, no full certificate, no pairing code.

package com.adb.engine

import java.io.File
import java.security.MessageDigest

/**
 * Persistent pairing-trust metadata for a consumer identity directory.
 *
 * File format (properties, human-readable, one key=value per line):
 * ```
 * paired_key_fingerprint=<64 hex chars SHA-256 of SPKI DER>
 * device_guid=<GUID from pairing success, optional>
 * paired_at=<Unix epoch millis>
 * ```
 */
class AdbPairingMetadata(identityDir: String) {

    companion object {
        private const val FILE_NAME = "pairing_meta"
        private const val KEY_FINGERPRINT = "paired_key_fingerprint"
        private const val KEY_GUID = "device_guid"
        private const val KEY_TIMESTAMP = "paired_at"

        /**
         * Compute the SHA-256 fingerprint of the RSA public key (SPKI DER).
         *
         * Reads the adbkey.pub file (Android format: base64 blob + comment)
         * and hashes the decoded public-key bytes. This is the same key
         * material that adbd compares during TLS certificate verification,
         * so a fingerprint match means "this key was the one that was paired".
         *
         * @return 64-char lowercase hex string, or null if the key cannot be read.
         */
        fun computeFingerprint(identityDir: String): String? {
            val pubFile = File(identityDir, "adbkey.pub")
            if (!pubFile.exists()) return null
            return try {
                val content = pubFile.readText().trim()
                // adbkey.pub format: "<base64> <comment>"
                val b64 = content.substringBefore(' ')
                val bytes = java.util.Base64.getDecoder().decode(b64)
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(bytes)
                hash.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private val file = File(identityDir, FILE_NAME)

    /** Load the stored pairing fingerprint, or null if no metadata exists. */
    fun pairedFingerprint(): String? {
        if (!file.exists()) return null
        return try {
            val props = file.readText().lineSequence()
                .associate { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx) to line.substring(idx + 1)
                    else line to ""
                }
            props[KEY_FINGERPRINT]?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Load the stored device GUID, or empty string. */
    fun deviceGuid(): String {
        if (!file.exists()) return ""
        return try {
            val props = file.readText().lineSequence()
                .associate { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx) to line.substring(idx + 1)
                    else line to ""
                }
            props[KEY_GUID] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Whether the current key's fingerprint matches the stored pairing
     * fingerprint. This is the PRIMARY trust check:
     *   - true  -> the key that is now in use is the one that was paired.
     *   - false -> either never paired, or the key was rotated (fresh install).
     */
    fun isCurrentKeyPaired(): Boolean {
        val current = computeFingerprint(file.parent ?: return false) ?: return false
        val stored = pairedFingerprint() ?: return false
        return current.equals(stored, ignoreCase = true)
    }

    /**
     * Persist pairing metadata after a successful pairing.
     * Binds the fingerprint + GUID to the current key.
     */
    fun markPaired(fingerprint: String, guid: String) {
        try {
            val content = buildString {
                appendLine("$KEY_FINGERPRINT=$fingerprint")
                appendLine("$KEY_GUID=$guid")
                appendLine("$KEY_TIMESTAMP=${System.currentTimeMillis()}")
            }
            file.writeText(content)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (e: Exception) {
            // Non-fatal: metadata persistence failure should not block pairing.
            // The key is still trusted on the device side; we just won't have
            // the local annotation. Next launch will do UNKNOWN_TRUST probe.
        }
    }

    /** Remove pairing metadata (e.g., after "forget device"). */
    fun clear() {
        if (file.exists()) file.delete()
    }
}
