// AT Engine — provisional bootstrap placeholder.
//
// Canonical V1 §5 / §7.3: AT Engine = reusable Terminal Execution Capability:
//   One-shot Execution (execute -> stdout/stderr/exitCode)
//   Persistent Terminal (TerminalSession / Terminal Emulator / Screen State /
//                        Session Lifecycle / Input / Resize)
//   Backend abstraction (Local / ADB / future SSH)
//
// AT.V3-0: no implementation yet. Contracts arrive via AT.CONTRACT-0 draft
// after bootstrap.

package com.at.engine

/**
 * Provisional marker for the AT Engine module.
 *
 * Real boundary (Canonical V1 §5 / §7.3):
 *   One-shot Execution
 *   Persistent Terminal (TerminalSession / Emulator / Screen State / lifecycle)
 *   Backend abstraction
 */
object AtEngineMarker {
    const val MODULE = "at-engine"
}