package com.example.dopadopa.collector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.dopadopa.data.PrivacyGate
import com.example.dopadopa.data.TimelineRepository
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.SharedPreferences
import com.example.dopadopa.cloud.CloudRepository
import com.example.dopadopa.intervention.AgentRunState
import com.example.dopadopa.intervention.AgentStatusOverlay
import com.example.dopadopa.intervention.LiveAgentRuntime
import com.google.firebase.auth.FirebaseAuth

class GuardAccessibilityService : AccessibilityService() {
    private val repository by lazy { TimelineRepository(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollAt = 0L
    private val prefs by lazy { getSharedPreferences("guard", MODE_PRIVATE) }
    private val liveRuntime by lazy { LiveAgentRuntime(applicationContext, handler, repository) { refreshAgentStatus() } }
    private val statusOverlay by lazy { AgentStatusOverlay(this) {
        synchronized(PrivacyGate.lock) {
            prefs.edit().putBoolean("automatic_consent", false).commit()
            CloudRepository.cancel()
        }
    } }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { refreshAgentStatus() }
    }
    private val statusPoll = object : Runnable {
        override fun run() { refreshAgentStatus(); handler.postDelayed(this, 1_000) }
    }

    private fun refreshAgentStatus() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(PowerManager::class.java).isInteractive
        val ready = uid != null && prefs.getBoolean("goal_confirmed", false) &&
            prefs.getBoolean("collection_consent", false) && prefs.getBoolean("cloud_consent", false) &&
            prefs.getBoolean("automatic_consent", false) && prefs.getString("automatic_owner_uid", null) == uid &&
            prefs.getString("cloud_owner", uid) == uid && unlocked && repository.hasUsagePermission()
        if (!ready) { liveRuntime.tick(false); statusOverlay.hide(); return }
        val safeYoutube = runCatching {
            val root = rootInActiveWindow ?: return@runCatching false
            if (root.packageName?.toString() != YouTubeAdapter.PACKAGE) return@runCatching false
            // Inspect flags only, never text. Fail closed for large/unknown input trees.
            val nodes = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            nodes.add(root)
            var count = 0
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (++count > 500 || node.isPassword || node.isEditable) return@runCatching false
                for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.add(it) }
            }
            true
        }.getOrDefault(false)
        liveRuntime.tick(safeYoutube)
        if (safeYoutube) statusOverlay.show(AgentRunState.live.judging, liveRuntime.detail) else statusOverlay.hide()
    }
    private val poll = object : Runnable {
        override fun run() {
            enqueue { repository.pollUsage() }
            handler.postDelayed(this, 5_000)
        }
    }
    override fun onServiceConnected() {
        handler.removeCallbacks(poll); handler.post(poll)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        handler.removeCallbacks(statusPoll); handler.post(statusPoll)
    }
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
        if (type == "CLICK") liveRuntime.onClick()
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
    override fun onInterrupt() { liveRuntime.tick(false); statusOverlay.hide() }
    override fun onDestroy() {
        handler.removeCallbacks(poll); handler.removeCallbacks(statusPoll)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        liveRuntime.destroy()
        statusOverlay.hide()
        super.onDestroy()
    }
}
