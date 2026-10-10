// ADB Backend — provisional bootstrap placeholder.
//
// Canonical V1 §8 / §7.1: AT Engine -> ADB Backend -> ADB Engine is a
// consumer -> provider relationship. ADB Backend implements the AT Engine
// interactive/one-shot contracts in terms of ADB Engine capabilities
// (shell-v2, interactive stream, winsize).

package com.at.backend.adb

/**
 * Provisional marker for the ADB Backend module.
 */
object AdbBackendMarker {
    const val MODULE = "at-backend-adb"
}