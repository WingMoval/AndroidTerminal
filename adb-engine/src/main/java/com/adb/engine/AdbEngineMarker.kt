// ADB Engine — provisional bootstrap placeholder.
//
// Canonical V1 §7.2: ADB Engine independently owns ADB protocol, pairing,
// identity, key/trust, wireless discovery, endpoint, TLS, connection, streams,
// shell-v2 transport, recovery, and diagnostics.
//
// AT.V3-0: no implementation yet. Content migrates from V2 verified source
// during AT.V3-1 (ADB Engine Extraction / Migration).

package com.adb.engine

/**
 * Provisional marker for the ADB Engine module.
 *
 * Real boundary (Canonical V1 §7.2):
 *   Pairing / Identity / Key-Trust / Discovery / Endpoint / TLS /
 *   Connection / Protocol / shell-v2 / Interactive Stream / Recovery / Diagnostics
 */
object AdbEngineMarker {
    const val MODULE = "adb-engine"
}