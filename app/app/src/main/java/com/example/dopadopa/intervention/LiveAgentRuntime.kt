package com.example.dopadopa.intervention

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.example.dopadopa.cloud.CloudRepository
import com.example.dopadopa.cloud.LiveCloudBinding
import com.example.dopadopa.data.*
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

/** UI-thread scheduling; Room reads use collector IO, all network waits use separate IO. */
class LiveAgentRuntime(
    context: Context, private val handler: Handler, private val repository: TimelineRepository,
    private val refresh: () -> Unit
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val cloud = CloudRepository(app)
    private val dao = GuardDatabase.get(app).dao()
    private val schedule = MinuteAgentSchedule()
    private val policy = LiveAnalysisPolicy()
    private var activeId: String? = null
    private var currentKey: String? = null
    @Volatile private var sequence = 1L
    private var destroyed = false
    private var lastStatus = "まだ分析していません"
    private var analysisLog = ""
    private var logLoaded = false
    var detail: String = "次の分析を待っています"
        private set

    fun onClick() { sequence++ }

    fun tick(eligible: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val revision = prefs.getString("consent_revision", "").orEmpty()
        val goalRevision = prefs.getString("goal_revision", "").orEmpty()
        val since = prefs.getLong("automatic_since", 0)
        val key = if (eligible && uid != null && revision.isNotBlank() && goalRevision.isNotBlank() && since > 0)
            "$uid:$revision:$goalRevision:$since" else null
        val oldEpoch = schedule.epoch
        val now = SystemClock.elapsedRealtime()
        schedule.deferUntil(policy.nextAllowedStartElapsedMs())
        val due = schedule.tick(key, activeId != null, now)
        currentKey = key
        if (schedule.epoch != oldEpoch && activeId != null) CloudRepository.cancel()
        detail = if (activeId != null) "直近の観測とゴールを分析しています。\n$lastStatus"
            else "次の分析まで ${schedule.remainingSeconds(now)} 秒\n$lastStatus"
        val logText = analysisLog()
        if (logText.isNotEmpty()) detail += "\n$logText"
        if (!due || destroyed || uid == null) return
        val id = "live-" + UUID.randomUUID()
        activeId = id
        val epoch = schedule.epoch
        val started = schedule.startedAt ?: now
        val binding = LiveCloudBinding(uid, revision, goalRevision, since)
        // A real request starts here, including authentication and generation lookup.
        if (!AgentRunState.live.begin(id)) { activeId = null; return }
        prefs.edit().putLong("live_last_started_at", System.currentTimeMillis())
            .putString("live_last_status", "running")
            .putInt("live_total_starts", prefs.getInt("live_total_starts", 0) + 1).apply()
        detail = "直近の観測とゴールを分析しています。\n$lastStatus"
        refresh()
        network.execute {
            val generation = runCatching { cloud.liveGeneration(binding) }
            if (generation.isFailure) { handler.post { finish(id, "通信に失敗しました。次の周期で再試行します", "failed", "device_transport") }; return@execute }
            val capturedSequence = sequence
            PrivacyGate.io.execute {
                val prepared = runCatching {
                    synchronized(PrivacyGate.lock) {
                        repository.pollUsage()
                        val wall = System.currentTimeMillis()
                        val session = repository.liveSession(wall) ?: error("no observed session")
                        val events = dao.liveEvents(session.sessionId, since, revision)
                            .filter { it.timestamp <= wall }.sortedWith(compareBy({ it.timestamp }, { it.eventId }))
                        require(events.isNotEmpty() && events.all { it.dataOrigin == "real" })
                        Triple(session, events, wall)
                    }
                }
                handler.post {
                    if (!current(id, key, epoch)) { finish(id, lastStatus, "cancelled", "cancelled"); return@post }
                    if (sequence != capturedSequence) { finish(id, "観測が更新されたため次の周期で分析します", "discarded", "discarded"); return@post }
                    if (prepared.isFailure) { finish(id, "観測がまだありません。次の周期で確認します", "no_observations", "no_observations"); return@post }
                    val (session, events, wall) = prepared.getOrThrow()
                    val snapshot = LiveSnapshot(id, LiveContext(uid, session.sessionId, revision, goalRevision,
                        generation.getOrThrow(), epoch), capturedSequence, started, SystemClock.elapsedRealtime(),
                        events.map { it.eventId }.toSet(), events.filter { it.eventType == "CLICK" && it.textStatus == "available" }
                            .map { it.eventId }.toSet(), true)
                    if (!policy.reserve(snapshot, snapshot.context, sequence, ready, SystemClock.elapsedRealtime())) {
                        finish(id, "次の分析周期を待っています", "skipped", "skipped"); return@post
                    }
                    val body = cloud.liveBody(id, session.sessionId, prefs.getString("goal", "").orEmpty(), binding,
                        generation.getOrThrow(), snapshot.clickSequence, wall, events)
                    prefs.edit().putInt("live_last_event_count", events.size).putString("live_last_origin", "real").apply()
                    network.execute {
                        val response = runCatching { cloud.analyzeLive(body, binding, generation.getOrThrow()) }
                        PrivacyGate.io.execute {
                        val latestSession = runCatching {
                            synchronized(PrivacyGate.lock) {
                                repository.pollUsage()
                                repository.liveSession(System.currentTimeMillis())?.sessionId
                            }
                        }.getOrNull()
                        handler.post received@{
                            refresh() // Re-read foreground, lock and sensitive-UI flags before accepting a result.
                            if (!current(id, key, epoch) || snapshot.clickSequence != sequence ||
                                latestSession != snapshot.context.sessionId ||
                                SystemClock.elapsedRealtime() - snapshot.capturedElapsedMs > LiveAnalysisPolicy.RESULT_MAX_AGE_MS) {
                                policy.finishFailure(id); finish(id, "古い分析結果を破棄しました", "discarded", "discarded"); return@received
                            }
                            if (response.isFailure) {
                                policy.finishFailure(id); finish(id, "通信に失敗しました。次の周期で再試行します", "failed", "device_transport"); return@received
                            }
                            val value = response.getOrThrow()
                            if (value.optString("status") == "failed") {
                                val stage = AnalysisDiagnostics.serverStage(value.optString("diagnostic_stage"))
                                policy.finishFailure(id)
                                finish(id, "分析できませんでした（$stage）。次の周期で再試行します", "failed", stage)
                                return@received
                            }
                            val validated = runCatching { validate(value, snapshot) }
                            if (validated.isFailure) {
                                policy.finishFailure(id); finish(id, "分析できませんでした（result_validation）。次の周期で再試行します", "failed", "result_validation"); return@received
                            }
                            val result = validated.getOrThrow()
                            // No automatic restriction. Suggestion UI is connected separately in DG-304.
                            policy.finishAndAllowSuggestion(result, snapshot.context, sequence, ready, SystemClock.elapsedRealtime())
                            val alignment = value.getJSONObject("result").getString("goal_alignment")
                            val names = mutableListOf<String>()
                            val trace = value.getJSONArray("trace")
                            for (i in 0 until trace.length()) names.add(trace.getJSONObject(i).getString("tool"))
                            val text = when (alignment) {
                                "aligned" -> "前回: ゴールと整合している可能性"
                                "possibly_diverged" -> "前回: ゴールから離れた可能性（AIの仮説）"
                                else -> "前回: 観測からは判断できません"
                            }
                            prefs.edit().putString("live_last_alignment", alignment)
                                .putInt("live_last_tool_count", trace.length()).apply()
                            finish(id, text, value.getString("status"), "unknown", alignment, AnalysisDiagnostics.tools(names))
                        }
                        }
                    }
                }
            }
        }
    }

    private fun current(id: String, key: String?, epoch: Long): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val actualKey = "$uid:${prefs.getString("consent_revision", "")}:${prefs.getString("goal_revision", "")}:${prefs.getLong("automatic_since", 0)}"
        return !destroyed && activeId == id && key != null && currentKey == key && actualKey == key &&
            schedule.epoch == epoch && prefs.getBoolean("automatic_consent", false) &&
            prefs.getBoolean("collection_consent", false) && prefs.getBoolean("cloud_consent", false)
    }

    private fun validate(value: JSONObject, snapshot: LiveSnapshot): LiveAnalysisResult {
        require(value.getString("status") in setOf("completed", "insufficient_data"))
        require(value.getString("snapshot_id") == snapshot.snapshotId && value.getString("session_id") == snapshot.context.sessionId)
        require(value.getString("goal_revision") == snapshot.context.goalRevision &&
            value.getString("consent_revision") == snapshot.context.consentRevision &&
            value.getLong("generation") == snapshot.context.deletionGeneration &&
            value.getLong("context_sequence") == snapshot.clickSequence && value.getString("data_origin") == "real")
        require(value.getBoolean("evidence_validated"))
        val result = value.getJSONObject("result")
        require(result.getString("session_id") == snapshot.context.sessionId && result.getString("data_origin") == "real" &&
            result.getString("analysis_id") == value.getString("analysis_id") && result.getBoolean("user_confirmation_required"))
        val trace = value.getJSONArray("trace")
        require(trace.length() in 3..12)
        require(setOf("get_user_goals", "get_timeline", "get_click_events").all { tool ->
            (0 until trace.length()).any { trace.getJSONObject(it).getString("tool") == tool }
        })
        val evidence = mutableSetOf<String>()
        for (name in listOf("segments", "transitions")) {
            val rows = result.getJSONArray(name)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                for (field in if (name == "segments") listOf("from_event_id", "to_event_id") else listOf("at_event_id"))
                    require(row.getString(field) in snapshot.eventIds)
                val ids = row.getJSONArray("evidence_ids")
                for (j in 0 until ids.length()) evidence.add(ids.getString(j))
            }
        }
        require(snapshot.eventIds.containsAll(evidence))
        require(result.getString("goal_alignment") in setOf("unknown", "aligned", "possibly_diverged"))
        if (snapshot.availableClickEventIds.isEmpty()) require(result.getString("goal_alignment") == "unknown" && evidence.isEmpty() &&
            result.getJSONArray("segments").length() == 0 && result.getJSONArray("transitions").length() == 0)
        return LiveAnalysisResult(snapshot.snapshotId, snapshot.context, snapshot.clickSequence, true,
            result.getString("goal_alignment") == "possibly_diverged", true, evidence)
    }

    private fun finish(id: String, status: String, code: String, stage: String = "unknown",
                       alignment: String = "unknown", tools: String = "none") {
        if (activeId != id) return
        policy.finishFailure(id)
        activeId = null; AgentRunState.live.finish(id)
        lastStatus = status
        remember(AnalysisDiagnostics.line(clockNow(), code, stage, alignment, tools))
        prefs.edit().putString("live_last_status", code).putLong("live_last_finished_at", System.currentTimeMillis())
            .putInt("live_total_finished", prefs.getInt("live_total_finished", 0) + 1).apply()
        if (!destroyed) refresh()
    }

    private fun clockNow(): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(System.currentTimeMillis()))

    private fun analysisLog(): String {
        if (!logLoaded) {
            analysisLog = prefs.getString("live_analysis_log", "").orEmpty().lines().filter(AnalysisDiagnostics::validLine).takeLast(8).joinToString("\n")
            logLoaded = true
        }
        return analysisLog
    }

    private fun remember(line: String) {
        if (!AnalysisDiagnostics.validLine(line)) return
        Log.i("DopaGuardAnalysis", line)
        analysisLog = (analysisLog().lines().filter(AnalysisDiagnostics::validLine) + line).takeLast(8).joinToString("\n")
        prefs.edit().putString("live_analysis_log", analysisLog).apply()
    }

    fun destroy() {
        destroyed = true
        if (activeId != null) CloudRepository.cancel()
        // The request owns judging until its transport returns; no overlapping replacement run.
    }

    companion object {
        private val network = Executors.newSingleThreadExecutor()
        private val ready = LiveEligibility(true, true, true, true, true, true, true, true)
    }
}
