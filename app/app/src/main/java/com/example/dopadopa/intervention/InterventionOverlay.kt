package com.example.dopadopa.intervention

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Actions are invoked only by explicit button clicks, never by display/update. */
class InterventionOverlay(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var clock: TextView? = null
    private var ring: PauseRing? = null
    private var mode: String? = null
    private var night: Boolean? = null
    fun suggestion(comment: String, explanation: String, choose: (String) -> Unit): Boolean {
        val colors = OverlayColors.of(service)
        if (mode == "suggestion" && night == colors.night) return true
        hide()
        val panel = SuggestionCard.create(service, comment, explanation, choose)
        val density = service.resources.displayMetrics.density
        val width = minOf((360 * density).toInt(), service.resources.displayMetrics.widthPixels - (32 * density).toInt())
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (24 * density).toInt()
            }
        if (runCatching { manager.addView(panel, params) }.isFailure) return false
        root = panel; mode = "suggestion"; night = colors.night
        return true
    }
    fun pause(seconds: Long, home: () -> Unit) {
        val colors = OverlayColors.of(service)
        if (mode == "pause" && night == colors.night) {
            PauseScreen.update(clock, ring, seconds)
            return
        }
        hide()
        val screen = PauseScreen.create(service, seconds, home)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { manager.addView(screen.root, params) }.isFailure) return
        root = screen.root
        clock = screen.clock
        ring = screen.ring
        mode = "pause"
        night = colors.night
    }
    fun hide() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null
        clock = null
        ring = null
        mode = null
        night = null
    }
}
