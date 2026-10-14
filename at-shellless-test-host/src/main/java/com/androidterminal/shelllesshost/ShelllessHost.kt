// AT.TERM-0 — shellless consumer composition root (VALIDATION ONLY).
//
// Wires the AT Engine with the shellless persistent backend. The CONSUMER
// reaches every session operation ONLY through ATEngine.openSession(...);
// it never calls ShelllessPersistentBackend / ShelllessPtyNative directly.
//
// One-shot is NOT wired (null): the core of this round is persistent terminal
// independence; an artificial one-shot provider is deliberately not created
// (AT.TERM-0 §35).

package com.androidterminal.shelllesshost

import com.at.engine.ATEngine

object ShelllessHost {

    /** The consumer facade. Only the persistent backend is wired (shellless). */
    fun engine(): ATEngine = ATEngine(oneShot = null, persistent = ShelllessPersistentBackend())
}
