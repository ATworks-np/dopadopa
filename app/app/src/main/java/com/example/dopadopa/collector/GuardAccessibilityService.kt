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
import com.example.dopadopa.intervention.InterventionOverlay
import com.example.dopadopa.intervention.AnalysisInterval
import com.example.dopadopa.intervention.SuggestionInterval
import com.example.dopadopa.intervention.VoluntaryPause
import com.google.firebase.auth.FirebaseAuth

class GuardAccessibilityService : AccessibilityService() {
    private val repository by lazy { TimelineRepository(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollAt = 0L
    private val screenText = ScreenTextCache()
    private var lastScreenRead = 0L
    private var lastContentTitle: String? = null
    private var titleReadPending = false
    private val titleRead = Runnable {
        titleReadPending = false
        if (prefs.getBoolean("collection_consent", false)) captureScreen()
    }
    private fun captureScreen() {
        val now = SystemClock.elapsedRealtime()
        val root = ForegroundWindow.youtubeRoot(this)
        val labels = YouTubeAdapter.screen(root, { status ->
            prefs.edit().putString("collector_screen_status", status).apply()
        }, { count -> prefs.edit().putInt("collector_protected_nodes", count).apply() })
        screenText.replace(now, labels)
        lastScreenRead = now
        prefs.edit().putInt("collector_visible_labels", labels.size).apply()
        // A named title node identifies opened content, not a user click or a subtitle.
        val title = YouTubeAdapter.contentTitle(root, labels)
        if (title != null && title.text != lastContentTitle) {
            lastContentTitle = title.text
            val at = System.currentTimeMillis()
            val revision = prefs.getString("consent_revision", "consent-v1").orEmpty()
            val observed = title.copy(source = "parent_context", observability = "inferred")
            enqueue {
                repository.pollUsage(at)
                repository.interaction(at, "WINDOW_CHANGE", observed, revision)
            }
        }
    }
    private val prefs by lazy { getSharedPreferences("guard", MODE_PRIVATE) }
    private val pause = VoluntaryPause()
    private var ignoreYouTubeClicksUntil = 0L
    private var releaseHomeFailure = false
    private var pipDismissLeft = 0
    private var pipRevealUsed = false
    private val dismissPip: Runnable = Runnable {
        if (!prefs.getBoolean("pause_active", false)) return@Runnable
        val result = PipDismiss.close(this, reveal = !pipRevealUsed)
        if (result.revealed) pipRevealUsed = true
        if (result.closed) return@Runnable
        if (--pipDismissLeft > 0) handler.postDelayed(dismissPip, 350)
    }
    private val finishLeave: Runnable = Runnable {
        val moved = performGlobalAction(GLOBAL_ACTION_HOME)
        if (moved) {
            handler.removeCallbacks(dismissPip)
            pipDismissLeft = 8
            pipRevealUsed = false
            handler.postDelayed(dismissPip, 200)
        } else if (releaseHomeFailure) releasePause("home_failed")
        refreshAgentStatus()
    }
    private val leaveForRest: Runnable = Runnable {
        if (YouTubePause.clickPause(ForegroundWindow.youtubeRoot(this))) handler.postDelayed(finishLeave, 180)
        else finishLeave.run()
    }
    private val pausePlaybackAgain: Runnable = Runnable { YouTubePause.clickPause(ForegroundWindow.youtubeRoot(this)) }
    private fun pausePlayback() {
        ignoreYouTubeClicksUntil = SystemClock.elapsedRealtime() + 1_500
        val root = ForegroundWindow.youtubeRoot(this)
        if (!YouTubePause.clickPause(root)) {
            YouTubePause.revealControls(root)
            handler.removeCallbacks(pausePlaybackAgain)
            handler.postDelayed(pausePlaybackAgain, 400)
        }
    }
    private fun scheduleLeave(releaseIfHomeFails: Boolean) {
        releaseHomeFailure = releaseIfHomeFails
        ignoreYouTubeClicksUntil = SystemClock.elapsedRealtime() + 3_000
        val root = ForegroundWindow.youtubeRoot(this)
        val paused = YouTubePause.clickPause(root)
        if (!paused) YouTubePause.revealControls(root)
        handler.removeCallbacks(leaveForRest)
        handler.removeCallbacks(finishLeave)
        handler.postDelayed(leaveForRest, if (paused) 160 else 420)
    }
    private val intervention by lazy { InterventionOverlay(this) }
    private data class Proposal(val comment: String, val explanation: String, val valid: () -> Boolean)
    private var proposal: Proposal? = null
    private var safeForeground = false
    private fun pauseBinding(): String = "${FirebaseAuth.getInstance().currentUser?.uid}:" +
        "${prefs.getString("consent_revision", "")}:${prefs.getString("goal_revision", "")}:" +
        prefs.getLong("automatic_since", 0)
    private val liveRuntime by lazy { LiveAgentRuntime(applicationContext, handler, repository,
        { refreshAgentStatus() }, { comment, explanation, valid ->
            val now = System.currentTimeMillis()
            val last = prefs.getLong("suggestion_last_wall", 0)
            // A backwards wall-clock change suppresses for only one new cooldown window.
            if (last > now) prefs.edit().putLong("suggestion_last_wall", now).apply()
            if (safeForeground && pause.remaining(SystemClock.elapsedRealtime()) == 0L && valid() &&
                (last == 0L || now >= last && now - last >= SuggestionInterval.millis(prefs.getInt(SuggestionInterval.KEY, SuggestionInterval.DEFAULT_MINUTES)))) {
                prefs.edit().putLong("suggestion_last_wall", now).apply()
                proposal = Proposal(comment, explanation, valid)
                refreshAgentStatus()
            }
        }) }
    private val statusOverlay by lazy { AgentStatusOverlay(this, {
        synchronized(PrivacyGate.lock) {
            prefs.edit().putBoolean("automatic_consent", false).commit()
            CloudRepository.cancel()
        }
    }, { liveRuntime.requestNow() }) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { refreshAgentStatus() }
    }
    private val statusPoll = object : Runnable {
        override fun run() { refreshAgentStatus(); handler.postDelayed(this, 1_000) }
    }

    private fun refreshAgentStatus() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (prefs.getString("intervention_history_owner", null) != uid) {
            prefs.edit().remove("intervention_history").putString("intervention_history_owner", uid).apply()
        }
        if (prefs.getBoolean("pause_active", false) && prefs.getString("pause_binding", null) != pauseBinding())
            releasePause("context_changed")
        val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(PowerManager::class.java).isInteractive
        val ready = uid != null && prefs.getBoolean("goal_confirmed", false) &&
            prefs.getBoolean("collection_consent", false) && prefs.getBoolean("cloud_consent", false) &&
            prefs.getBoolean("automatic_consent", false) && prefs.getString("automatic_owner_uid", null) == uid &&
            prefs.getString("cloud_owner", uid) == uid && unlocked && repository.hasUsagePermission()
        if (!ready) {
            screenText.clear()
            safeForeground = false; proposal = null; releasePause("revoked")
            liveRuntime.tick(false); statusOverlay.hide(); intervention.hide(); return
        }
        val safeYoutube = runCatching {
            val root = ForegroundWindow.youtubeRoot(this) ?: return@runCatching false
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
        safeForeground = safeYoutube
        if (!safeYoutube) screenText.clear()
        val remaining = pause.remaining(SystemClock.elapsedRealtime())
        if (remaining == 0L && prefs.getBoolean("pause_active", false)) releasePause("expired")
        liveRuntime.tick(safeYoutube && remaining == 0L)
        if (!safeYoutube) { proposal = null; statusOverlay.hide(); intervention.hide(); return }
        if (remaining > 0) {
            statusOverlay.hide()
            intervention.pause((remaining + 999) / 1000) { scheduleLeave(false) }
            return
        }
        val pending = proposal
        if (pending != null && pending.valid()) {
            statusOverlay.hide()
            if (!intervention.suggestion(pending.comment, pending.explanation, ::choose)) proposal = null
        } else {
            proposal = null; intervention.hide()
            val history = prefs.getString("intervention_history", "").orEmpty()
            statusOverlay.show(AgentRunState.live.judging, liveRuntime.detail +
                if (history.isBlank()) "" else "\n\n本人の選択・休止終了履歴（端末内）\n$history",
                AnalysisInterval.minutes(prefs.getInt(AnalysisInterval.KEY, AnalysisInterval.DEFAULT_MINUTES)))
        }
    }

    private fun choose(choice: String) {
        val pending = proposal ?: return
        // Re-read foreground, consent and authentication at button click time.
        refreshAgentStatus()
        if (!safeForeground || proposal !== pending || !pending.valid()) return
        proposal = null; intervention.hide()
        recordChoice(choice)
        when (choice) {
            "pause" -> {
                pause.approve(SystemClock.elapsedRealtime())
                prefs.edit().putBoolean("pause_active", true).putLong("pause_approved_at", System.currentTimeMillis())
                    .putString("pause_binding", pauseBinding())
                    .putLong("pause_until", System.currentTimeMillis() + VoluntaryPause.DURATION).apply()
                liveRuntime.tick(false)
                pausePlayback()
            }
            "stop" -> { prefs.edit().putBoolean("automatic_consent", false).apply(); CloudRepository.cancel() }
        }
        refreshAgentStatus()
    }

    private fun recordChoice(choice: String) {
        // Local fixed vocabulary only; no click text, goal, model prose or invented feedback.
        val row = "${System.currentTimeMillis()} $choice"
        val previous = prefs.getString("intervention_history", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit().putString("intervention_history", (previous + row).takeLast(50).joinToString("\n"))
            .putString("intervention_history_owner", FirebaseAuth.getInstance().currentUser?.uid).apply()
    }

    private fun releasePause(reason: String) {
        handler.removeCallbacks(leaveForRest)
        handler.removeCallbacks(finishLeave)
        handler.removeCallbacks(dismissPip)
        handler.removeCallbacks(pausePlaybackAgain)
        pause.release()
        if (prefs.getBoolean("pause_active", false)) {
            prefs.edit().putBoolean("pause_active", false).remove("pause_until").remove("pause_binding").apply()
            recordChoice(reason)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            enqueue { repository.pollUsage() }
            handler.postDelayed(this, 5_000)
        }
    }
    override fun onServiceConnected() {
        // Reconnection/process restart never resurrects a previous restriction.
        releasePause("service_reconnected")
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
        if (event == null) return
        if (event.packageName?.toString() != YouTubeAdapter.PACKAGE) {
            screenText.clear(); lastContentTitle = null
            handler.removeCallbacks(titleRead); titleReadPending = false
            return
        }
        val preferences = getSharedPreferences("guard", MODE_PRIVATE)
        if (!preferences.getBoolean("collection_consent", false)) {
            screenText.clear(); lastContentTitle = null
            handler.removeCallbacks(titleRead); titleReadPending = false
            return
        }
        // Clicks made to pause playback or close picture-in-picture are not the person's actions.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && SystemClock.elapsedRealtime() < ignoreYouTubeClicksUntil) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (SystemClock.elapsedRealtime() - lastScreenRead >= 250) captureScreen()
            if (!titleReadPending) {
                titleReadPending = true
                handler.postDelayed(titleRead, 80)
            }
            return
        }
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
        val source = event.source
        val direct = if (event.isPassword) TextObservation.redacted()
            else if (type == "CLICK") YouTubeAdapter.observe(source) else TextObservation.missing()
        val observed = if (type == "CLICK" && direct.status == "missing" && source != null)
            runCatching { screenText.lookup(YouTubeAdapter.target(source), SystemClock.elapsedRealtime()) }
                .getOrDefault(TextObservation.missing()) else direct
        if (type == "CLICK") {
            preferences.edit().putBoolean("collector_click_source_present", source != null)
                .putString("collector_click_text_status", observed.status).apply()
        }
        // Capture after resolving the click against the previous screen.
        captureScreen()
        val revision = preferences.getString("consent_revision", "consent-v1").orEmpty()
        enqueue {
            repository.pollUsage(now)
            repository.interaction(now, type, observed, revision)
        }
    }
    override fun onInterrupt() { proposal = null; releasePause("service_interrupted"); liveRuntime.tick(false); statusOverlay.hide(); intervention.hide() }
    override fun onDestroy() {
        screenText.clear()
        handler.removeCallbacks(titleRead)
        handler.removeCallbacks(leaveForRest); handler.removeCallbacks(finishLeave)
        handler.removeCallbacks(dismissPip); handler.removeCallbacks(pausePlaybackAgain)
        handler.removeCallbacks(poll); handler.removeCallbacks(statusPoll)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        liveRuntime.destroy()
        proposal = null; releasePause("service_stopped"); intervention.hide()
        statusOverlay.hide()
        super.onDestroy()
    }
}
