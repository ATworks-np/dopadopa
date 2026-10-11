package com.example.dopadopa.collector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.dopadopa.data.PrivacyGate
import com.example.dopadopa.data.TimelineRepository

class GuardAccessibilityService : AccessibilityService() {
    private val repository by lazy { TimelineRepository(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollAt = 0L
    private val poll = object : Runnable {
        override fun run() {
            enqueue { repository.pollUsage() }
            handler.postDelayed(this, 5_000)
        }
    }
    override fun onServiceConnected() { handler.removeCallbacks(poll); handler.post(poll) }
    private fun enqueue(action: () -> Unit) {
        PrivacyGate.io.execute {
            runCatching { action() }.onFailure {
                getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("collector_failed", true).apply()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != YouTubeAdapter.PACKAGE) return
        val preferences = getSharedPreferences("guard", MODE_PRIVATE)
        if (!preferences.getBoolean("collection_consent", false)) return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_CHANGE"
            else -> return
        }
        val now = System.currentTimeMillis() - SystemClock.uptimeMillis() + event.eventTime
        if (type == "SCROLL" && now - lastScrollAt in 0..499) return
        if (type == "SCROLL") lastScrollAt = now
        val observed = if (event.isPassword) TextObservation.redacted()
            else if (type == "CLICK") YouTubeAdapter.observe(event.source) else TextObservation.missing()
        val revision = preferences.getString("consent_revision", "consent-v1").orEmpty()
        enqueue {
            repository.pollUsage(now)
            repository.interaction(now, type, observed, revision)
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { handler.removeCallbacks(poll); super.onDestroy() }
}
