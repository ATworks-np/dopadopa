package com.example.dopadopa.intervention

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Remaining time for the voluntary 10-minute pause. Display only. */
object PauseClock {
    fun text(seconds: Long): String {
        val value = seconds.coerceAtLeast(0)
        return "%d:%02d".format(value / 60, value % 60)
    }

    fun spoken(seconds: Long): String {
        val value = seconds.coerceAtLeast(0)
        return "休止中、残り${value / 60}分${value % 60}秒"
    }

    fun fraction(seconds: Long): Float {
        val total = VoluntaryPause.DURATION / 1000L
        return seconds.coerceIn(0, total).toFloat() / total
    }
}

/** Countdown ring. A full ring is the whole pause; it shrinks as time runs out. */
class PauseRing(context: Context) : View(context) {
    var fraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var trackColor: Int = 0
    var accentColor: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = resources.displayMetrics.density * 12f
        paint.strokeWidth = stroke
        val inset = stroke / 2f
        oval.set(inset, inset, width - inset, height - inset)
        paint.color = trackColor
        canvas.drawArc(oval, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = accentColor
            canvas.drawArc(oval, -90f, 360f * fraction, false, paint)
        }
    }
}

/** Full-screen rest surface. Time and the home button only. */
object PauseScreen {
    class Screen(val root: FrameLayout, val clock: TextView, val ring: PauseRing)

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun Context.barHeight(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(fallbackDp)
    }

    fun create(context: Context, seconds: Long, home: () -> Unit): Screen {
        val colors = OverlayColors.of(context)
        val clock = TextView(context).apply {
            textSize = 56f
            setTextColor(colors.ink)
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        val ring = PauseRing(context).apply {
            trackColor = colors.track
            accentColor = colors.accent
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(colors.canvas)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(28), context.barHeight("status_bar_height", 28) + context.dp(12),
                context.dp(28), context.barHeight("navigation_bar_height", 24) + context.dp(16))
        }
        column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        val face = FrameLayout(context)
        val size = context.dp(240)
        face.addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        face.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        column.addView(face, LinearLayout.LayoutParams(size, size))
        column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        column.addView(SuggestionCard.action(context, "ホームへ戻る", true, callback = home), LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        update(clock, ring, seconds)
        return Screen(root, clock, ring)
    }

    fun update(clock: TextView?, ring: PauseRing?, seconds: Long) {
        val safe = seconds.coerceAtLeast(0)
        clock?.text = PauseClock.text(safe)
        clock?.contentDescription = PauseClock.spoken(safe)
        ring?.fraction = PauseClock.fraction(safe)
    }
}
