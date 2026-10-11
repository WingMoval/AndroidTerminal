// AndroidTerminal V3 — Local Terminal Dev Preview.
//
// AT.DEVAPP-0 / AT.CONTRACT-0.5. Developer Preview / Consumer Validation slice.
//
// The App is a CONSUMER of the AT Engine public boundary. After composition
// (via AppTerminal), the UI only talks to:
//   ATEngine / ATTerminalSession / ATSessionEvent / ATSessionState
// It NEVER touches LocalPtyNative / LocalPtySession / JNI / fd / pid.
//
// This is a RAW terminal preview (no Terminal Emulator / Screen State).
// escape sequences are shown approximately; vim/top full-screen behavior is
// expected to be wrong and is NOT fixed here.

package com.androidterminal.app

import android.app.Activity
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

    private lateinit var terminal: AppTerminal

    // UI
    private lateinit var outputText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var inputBox: EditText
    private lateinit var rowsBox: EditText
    private lateinit var colsBox: EditText
    private lateinit var stateLabel: TextView
    private val outputBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Composition root: AppTerminal builds Local backend + AT Engine.
        terminal = AppTerminal(eventListener = { ev -> onSessionEvent(ev) })

        outputText = findViewById(R.id.tvOutput)
        scroll = findViewById(R.id.scrollOutput)
        inputBox = findViewById(R.id.etInput)
        rowsBox = findViewById(R.id.etRows)
        colsBox = findViewById(R.id.etCols)
        stateLabel = findViewById(R.id.tvState)
        rowsBox.setText(DEFAULT_ROWS.toString())
        colsBox.setText(DEFAULT_COLS.toString())

        findViewById<Button>(R.id.btnOpen).setOnClickListener { openSession() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendInput() }
        findViewById<Button>(R.id.btnCtrlC).setOnClickListener { sendCtrlC() }
        findViewById<Button>(R.id.btnResize).setOnClickListener { resize() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearOutput() }
        findViewById<Button>(R.id.btnClose).setOnClickListener { closeSession() }
    }

    private fun openSession() {
        val current = terminal.session
        if (current != null && current.state == ATSessionState.ACTIVE) {
            appendState("already ACTIVE")
            return
        }
        appendState("OPENING ...")
        val ok = terminal.open(DEFAULT_ROWS, DEFAULT_COLS)
        if (ok) {
            appendState("ACTIVE")
            terminal.session?.write(
                "echo === AT V3 LOCAL SESSION ===; id; tty\n".toByteArray(StandardCharsets.UTF_8),
            )
        } else {
            appendState("FAILED: open failed")
        }
    }

    private fun sendInput() {
        if (terminal.state() != ATSessionState.ACTIVE) return
        val text = inputBox.text?.toString() ?: ""
        if (text.isEmpty()) return
        // UTF-8 bytes + explicit newline (byte-stream semantics, AT.CONTRACT-0.5 §5).
        terminal.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
        inputBox.setText("")
    }

    private fun sendCtrlC() {
        if (terminal.state() != ATSessionState.ACTIVE) return
        terminal.ctrlC() // 0x03 -> SIGINT to foreground
        appendState("CTRL-C sent")
    }

    private fun resize() {
        val rows = rowsBox.text?.toString()?.toIntOrNull() ?: return
        val cols = colsBox.text?.toString()?.toIntOrNull() ?: return
        val ok = terminal.resize(rows, cols)
        appendState(if (ok) "RESIZED ${rows}x$cols" else "resize failed")
    }

    private fun clearOutput() {
        // UI-only clear; NOT a terminal clear sequence (§12).
        mainHandler.post {
            outputBuilder.clear()
            outputText.text = ""
        }
    }

    private fun closeSession() {
        terminal.close()
        appendState("CLOSED")
    }

    private fun onSessionEvent(ev: ATSessionEvent) {
        when (ev) {
            is ATSessionEvent.Data -> {
                val s = String(ev.bytes, StandardCharsets.UTF_8)
                mainHandler.post { appendOutput(s) }
            }
            is ATSessionEvent.Resized -> appendState("RESIZED ${ev.rows}x${ev.cols}")
            is ATSessionEvent.Exited -> appendState("EXITED ${ev.exitCode}")
            is ATSessionEvent.Eof -> appendState("EOF")
            is ATSessionEvent.Failed -> appendState("FAILED ${ev.error}")
            is ATSessionEvent.Closed -> appendState("CLOSED")
        }
    }

    private fun appendOutput(s: String) {
        outputBuilder.append(s)
        outputText.text = outputBuilder.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendState(s: String) {
        mainHandler.post {
            val state = terminal.state().name
            stateLabel.text = "State: $state   | $s"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dev preview policy: close session on Activity destroy to avoid orphan
        // child shells. Rotation retention / process recreation = DEFERRED.
        terminal.close()
    }
}