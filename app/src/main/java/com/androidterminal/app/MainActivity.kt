// AndroidTerminal V3 — Dynamic Session Pages Dev UI.
//
// AT.DEVAPP-0.2. Dev Preview / Consumer Validation slice.
//
// The App is a CONSUMER of the AT Engine public boundary. MainActivity owns an
// App-layer SessionPageController that dynamically creates / switches / closes
// independent session pages. Each page wraps an AppTerminal -> ATTerminalSession.
//
// All terminal operations (Send / Ctrl-C / Resize / Clear / Close) act on the
// ACTIVE page only. Switching pages never closes/recreates/resets sessions.
//
// Debug automation reuses the SAME normal session pages (no special debug UI).
//
// This is a RAW terminal preview (no Terminal Emulator / Screen State).

package com.androidterminal.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {

    private companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pages = SessionPageController()

    // UI
    private lateinit var outputText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var inputBox: EditText
    private lateinit var stateLabel: TextView
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.tvOutput)
        scroll = findViewById(R.id.scrollOutput)
        inputBox = findViewById(R.id.etInput)
        stateLabel = findViewById(R.id.tvState)
        pageLabel = findViewById(R.id.tvPage)

        findViewById<Button>(R.id.btnPrev).setOnClickListener { pages.prev(); renderPage() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { pages.next(); renderPage() }
        findViewById<Button>(R.id.btnAddPage).setOnClickListener { addPage() }
        findViewById<Button>(R.id.btnClosePage).setOnClickListener { closeActivePage() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendInput() }
        findViewById<Button>(R.id.btnCtrlC).setOnClickListener { sendCtrlC() }
        findViewById<Button>(R.id.btnResize).setOnClickListener { showResizeDialog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearOutput() }

        renderPage()
    }

    // ------------------------------------------------------------------
    // Page operations (App-layer)
    // ------------------------------------------------------------------

    /**
     * Debug-only (AT.DEVAPP-0.2): create a NEW independent normal session page
     * and make it active. Used by instrumentation to drive a normal page.
     * @return the created page.
     */
    fun createPage(): SessionPage {
        val page = pages.add()
        // Wire this page's output into the shared renderer (only when active).
        page.terminal = AppTerminal(eventListener = { ev -> onPageEvent(page, ev) })
        renderPage()
        return page
    }

    /** Debug-only: open the active page's session (call after [createPage]). */
    fun openActiveSession(rows: Int = DEFAULT_ROWS, cols: Int = DEFAULT_COLS): Boolean {
        val page = pages.activePage ?: return false
        page.rows = rows
        page.cols = cols
        val ok = page.terminal.open(rows, cols)
        if (ok) {
            // Apply the requested initial size (PTY starts at 0x0 per T1 evidence).
            page.terminal.resize(rows, cols)
            page.terminal.session?.write(
                "echo === AT V3 LOCAL SESSION (page ${page.pageId}) ===; id; tty\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        renderPage()
        return ok
    }

    /** Debug-only: the active page's session, if open. */
    fun activeSession(): com.at.engine.ATTerminalSession? = pages.activePage?.terminal?.session

    private fun addPage() {
        val page = pages.add()
        page.terminal = AppTerminal(eventListener = { ev -> onPageEvent(page, ev) })
        openActiveSession()
        renderPage()
    }

    private fun closeActivePage() {
        pages.closeActive()
        renderPage()
    }

    private fun renderPage() {
        val page = pages.activePage
        if (page == null) {
            pageLabel.text = "- / -"
            stateLabel.text = "State: -   | no session (tap +)"
            outputText.text = ""
            return
        }
        pageLabel.text = "${pages.size} page(s)  #${page.pageId} (${page.state.name})"
        stateLabel.text = "State: ${page.state.name}"
        outputText.text = page.outputBuffer.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // Terminal operations (act on ACTIVE page only)
    // ------------------------------------------------------------------

    private fun sendInput() {
        val page = pages.activePage ?: return
        if (page.state != ATSessionState.ACTIVE) return
        val text = inputBox.text?.toString() ?: ""
        if (text.isEmpty()) return
        page.terminal.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
        inputBox.setText("")
    }

    private fun sendCtrlC() {
        val page = pages.activePage ?: return
        if (page.state != ATSessionState.ACTIVE) return
        page.terminal.ctrlC() // 0x03 -> SIGINT to foreground
        stateLabel.text = "State: ${page.state.name}   | Ctrl-C sent"
    }

    private fun showResizeDialog() {
        val page = pages.activePage ?: return
        val rows = EditText(this).apply { setText(page.rows.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val cols = EditText(this).apply { setText(page.cols.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(rows)
            addView(cols)
        }
        AlertDialog.Builder(this)
            .setTitle("Resize Session")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val r = rows.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                val c = cols.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                page.rows = r
                page.cols = c
                page.terminal.resize(r, c)
                stateLabel.text = "State: ${page.state.name}   | RESIZED ${r}x$c"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearOutput() {
        val page = pages.activePage ?: return
        page.outputBuffer.clear()
        outputText.text = ""
    }

    // ------------------------------------------------------------------
    // Event handling: buffer output on its own page, render when active
    // ------------------------------------------------------------------

    private fun onPageEvent(page: SessionPage, ev: ATSessionEvent) {
        when (ev) {
            is ATSessionEvent.Data -> {
                val s = String(ev.bytes, StandardCharsets.UTF_8)
                page.outputBuffer.append(s)
                if (pages.activePage === page) {
                    mainHandler.post { renderPage() }
                }
                // Inactive pages: just buffered; visible on switch-back.
            }
            else -> {
                // state events only update the label when this page is active
                if (pages.activePage === page) {
                    mainHandler.post { renderPage() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close ALL sessions (PTY/fd/shell cleanup). Retention = DEFERRED.
        pages.closeAll()
    }
}