package com.example.dopadopa.intervention

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.example.dopadopa.MainActivity

/** Small touch target only; the rest of YouTube remains interactive. UI thread only. */
class AgentStatusOverlay(private val service: AccessibilityService, private val stop: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var details: LinearLayout? = null
    private var statusText: TextView? = null
    private var judging = false
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun show(isJudging: Boolean, status: String) {
        judging = isJudging
        if (root == null) {
            val panel = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(25, 30, 42)); cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.rgb(99, 112, 139))
                }
                elevation = dp(8).toFloat()
            }
            val badge = TextView(service).apply {
                textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                minWidth = dp(160)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(40)
                setOnClickListener { details?.visibility = if (details?.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
            }
            panel.addView(badge)
            val extra = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
            }
            val explanation = TextView(service).apply {
                setTextColor(Color.WHITE); textSize = 12f; maxWidth = dp(240)
            }
            val scroll = ScrollView(service).apply {
                addView(explanation)
                layoutParams = LinearLayout.LayoutParams(dp(260), dp(320))
            }
            extra.addView(scroll)
            extra.addView(Button(service).apply {
                text = "設定を開く"
                setOnClickListener {
                    service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    hide()
                }
            })
            extra.addView(Button(service).apply { text = "AgentをOFFにする"; setOnClickListener { stop(); hide() } })
            panel.addView(extra)
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(96)
            }
            if (runCatching { manager.addView(panel, params) }.isFailure) return
            root = panel; label = badge; details = extra; statusText = explanation
        }
        statusText?.text = "YouTube利用中に1分ごとに分析します。\n$status"
        label?.apply {
            text = if (judging) "◉ Agent · 判定中" else "● Agent · 待機中"
            contentDescription = text
            setTextColor(if (judging) Color.rgb(207, 184, 255) else Color.rgb(128, 230, 191))
        }
    }

    fun hide() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null; label = null; details = null; statusText = null
    }
}
