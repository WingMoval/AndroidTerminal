// AndroidTerminal V3 — ADB pairing code input window (AT.HUMAN-ADB-0).
//
// A NORMAL Activity (NOT a floating overlay / NOT freeform / NOT split-screen).
// The user reads the 6-digit pairing code from the SYSTEM "pair device with
// pairing code" dialog, then enters it HERE. On submit, the app drives the
// REUSABLE ADB Consumer Bootstrap (AT.CONSUMER-BOOTSTRAP-0):
//
//   AppRuntime.initialize -> AppRuntime.consumerBootstrap()
//       -> AdbConsumerBootstrap.pairDiscovered(code)
//
// The bootstrap is the SINGLE pairing boundary shared with the CLI `adb pair`
// and the consumer host. PairActivity does NOT call AdbEngine.pair directly
// (PAIR_ACTIVITY_DIRECT_ADBENGINE_PAIR = NO). It only:
//   - shows the PAIRING_REQUIRED state,
//   - runs discovery (auto host/port — the user only enters the 6-digit code),
//   - handles the 0 / 1 / N endpoint cases,
//   - triggers pairing via the bootstrap,
//   - then connects and reports READY.
//
// No overlay / accessibility / magic-window / freeform code lives here.
// The pairing CODE is a short-lived secret: it is never logged, persisted, or
// written to long-term storage.

package com.androidterminal.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.adb.engine.AdbBootstrapState
import com.adb.engine.AdbConsumerBootstrap
import com.adb.engine.AdbPairingResultV2
import com.adb.engine.AdbRuntimeState

class PairActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pairButton: Button
    private lateinit var bootstrap: AdbConsumerBootstrap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRuntime.initialize(applicationContext)
        bootstrap = AppRuntime.consumerBootstrap()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "ADB 配对 — 配对码输入"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }

        val hintText = TextView(this).apply {
            text = "1. 手机 设置 → 无线调试 → 使用配对码配对设备（保持弹窗前台，记住 6 位码）\n" +
                "2. 在此输入配对码 → 点配对\n" +
                "（配对成功后 App 自动连接，重启无需再配对）"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        codeInput = EditText(this).apply {
            hint = "6 位配对码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 24f
            gravity = Gravity.CENTER
        }
        codeInput.maxEms = 6

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }

        pairButton = Button(this).apply {
            text = "配对"
        }

        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(title)
        root.addView(hintText)
        root.addView(codeInput)
        root.addView(statusText)
        root.addView(pairButton)
        setContentView(scroll)

        // Show the current consumer readiness projection (honest classification).
        refreshStateLabel()

        pairButton.setOnClickListener { doPair() }
    }

    /** Reflect the consumer bootstrap state in the status area (read-only). */
    private fun refreshStateLabel() {
        val state = bootstrap.bootstrapState()
        statusText.text = when (state) {
            AdbBootstrapState.READY -> "已连接（ADB READY）"
            AdbBootstrapState.PAIRING_REQUIRED -> "ADB 尚未完成首次配对"
            AdbBootstrapState.USER_ACTION_REQUIRED_WD -> "请先开启：设置 → 无线调试"
            AdbBootstrapState.ENDPOINT_NOT_FOUND -> "未发现 ADB 连接端点"
            AdbBootstrapState.CONNECT_FAILED -> "未连接（可尝试重新配对）"
            AdbBootstrapState.NOT_READY -> "ADB 运行时未初始化"
        }
    }

    private fun doPair() {
        val code = codeInput.text?.toString()?.trim() ?: ""
        if (code.length != 6) {
            statusText.text = "请输入 6 位配对码"
            return
        }
        pairButton.isEnabled = false
        statusText.text = "正在发现配对服务..."
        Thread {
            // Pair through the REUSABLE bootstrap (single pairing boundary).
            // pairDiscovered handles 0/1/N endpoints; the user only supplies
            // the 6-digit code. The bootstrap (not this Activity) calls
            // AdbEngine.pair.
            val result = runCatching { bootstrap.pairDiscovered(code) }.getOrElse { t ->
                AdbPairingResultV2(
                    success = false,
                    outcome = AdbPairingResultV2.Outcome.FAILED,
                    error = t.message ?: "pairing threw",
                )
            }
            val message = when (result.outcome) {
                AdbPairingResultV2.Outcome.SUCCESS -> {
                    // Pairing succeeded -> connect the now-trusted identity.
                    val diag = AppRuntime.connectAuto()
                    if (AppRuntime.adbState() == AdbRuntimeState.READY) {
                        "配对成功 — ADB READY (guid=${result.deviceGuid})"
                    } else {
                        "配对成功但连接未 READY: ${diag.code} ${diag.message}"
                    }
                }
                AdbPairingResultV2.Outcome.SERVICE_NOT_FOUND ->
                    "未发现无线调试配对服务\n\n请确认：\n系统「使用配对码配对设备」窗口仍处于打开状态\n\n[点配对 重新检测]"
                AdbPairingResultV2.Outcome.MULTIPLE_ENDPOINTS ->
                    "发现多个配对端点，请在高级模式下手动选择 host:port"
                AdbPairingResultV2.Outcome.REJECTED ->
                    "配对被拒绝（配对码错误或已过期）：${result.error.ifBlank { "无详情" }}"
                AdbPairingResultV2.Outcome.FAILED ->
                    "配对失败：${result.error.ifBlank { "未知错误" }}"
            }
            mainHandler.post {
                statusText.text = message
                pairButton.isEnabled = true
                if (message.startsWith("配对成功")) {
                    finish()
                }
            }
        }.start()
    }
}
