// Local Backend — provisional bootstrap placeholder.
//
// Canonical V1 §8: AT Engine says write(bytes)/resize/close; Local Backend
// translates to local PTY / process primitives (write fd, ioctl TIOCSWINSZ,
// close + waitpid). V2 evidence: ATsh.T1 (Local PTY persistent shell).

package com.at.backend.local

/**
 * Provisional marker for the Local Backend module.
 */
object LocalBackendMarker {
    const val MODULE = "at-backend-local"
}