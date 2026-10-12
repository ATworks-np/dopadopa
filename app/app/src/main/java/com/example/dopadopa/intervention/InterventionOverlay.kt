package com.example.dopadopa.intervention

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

/** Actions are invoked only by explicit button clicks, never by display/update. */
class InterventionOverlay(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: ScrollView? = null
    private var title: TextView? = null
    private var mode: String? = null
    fun suggestion(comment: String, explanation: String, choose: (String) -> Unit): Boolean {
        if (mode == "suggestion") return true
        return show("suggestion", "AIからの提案（仮説）\n" + comment.filter { !it.isISOControl() || it == '\n' }.take(300), listOf(
            "10分休む" to { choose("pause") }, "このまま続ける" to { choose("continue") },
            "目的に沿っている" to { choose("correct") }, "判断できない" to { choose("unknown") },
            "自動提案を停止" to { choose("stop") }, "閉じる" to { choose("dismiss") }
        ), explanation)
    }
    fun pause(seconds: Long, release: () -> Unit, home: () -> Unit) {
        val text = "本人が選んだ休止中 · 残り ${seconds / 60}分${seconds % 60}秒"
        if (mode == "pause") { title?.text = text; return }
        show("pause", text, listOf("今すぐ解除" to release, "ホームへ戻る" to home))
    }
    private fun show(kind: String, text: String, actions: List<Pair<String, () -> Unit>>, evidence: String? = null): Boolean {
        hide()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(25, 30, 42))
        }
        val heading = TextView(service).apply { this.text = text; textSize = 18f; setTextColor(Color.WHITE) }
        panel.addView(heading)
        if (evidence != null) {
            val details = TextView(service).apply {
                this.text = evidence.filter { !it.isISOControl() || it == '\n' }.take(1200)
                textSize = 16f; setPadding(0, 16, 0, 16); setTextColor(Color.WHITE); visibility = android.view.View.GONE
            }
            panel.addView(Button(service).apply { this.text = "理由を見る"; setOnClickListener {
                details.visibility = if (details.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                this.text = if (details.visibility == android.view.View.VISIBLE) "理由を閉じる" else "理由を見る"
            } })
            panel.addView(details)
        }
        actions.forEach { (label, action) -> panel.addView(Button(service).apply { this.text = label; setOnClickListener { action() } }) }
        val scroll = ScrollView(service).apply { addView(panel) }
        val density = service.resources.displayMetrics.density
        val params = WindowManager.LayoutParams((300 * density).toInt(),
            minOf((540 * density).toInt(), service.resources.displayMetrics.heightPixels * 3 / 4),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        if (runCatching { manager.addView(scroll, params) }.isFailure) return false
        root = scroll; title = heading; mode = kind
        return true
    }
    fun hide() { root?.let { runCatching { manager.removeViewImmediate(it) } }; root = null; title = null; mode = null }
}
