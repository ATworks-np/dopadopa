package com.example.dopadopa.intervention

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.example.dopadopa.MainActivity
import androidx.core.view.doOnLayout
import kotlin.math.abs

/** Keeps a floating overlay inside the screen. */
internal object OverlayDrag {
    fun clamp(x: Int, y: Int, viewWidth: Int, viewHeight: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }
}

/** Small touch target only; the rest of YouTube remains interactive. UI thread only. */
class AgentStatusOverlay(private val service: AccessibilityService, private val stop: () -> Unit, private val runNow: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var signal: OverlaySignal? = null
    private var badge: LinearLayout? = null
    private var brandText: TextView? = null
    private var expanded = false
    private var compactX = 0
    private var compactY = 0
    private var details: LinearLayout? = null
    private var statusText: TextView? = null
    private var runButton: Button? = null
    private var params: WindowManager.LayoutParams? = null
    private var judging = false
    private val prefs = service.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun screenWidth() = service.resources.displayMetrics.widthPixels
    private fun screenHeight() = service.resources.displayMetrics.heightPixels

    fun show(isJudging: Boolean, status: String, intervalMinutes: Int) {
        judging = isJudging
        if (root == null) {
            val panel = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
            }
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.argb(245, 19, 24, 36))
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.argb(110, 135, 158, 210))
                }
                clipToOutline = true
                setOnClickListener {
                    setExpanded(!expanded)
                }
            }
            val mark = OverlaySignal(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(mark, LinearLayout.LayoutParams(dp(96), dp(96)))
            val brand = TextView(service).apply {
                text = "DopaGuard"
                textSize = 12f
                setTextColor(Color.rgb(238, 242, 255))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(brand, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            brandText = brand
            panel.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            badge = box
            signal = mark
            val extra = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(25, 30, 42))
                    cornerRadius = dp(22).toFloat()
                }
            }
            val explanation = TextView(service).apply {
                setTextColor(Color.rgb(234, 238, 249))
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setLineSpacing(dp(4).toFloat(), 1f)
                includeFontPadding = false
                setPadding(0, dp(4), 0, dp(12))
            }
            val scroll = ScrollView(service).apply {
                addView(explanation)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    minOf(dp(320), (screenHeight() * 0.38f).toInt()))
            }
            extra.addView(scroll)
            val analyze = Button(service).apply {
                text = "今すぐ分析"
                setOnClickListener { runNow() }
            }
            extra.addView(analyze)
            runButton = analyze
            extra.addView(Button(service).apply {
                text = "設定を開く"
                setOnClickListener {
                    service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    hide()
                }
            })
            extra.addView(Button(service).apply { text = "AgentをOFFにする"; setOnClickListener { stop(); hide() } })
            panel.addView(extra)
            val placed = prefs.getBoolean("overlay_placed", false)
            val layout = WindowManager.LayoutParams(dp(112),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                if (placed) {
                    x = prefs.getInt("overlay_x", dp(12))
                    y = prefs.getInt("overlay_y", dp(96))
                }
            }
            if (runCatching { manager.addView(panel, layout) }.isFailure) return
            root = panel; details = extra; statusText = explanation; params = layout
            panel.post {
                if (!placed) layout.x = (screenWidth() - panel.width - dp(12)).coerceAtLeast(0)
                layout.y = if (placed) layout.y else dp(96)
                moveTo(layout.x, layout.y)
            }
            attachDrag(box, panel)
        }
        runButton?.isEnabled = !isJudging
        statusText?.text = "YouTube利用中に${AnalysisInterval.minutes(intervalMinutes)}分ごとに分析します。\n$status"
        signal?.judging = isJudging
        badge?.contentDescription = if (isJudging) "DopaGuard 分析中。タップで詳細" else "DopaGuard 待機。タップで詳細"
    }

    private fun setExpanded(open: Boolean) {
        if (expanded == open) return
        val panel = root ?: return
        val box = badge ?: return
        val layout = params ?: return
        if (open) {
            compactX = layout.x
            compactY = layout.y
        }
        expanded = open
        val targetWidth = if (open) minOf(dp(360), screenWidth() - dp(24)) else dp(112)
        val targetX = if (open) (compactX + dp(112) - targetWidth).coerceIn(0, (screenWidth() - targetWidth).coerceAtLeast(0)) else compactX
        val targetY = compactY
        box.orientation = if (open) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(if (open) 16 else 8), dp(if (open) 8 else 4), dp(if (open) 16 else 8), dp(if (open) 8 else 12))
        signal?.layoutParams = LinearLayout.LayoutParams(dp(if (open) 64 else 96), dp(if (open) 64 else 96))
        brandText?.apply {
            textSize = if (open) 18f else 12f
            gravity = if (open) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(if (open) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, if (open) 1f else 0f).apply {
                if (open) marginStart = dp(12)
            }
        }
        details?.visibility = if (open) View.VISIBLE else View.GONE
        layout.width = targetWidth
        layout.x = targetX
        layout.y = targetY
        panel.doOnLayout { moveTo(targetX, targetY) }
        runCatching { manager.updateViewLayout(panel, layout) }
    }

    private fun moveTo(x: Int, y: Int) {
        val layout = params ?: return
        val panel = root ?: return
        val next = OverlayDrag.clamp(x, y, panel.width, panel.height, screenWidth(), screenHeight())
        layout.x = next.first
        layout.y = next.second
        runCatching { manager.updateViewLayout(panel, layout) }
    }

    private fun attachDrag(handle: View, panel: LinearLayout) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        var moved = false
        val beginDrag = Runnable {
            dragging = true
            setExpanded(false)
            originX = params?.x ?: originX
            originY = params?.y ?: originY
            panel.alpha = 0.85f
        }
        handle.setOnTouchListener { view, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    moved = false
                    downX = event.rawX
                    downY = event.rawY
                    originX = layout.x
                    originY = layout.y
                    view.handler.postDelayed(beginDrag, timeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        view.handler.removeCallbacks(beginDrag)
                    }
                    if (dragging) moveTo(originX + dx.toInt(), originY + dy.toInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.handler.removeCallbacks(beginDrag)
                    if (dragging) {
                        dragging = false
                        panel.alpha = 1f
                        prefs.edit().putBoolean("overlay_placed", true)
                            .putInt("overlay_x", layout.x).putInt("overlay_y", layout.y).apply()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !moved) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    fun hide() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null; signal = null; badge = null; brandText = null; expanded = false
        details = null; statusText = null; runButton = null; params = null
    }
}
