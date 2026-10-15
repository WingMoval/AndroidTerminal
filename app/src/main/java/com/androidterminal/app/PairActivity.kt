// AndroidTerminal V3 — ADB pairing code input window (AT.HUMAN-ADB-0).
//
// A NORMAL Activity (NOT a floating overlay / NOT freeform / NOT split-screen).
// The user reads the 6-digit pairing code from the SYSTEM "pair device with
// pairing code" dialog, then enters it HERE. On submit, the app drives the
// already-verified pairing primitive:
//
//   AppRuntime.initialize -> AdbRuntime.discoverPairing -> AdbEngine.pair
//
// This is a one-time developer/user provisioning input channel, NOT First-run
// product onboarding (that is AT.FIRSTRUN-0). After pairing, the app's own
// identity is trusted and connectAuto reaches READY without re-pairing.
//
// No overlay / accessibility / magic-window / freeform code lives here.

package com.androidterminal.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.adb.engine.AdbEngine
import com.adb.engine.AdbRuntime
import com.adb.engine.AdbRuntimeState

class PairActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pairButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRuntime.initialize(applicationContext)

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

        root.addView(title)
        root.addView(hintText)
        root.addView(codeInput)
        root.addView(statusText)
        root.addView(pairButton)
        setContentView(root)

        pairButton.setOnClickListener { doPair() }
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
            val result = try {
                // 1. Discover the pairing service (system dialog must be open).
                val endpoints = AdbRuntime.discoverPairing(10_000)
                if (endpoints.isEmpty()) {
                    "FAIL: 未发现配对服务 — 请保持系统「使用配对码配对设备」弹窗前台，再重试"
                } else {
                    val ep = endpoints.first()
                    val host = ep.host ?: "127.0.0.1"
                    // 2. Pair with the app's own identity (same keyDir as connectAuto).
                    val r = AdbEngine.pair(host, ep.port, code, AppRuntime.adbIdentityDir())
                    if (r.success) {
                        // 3. Auto-connect with the now-trusted identity.
                        val diag = AppRuntime.connectAuto()
                        if (AppRuntime.adbState() == AdbRuntimeState.READY) {
                            "配对成功 — ADB READY (guid=${r.deviceGuid})"
                        } else {
                            "配对成功但连接未 READY: ${diag.code} ${diag.message}"
                        }
                    } else {
                        "配对失败: ${r.error}"
                    }
                }
            } catch (t: Throwable) {
                "异常: ${t.message}"
            }
            mainHandler.post {
                statusText.text = result
                pairButton.isEnabled = true
                if (result.startsWith("配对成功")) {
                    finish()
                }
            }
        }.start()
    }
}
