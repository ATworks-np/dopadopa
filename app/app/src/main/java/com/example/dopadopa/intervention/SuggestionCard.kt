package com.example.dopadopa.intervention

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*

/** Presentation only. Expanding the explanation never makes a decision. */
object SuggestionCard {
    private fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun surface(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }
    private fun text(context: Context, value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(context.dp(3).toFloat(), 1f)
    }
    fun action(context: Context, label: String, primary: Boolean, callback: () -> Unit) = Button(context).apply {
        val colors = OverlayColors.of(context)
        text = label; textSize = 16f; isAllCaps = false; includeFontPadding = false
        stateListAnimator = null; elevation = 0f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) colors.onAccent else colors.ink)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        minWidth = 0; minimumWidth = 0; minHeight = context.dp(52); minimumHeight = context.dp(52)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(if (colors.night) 50 else 35, Color.red(colors.accent), Color.green(colors.accent), Color.blue(colors.accent))),
            surface(if (primary) colors.accent else colors.raised, context.dp(16).toFloat()), null)
        setOnClickListener { callback() }
    }
    fun create(context: Context, comment: String, explanation: String, choose: (String) -> Unit): LinearLayout {
        val colors = OverlayColors.of(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(20))
            background = surface(colors.surface, context.dp(28).toFloat())
            elevation = context.dp(12).toFloat()
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(context, "DopaGuard · 提案", 12f, colors.accent, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })
        body.addView(text(context, "ひと息、入れますか？", 23f, colors.ink, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) })
        body.addView(text(context, comment.filter { !it.isISOControl() || it == '\n' }.take(300), 15f, colors.ink), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(18) })
        val details = text(context, explanation.filter { !it.isISOControl() || it == '\n' }.take(1200), 14f, colors.muted).apply {
            visibility = View.GONE
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            background = surface(colors.well, context.dp(16).toFloat())
        }
        val accordion = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48); isFocusable = true
        }
        accordion.addView(text(context, "理由を見る", 14f, colors.muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = text(context, "⌄", 22f, colors.muted)
        accordion.addView(arrow)
        fun updateAccessibility() {
            accordion.contentDescription = if (details.visibility == View.VISIBLE) "理由を閉じる" else "理由を見る"
            if (Build.VERSION.SDK_INT >= 30) accordion.stateDescription = if (details.visibility == View.VISIBLE) "展開中" else "折りたたみ"
        }
        updateAccessibility()
        accordion.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            arrow.text = if (details.visibility == View.VISIBLE) "⌃" else "⌄"
            updateAccessibility()
        }
        body.addView(accordion)
        body.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4); bottomMargin = context.dp(12) })
        val scroll = CappedScrollView(context, minOf(context.dp(360), context.resources.displayMetrics.heightPixels / 2)).apply {
            isFillViewport = false; clipToPadding = false; addView(body)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        panel.addView(View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(12); bottomMargin = context.dp(18) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action(context, "続ける", false) { choose("continue") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = context.dp(10) })
        actions.addView(action(context, "休む", true) { choose("pause") }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(text(context, "休むと10分の休止です。", 11f, colors.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        return panel
    }
}

class CappedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxHeight
            else minOf(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(parentLimit, View.MeasureSpec.AT_MOST))
    }
}
