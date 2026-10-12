package com.example.dopadopa

import android.app.UiAutomation
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.intervention.SuggestionCard
import org.junit.Assert.*
import org.junit.Test

/** Clearly marked sample preview. No consent, pause, goal, or cloud data is changed. */
class SuggestionCardTest {
    @Test fun twoChoicesAndInlineReason() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var chosen: String? = null
        val reason = "学習を続けたいという目標に対し、数学の解説から別の話題のタイトルへ画面が変わりました。気分転換として開いた可能性もあり、実際の視聴や目的の変化は確認できません。"
        lateinit var card: LinearLayout
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val preview = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(22, 26, 37)) }
                preview.addView(TextView(activity).apply {
                    text = "デザインプレビュー · サンプル"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (48 * density).toInt() })
                card = SuggestionCard.create(activity,
                    "学習とは違う話題のタイトルが表示されています。気分転換かもしれませんが、一度ひと息入れますか？", reason) { chosen = it }
                preview.addView(card, FrameLayout.LayoutParams((360 * density).toInt(), -2).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = (48 * density).toInt()
                })
                activity.setContentView(preview)
                assertNull(chosen)
            }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            save(ui, "suggestion-collapsed.png")
            scenario.onActivity {
                val views = descendants(card)
                val buttons = views.filterIsInstance<Button>()
                assertEquals(listOf("続ける", "休む"), buttons.map { it.text.toString() })
                val explanation = views.filterIsInstance<TextView>().first { it.text.toString() == reason }
                assertEquals(View.GONE, explanation.visibility)
                val accordion = views.first { it.contentDescription?.toString() == "理由を見る" }
                accordion.performClick()
                assertEquals(View.VISIBLE, explanation.visibility)
                assertNull(chosen)
                assertTrue(buttons.all { it.visibility == View.VISIBLE })
            }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            save(ui, "suggestion-expanded.png")
            scenario.onActivity {
                descendants(card).first { it.contentDescription?.toString() == "理由を閉じる" }.performClick()
                assertNull(chosen)
                descendants(card).filterIsInstance<Button>().first { it.text == "続ける" }.performClick()
                assertEquals("continue", chosen)
                descendants(card).filterIsInstance<Button>().first { it.text == "休む" }.performClick()
                assertEquals("pause", chosen)
            }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun save(ui: UiAutomation, filename: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(ui.takeScreenshot())
        java.io.File(context.cacheDir, filename).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
