// AndroidTerminal V3 — Session Page（App/UI 层）。
//
// AT.DEVAPP-0.2. One page = one independent TerminalSession. The page owns the
// AppTerminal (which owns the ATTerminalSession) plus its own output buffer and
// size. It is an App-layer concept; AT Engine knows nothing about pages/tabs.

package com.androidterminal.app

import com.at.engine.ATSessionState

/**
 * A single dynamic session page.
 *
 * @param pageId process-unique id assigned by the controller.
 * @param kind the backend kind of this page's session (fixed at creation;
 *        switching pages never recreates/resets the backend).
 * @param terminal the AppTerminal for this session (owns ATTerminalSession).
 * @param rows current terminal rows.
 * @param cols current terminal cols.
 */
class SessionPage(
    val pageId: Int,
    val kind: SessionBackendKind,
    var terminal: AppTerminal,
) {
    /** This page's own output buffer (inactive pages keep accumulating). */
    val outputBuffer: StringBuilder = StringBuilder()

    var rows: Int = 24
    var cols: Int = 80

    val state: ATSessionState
        get() = terminal.state()

    /** Close this page's session. Idempotent. */
    fun close() = terminal.close()
}