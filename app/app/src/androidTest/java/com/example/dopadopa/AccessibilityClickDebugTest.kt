package com.example.dopadopa

import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.collector.YouTubeAdapter
import com.example.dopadopa.cloud.CloudRepository
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly invoked local-only probe. Does not log labels, change DB, or call cloud APIs. */
class AccessibilityClickDebugTest {
    @Test fun inspectRealClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val expected = requireNotNull(args.getString("expected"))
        val x = requireNotNull(args.getString("tapX")).toFloat()
        val y = requireNotNull(args.getString("tapY")).toFloat()
        val prefs = instrumentation.targetContext.getSharedPreferences("guard", Context.MODE_PRIVATE)
        val automatic = prefs.getBoolean("automatic_consent", false)
        prefs.edit().putBoolean("automatic_consent", false).commit()
        CloudRepository.cancel()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = ui.serviceInfo
        val counts = ConcurrentHashMap<Int, AtomicInteger>()
        val clickedSource = AtomicInteger()
        val clickedMatches = AtomicInteger()
        val clickedAvailable = AtomicInteger()
        try {
            val info = ui.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            ui.serviceInfo = info
            ui.setOnAccessibilityEventListener { event ->
                if (event.packageName?.toString() == YouTubeAdapter.PACKAGE) {
                    counts.computeIfAbsent(event.eventType) { AtomicInteger() }.incrementAndGet()
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        val node = event.source
                        if (node != null) clickedSource.incrementAndGet()
                        val label = YouTubeAdapter.observe(node)
                        if (label.status == "available") clickedAvailable.incrementAndGet()
                        if (label.text?.contains(expected, ignoreCase = true) == true) clickedMatches.incrementAndGet()
                    }
                }
            }
            SystemClock.sleep(700)
            val before = inspect(ui.rootInActiveWindow, expected, "before")
            val time = SystemClock.uptimeMillis()
            for (action in if (args.getString("inspectOnly") == "true") emptyList() else listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val motion = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                motion.source = InputDevice.SOURCE_TOUCHSCREEN
                check(ui.injectInputEvent(motion, true))
                motion.recycle()
                SystemClock.sleep(80)
            }
            var maxMatches = 0
            repeat(12) { index ->
                val observed = inspect(ui.rootInActiveWindow, expected, "poll_$index")
                maxMatches = maxOf(maxMatches, observed["expected_label_matches"] ?: 0)
                SystemClock.sleep(200)
            }
            val after = inspect(ui.rootInActiveWindow, expected, "after")
            val result = Bundle()
            before.forEach { (key, value) -> result.putInt("before_$key", value) }
            after.forEach { (key, value) -> result.putInt("after_$key", value) }
            result.putInt("click_events", counts[AccessibilityEvent.TYPE_VIEW_CLICKED]?.get() ?: 0)
            result.putInt("click_sources", clickedSource.get())
            result.putInt("poll_max_expected_matches", maxMatches)
            result.putInt("click_available_labels", clickedAvailable.get())
            result.putInt("click_expected_matches", clickedMatches.get())
            result.putInt("content_change_events", counts[AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED]?.get() ?: 0)
            result.putInt("selected_events", counts[AccessibilityEvent.TYPE_VIEW_SELECTED]?.get() ?: 0)
            result.putInt("focused_events", counts[AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED]?.get() ?: 0)
            instrumentation.sendStatus(2, result)
        } finally {
            ui.setOnAccessibilityEventListener(null)
            ui.serviceInfo = original
            prefs.edit().putBoolean("automatic_consent", automatic).commit()
        }
    }

    private fun inspect(root: AccessibilityNodeInfo?, expected: String, stage: String): Map<String, Int> {
        if (root == null || root.packageName?.toString() != YouTubeAdapter.PACKAGE)
            return mapOf("youtube_root" to 0)
        val labels = YouTubeAdapter.screen(root)
        val matches = labels.values.count { it.text?.contains(expected, ignoreCase = true) == true }
        labels.entries.filter { it.value.text?.contains(expected, ignoreCase = true) == true }.forEachIndexed { index, entry ->
            val evidence = Bundle()
            evidence.putString("${stage}_match_${index}_node_id", entry.key.id)
            evidence.putString("${stage}_match_${index}_node_class", entry.key.kind)
            evidence.putString("${stage}_match_${index}_node_bounds", entry.key.bounds)
            evidence.putInt("${stage}_match_${index}_label_length", entry.value.text?.length ?: 0)
            evidence.putString("${stage}_match_${index}_label_source", entry.value.source)
            InstrumentationRegistry.getInstrumentation().sendStatus(3, evidence)
        }
        val found = root.findAccessibilityNodeInfosByText(expected).count {
            YouTubeAdapter.observe(it).text?.contains(expected, ignoreCase = true) == true
        }
        return mapOf("youtube_root" to 1, "available_labels" to labels.size,
            "expected_label_matches" to matches, "find_by_text_matches" to found)
    }
}
