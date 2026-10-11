package com.example.dopadopa.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.example.dopadopa.collector.*
import java.util.UUID

/** All methods run on PrivacyGate.io. Accessibility snapshots contain no live nodes. */
class TimelineRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val dao = GuardDatabase.get(context).dao()
    private val tracker = SessionTracker(YouTubeAdapter.PACKAGE) { "s-" + UUID.randomUUID() }
    private var revision = ""
    private var cursor = 0L
    private var lastScroll = 0L

    fun hasUsagePermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun prepare(): Boolean {
        if (!prefs.getBoolean("collection_consent", false)) { tracker.reset(); return false }
        val current = prefs.getString("consent_revision", "consent-v1").orEmpty()
        if (current != revision) {
            tracker.reset(); revision = current
            cursor = maxOf(prefs.getLong("collection_since", System.currentTimeMillis()), prefs.getLong("last_usage_at", 0))
        }
        return true
    }

    fun pollUsage(now: Long = System.currentTimeMillis()) = synchronized(PrivacyGate.lock) {
        LocalRetention.prune(dao, context.getSharedPreferences("cloud_queue", Context.MODE_PRIVATE), now)
        if (!prepare() || !hasUsagePermission()) return@synchronized
        if (now <= cursor) return@synchronized
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(cursor, now)
        val entry = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(entry)
            if (entry.timeStamp < cursor || entry.timeStamp > now) continue
            val changes = when (entry.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> tracker.foreground(entry.packageName.orEmpty(), entry.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> if (entry.packageName == YouTubeAdapter.PACKAGE) tracker.background(entry.timeStamp) else emptyList()
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> tracker.background(entry.timeStamp)
                else -> emptyList()
            }
            changes.forEach { change ->
                if (change.type == "APP_FOREGROUND") dao.insertSession(Session(change.sessionId, "youtube", YouTubeAdapter.PACKAGE, change.timestamp))
                if (change.type == "APP_BACKGROUND") dao.endSession(change.sessionId, change.timestamp)
                save(change.sessionId, change.timestamp, change.type, TextObservation.missing())
            }
        }
        cursor = now
        prefs.edit().putLong("last_usage_at", now).apply()
    }

    fun interaction(at: Long, type: String, text: TextObservation, expectedRevision: String) = synchronized(PrivacyGate.lock) {
        if (!prepare() || expectedRevision != revision || at < prefs.getLong("collection_since", Long.MAX_VALUE)) return@synchronized
        if (type == "SCROLL" && at - lastScroll in 0..499) return@synchronized
        if (type == "SCROLL") lastScroll = at
        val session = if (at < cursor) dao.sessionAt("youtube", at)?.sessionId ?: tracker.observedInteraction(at)
            else tracker.observedInteraction(at)
        if (session == null) return@synchronized
        dao.insertSession(Session(session, "youtube", YouTubeAdapter.PACKAGE, at))
        save(session, at, type, text)
    }

    private fun save(session: String, at: Long, type: String, observed: TextObservation) {
        val event = InteractionEvent("e-" + UUID.randomUUID(), session, "youtube", YouTubeAdapter.PACKAGE,
            at, type, observed.text, observed.status, observed.source, observed.observability, "real", revision)
        event.validate()
        dao.insertEvent(event)
    }
}
