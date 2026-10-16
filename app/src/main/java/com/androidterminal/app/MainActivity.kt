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
import android.view.View
import com.androidterminal.app.cli.LocalSessionBootstrap
import java.io.File
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.adb.engine.AdbEngine
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState
import com.at.engine.ATSessionEvent
import com.at.engine.ATSessionState
import com.at.engine.ATTerminalSession
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {

    private companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pages = SessionPageController()

    // AT.DEBUG-0: process-scoped session registry + debug-only bridge.
    private var sessionRegistry: AppSessionRegistry? = null
    private var debugBridge: com.androidterminal.app.debug.DebugBridge? = null

    // UI
    private lateinit var outputText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var inputBox: EditText
    private lateinit var stateLabel: TextView
    private lateinit var pageLabel: TextView
    private lateinit var adbStateLabel: TextView
    private lateinit var adbHint: TextView
    private var debugStatusLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wire the single ADB runtime owner (process-scoped composition root).
        AppRuntime.initialize(applicationContext)
        setContentView(R.layout.activity_main)

        // AT.DEBUG-0: registry + debug-only bridge (loopback, debug builds only).
        sessionRegistry = AppSessionRegistry { pages.activePage?.pageId }
        // AT.CLI-0.1A: ADB lifecycle status hub (read-only AdbRuntime observer,
        // fans out `[adb] ...` to Local sessions that used adb).
        AppRuntime.initStatusHub(sessionRegistry!!)
        if (com.androidterminal.app.BuildConfig.DEBUG) {
            debugBridge = com.androidterminal.app.debug.DebugBridge(sessionRegistry!!)
            debugBridge?.start()
        }

        outputText = findViewById(R.id.tvOutput)
        scroll = findViewById(R.id.scrollOutput)
        inputBox = findViewById(R.id.etInput)
        stateLabel = findViewById(R.id.tvState)
        pageLabel = findViewById(R.id.tvPage)
        adbStateLabel = findViewById(R.id.tvAdbState)
        adbHint = findViewById(R.id.tvAdbHint)
        debugStatusLabel = findViewById(R.id.tvDebugBridge)

        findViewById<Button>(R.id.btnPrev).setOnClickListener { pages.prev(); renderPage() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { pages.next(); renderPage() }
        findViewById<Button>(R.id.btnAddPage).setOnClickListener { showNewSessionChooser() }
        findViewById<Button>(R.id.btnRetryAdb).setOnClickListener { retryAdb() }
        findViewById<Button>(R.id.btnPairAdb).setOnClickListener {
            startActivity(android.content.Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.btnClosePage).setOnClickListener { closeActivePage() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendInput() }
        findViewById<Button>(R.id.btnCtrlC).setOnClickListener { sendCtrlC() }
        findViewById<Button>(R.id.btnResize).setOnClickListener { showResizeDialog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearOutput() }

        updateAdbStatus()
        renderPage()
    }

    // ------------------------------------------------------------------
    // ADB status. The Wi-Fi / Wireless-Debugging gate is the DEVICE's own
    // mechanism (settings, user-operated). The app does NOT open settings /
    // auto-navigate — it only reflects the AdbRuntime lifecycle state and
    // offers a Retry (connectAuto) so the user can re-attempt after they have
    // enabled Wireless Debugging and paired the device themselves.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Page operations (App-layer)
    // ------------------------------------------------------------------

    /**
     * Debug-only: create a NEW independent normal session page (default Local)
     * and make it active. Used by instrumentation to drive a normal page.
     * @return the created page.
     */
    fun createPage(): SessionPage = createPage(SessionBackendKind.LOCAL)

    /**
     * Create a new independent normal session page of the given backend kind
     * (LOCAL or ADB) and make it active. Local and ADB share the SAME
     * SessionPage; only the backend kind differs (fixed at creation).
     */
    fun createPage(kind: SessionBackendKind): SessionPage {
        // Add a placeholder page first (assigns a stable pageId), then attach
        // the real AppTerminal wired with that pageId + the debug registry.
        val placeholder = AppTerminal(eventListener = {}, kind = kind, engine = engineFor(kind))
        val page = pages.add(kind, placeholder)
        page.terminal = AppTerminal(
            eventListener = { ev -> onPageEvent(page, ev) },
            kind = kind,
            engine = engineFor(kind),
            sessionId = page.pageId,
            // Use the process-scoped registry (AppRuntime) so it's consistent
            // across Activity recreations (NOT the Activity's field, which
            // would be a new instance on recreate while StatusHub holds the
            // old one).
            registry = AppRuntime.sessionRegistry(),
            onInputActivity = { source, bytes ->
                // External input activity = local UI annotation (NOT remote stdout).
                if (source == InputSource.DEBUG) {
                    val text = String(bytes, StandardCharsets.UTF_8).trimEnd('\n')
                    mainHandler.post {
                        page.outputBuffer.append("DEBUG > ").append(text).append("\n")
                        if (pages.activePage === page) renderPage()
                    }
                }
            },
        )
        renderPage()
        return page
    }

    /** Debug-only: open the active page's session (call after [createPage]). */
    fun openActiveSession(rows: Int = DEFAULT_ROWS, cols: Int = DEFAULT_COLS): Boolean {
        val page = pages.activePage ?: return false
        page.rows = rows
        page.cols = cols
        val ok = page.terminal.open(rows, cols, command = launchCommand(page.kind))
        if (ok) {
            // Apply the requested initial size (PTY starts at 0x0 per T1 evidence).
            page.terminal.resize(rows, cols)
            page.terminal.write(banner(page).toByteArray(StandardCharsets.UTF_8))
        }
        renderPage()
        return ok
    }

    /** Debug-only: the active page's session, if open. */
    fun activeSession(): ATTerminalSession? = pages.activePage?.terminal?.session

    /** Debug/test: the active page's SharedSessionHandle (observer fan-out). */
    fun activeSharedHandle(): SharedSessionHandle? =
        pages.activePage?.terminal?.sharedHandle

    /** Debug-only: the active page's backend kind. */
    fun activePageKind(): SessionBackendKind? = pages.activePage?.kind

    /** Debug/test: the input box (so tests can drive Human input without uiautomator). */
    val testInputBox: android.widget.EditText get() = inputBox

    /** Debug/test: number of pages. */
    fun pagesSize(): Int = pages.size

    /** Debug/test: close the active page (so tests don't accumulate PTYs). */
    fun testCloseActivePage() {
        closeActivePage()
    }

    /** Debug/test: the active page's id (or -1). */
    fun activePageId(): Int = pages.activePage?.pageId ?: -1

    /** Debug/test: state name of a page by id (or null). */
    fun pageStateById(id: Int): String? = pages.allPages().firstOrNull { it.pageId == id }?.state?.name

    /** Debug/test: switch to a specific page by id. */
    fun switchToPage(id: Int) {
        val target = pages.allPages().indexOfFirst { it.pageId == id }
        if (target < 0) return
        // Walk next/prev until active == target.
        repeat(pages.size) {
            if (pages.activePage?.pageId == id) return
            pages.next()
        }
        renderPage()
    }

    private fun engineFor(kind: SessionBackendKind): com.at.engine.ATEngine? =
        if (kind == SessionBackendKind.ADB) AppRuntime.adbEngine() else null

    private fun launchCommand(kind: SessionBackendKind): String =
        if (kind == SessionBackendKind.ADB) "" else "/system/bin/sh"

    private fun banner(page: SessionPage): String {
        val tag = if (page.kind == SessionBackendKind.ADB) "ADB" else "LOCAL"
        // AT.CLI-0.1: for Local sessions, write the `adb` shell function to a
        // PER-SESSION script in the app filesDir and source it (robust vs
        // PTY-mangled multi-line function). The script exports AT_SESSION_ID
        // (= pageId) so AdbCliMain binds ADB lifecycle status to THIS session
        // (AT.CLI-0.1A). `command -v adb` then resolves in the shell.
        val bootstrap = if (page.kind == SessionBackendKind.LOCAL) {
            runCatching {
                val apk = AppRuntime.appApkPath()
                val sock = AppRuntime.cliBridgeSocketPath()
                // Per-session script so AT_SESSION_ID is unique per Local page.
                val scriptName = "${LocalSessionBootstrap.BOOTSTRAP_SCRIPT}.${page.pageId}"
                val script = LocalSessionBootstrap.writeScript(
                    scriptPath = File(filesDir, scriptName).absolutePath,
                    apkPath = apk,
                    sockPath = sock,
                    sessionId = page.pageId,
                )
                runCatching {
                    File(filesDir, "adb_bootstrap_diag.txt").writeText(
                        "apk=[$apk]\nsock=[$sock]\nscript=[$script]\nsession=${page.pageId}\n"
                    )
                }
                LocalSessionBootstrap.sourceLine(script)
            }.getOrDefault("")
        } else ""
        // The echo message must be QUOTED: an unquoted `(` (from "(page N)")
        // is a shell metacharacter (subshell start) and makes /system/bin/sh
        // fail the whole banner line with `syntax error: unexpected '('`.
        return bootstrap + "echo \"=== AT V3 $tag SESSION (page ${page.pageId}) ===\"; id; tty\n"
    }

    private fun showNewSessionChooser() {
        val opts = arrayOf("ADB", "Local")
        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setItems(opts) { _, which ->
                val kind = if (which == 0) SessionBackendKind.ADB else SessionBackendKind.LOCAL
                addPage(kind)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPage(kind: SessionBackendKind) {
        createPage(kind)
        if (kind == SessionBackendKind.ADB) {
            openAdbSessionAsync()
        } else {
            openActiveSession()
        }
        renderPage()
    }

    /** ADB page: ensure READY (single AdbRuntime owner), then open the session. */
    private fun openAdbSessionAsync() {
        updateAdbStatus()
        if (AppRuntime.adbState() != AdbRuntimeState.READY) {
            stateLabel.text = "State: connecting ADB..."
            Thread {
                val diag = AppRuntime.connectAuto()
                mainHandler.post {
                    updateAdbStatus()
                    if (AppRuntime.adbState() == AdbRuntimeState.READY) {
                        openActiveSession()
                    } else {
                        stateLabel.text = "State: ADB not ready — ${diag.code} ${diag.message}"
                    }
                }
            }.start()
        } else {
            openActiveSession()
        }
    }

    private fun retryAdb() {
        stateLabel.text = "State: connecting ADB..."
        Thread {
            val diag = AppRuntime.connectAuto()
            mainHandler.post {
                updateAdbStatus()
                stateLabel.text = "ADB: ${AppRuntime.adbState().name} | ${diag.code}"
            }
        }.start()
    }

    private fun updateAdbStatus() {
        val s = AppRuntime.adbState()
        adbStateLabel.text = when (s) {
            AdbRuntimeState.DISCONNECTED -> "ADB: DISCONNECTED"
            AdbRuntimeState.CONNECTING -> "ADB: CONNECTING"
            AdbRuntimeState.READY -> "ADB: READY"
            AdbRuntimeState.CONNECT_FAILED -> "ADB: CONNECT_FAILED"
            AdbRuntimeState.ENDPOINT_NOT_FOUND -> "ADB: CONNECT_FAILED"
            AdbRuntimeState.USER_ACTION_REQUIRED -> "ADB: USER_ACTION_REQUIRED"
            AdbRuntimeState.TRANSPORT_LOST -> "ADB: TRANSPORT_LOST"
            AdbRuntimeState.RECOVERING -> "ADB: RECOVERING"
            AdbRuntimeState.RECOVERY_FAILED -> "ADB: RECOVERY_FAILED"
        }
        // Guidance only while not READY (Wireless Debugging is the user's own
        // device setting; we never open it).
        adbHint.visibility =
            if (s == AdbRuntimeState.READY) View.GONE else View.VISIBLE
    }

    /** Debug-only (tests): refresh the ADB status label from AdbRuntime. */
    fun updateAdbStatusForTest() = updateAdbStatus()

    private fun closeActivePage() {
        val removed = pages.closeActive()
        // AT.CLI-0.1A: a closed Local session stops receiving ADB lifecycle
        // status immediately (no observer/registry leak, no stale delivery).
        removed?.let { AppRuntime.unsubscribeAdbStatus(it.pageId) }
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
        val kindTag = if (page.kind == SessionBackendKind.ADB) "ADB" else "Local"
        pageLabel.text = "${pages.size} page(s)  #${page.pageId} $kindTag (${page.state.name})"
        stateLabel.text = "State: ${page.state.name}"
        outputText.text = page.outputBuffer.toString()
        updateDebugStatus()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** AT.DEBUG-0: show the loopback DebugBridge status (debug builds only). */
    private fun updateDebugStatus() {
        val bridge = debugBridge ?: return
        val label = debugStatusLabel ?: return
        label.visibility = View.VISIBLE
        label.text = "DebugBridge: ${bridge.status} (127.0.0.1:${bridge.portForUi()})"
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
        // AT.DEBUG-0: unregister all handles (session lifecycle ended) + stop the
        // loopback debug bridge (debug builds only).
        sessionRegistry?.let { reg ->
            reg.list().forEach { reg.unregister(it.sessionId) }
        }
        debugBridge?.stop()
        debugBridge = null
    }
}