package com.example.dopadopa.intervention

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.example.dopadopa.cloud.CloudRepository
import com.example.dopadopa.cloud.CloudHttpFailure
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
    private val refresh: () -> Unit,
    private val suggest: (String, String, () -> Boolean) -> Unit = { _, _, _ -> }
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
    private var lastEligible = false
    private var runImmediately = false
    private val explanations = ArrayDeque<String>()
    private var pendingExplanation: String? = null
    var detail: String = "次の分析を待っています"
        private set

    fun onClick() { sequence++ }

    fun requestNow() {
        if (destroyed) return
        val wait = policy.nextAllowedStartElapsedMs() - SystemClock.elapsedRealtime()
        if (activeId != null || wait > 0) {
            lastStatus = if (activeId != null) "分析を実行中です" else "再分析できるまであと ${(wait + 999) / 1000} 秒です"
            refresh()
            return
        }
        runImmediately = true
        refresh()
    }

    fun tick(eligible: Boolean) {
        lastEligible = eligible
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val revision = prefs.getString("consent_revision", "").orEmpty()
        val goalRevision = prefs.getString("goal_revision", "").orEmpty()
        val since = prefs.getLong("automatic_since", 0)
        val key = if (eligible && uid != null && revision.isNotBlank() && goalRevision.isNotBlank() && since > 0)
            "$uid:$revision:$goalRevision:$since" else null
        val oldEpoch = schedule.epoch
        val now = SystemClock.elapsedRealtime()
        val interval = AnalysisInterval.millis(prefs.getInt(AnalysisInterval.KEY, AnalysisInterval.DEFAULT_MINUTES))
        schedule.deferUntil(policy.nextAllowedStartElapsedMs(interval))
        val due = schedule.tick(key, activeId != null, now, interval)
        val immediate = runImmediately
        runImmediately = false
        currentKey = key
        if (schedule.epoch != oldEpoch) {
            explanations.clear()
            pendingExplanation = null
            lastStatus = "この利用文脈ではまだ分析していません"
        }
        if (schedule.epoch != oldEpoch && activeId != null) CloudRepository.cancel()
        val blockedNow = immediate && (key == null || activeId != null)
        if (blockedNow) lastStatus = if (activeId != null) "分析を実行中です" else "今すぐ分析するには、YouTubeを開いたまま同意とログインを確認してください"
        detail = if (activeId != null) "直近の観測とゴールを分析しています。\n$lastStatus"
            else "次の分析まで ${schedule.remainingSeconds(now)} 秒\n$lastStatus"
        detail += "\n分析の間隔: ${AnalysisInterval.minutes(prefs.getInt(AnalysisInterval.KEY, AnalysisInterval.DEFAULT_MINUTES))}分"
        detail += "\n提案通知の間隔: ${SuggestionInterval.minutes(prefs.getInt(SuggestionInterval.KEY, SuggestionInterval.DEFAULT_MINUTES))}分"
        val logText = analysisLog()
        if (logText.isNotEmpty()) detail += "\n$logText"
        if (key != null && explanations.isNotEmpty())
            detail += "\n\n各回の分析説明（この利用文脈の直近8回）\n" + explanations.reversed().joinToString("\n\n")
        if (blockedNow || (!due && !immediate) || destroyed || uid == null) return
        val id = "live-" + UUID.randomUUID()
        activeId = id
        val epoch = schedule.epoch
        val started = schedule.startedAt ?: now
        val binding = LiveCloudBinding(uid, revision, goalRevision, since)
        val immediateRequest = immediate
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
                    if (prepared.isFailure) { finish(id, "観測がまだありません。次の周期で確認します", "no_observations", "no_observations"); return@post }
                    val (session, events, wall) = prepared.getOrThrow()
                    val snapshot = LiveSnapshot(id, LiveContext(uid, session.sessionId, revision, goalRevision,
                        generation.getOrThrow(), epoch), capturedSequence, started, SystemClock.elapsedRealtime(),
                        events.map { it.eventId }.toSet(), events.filter { it.eventType in setOf("CLICK", "WINDOW_CHANGE") && it.textStatus == "available" }
                            .map { it.eventId }.toSet(), true)
                    if (!policy.reserve(snapshot, snapshot.context, sequence, ready, SystemClock.elapsedRealtime(), interval, immediateRequest)) {
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
                            if (!current(id, key, epoch) || latestSession != snapshot.context.sessionId) {
                                policy.finishFailure(id); finish(id, "利用終了または設定変更により分析を停止しました", "cancelled", "cancelled"); return@received
                            }
                            if (response.isFailure) {
                                if ((response.exceptionOrNull() as? CloudHttpFailure)?.statusCode == 429) {
                                    policy.finishFailure(id)
                                    finish(id, "分析の実行間隔または回数の上限です。少し待ってから再実行してください", "skipped", "rate_limited")
                                    return@received
                                }
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
                            val allowSuggestion = policy.finishAndAllowSuggestion(result, snapshot.context, sequence, ready, SystemClock.elapsedRealtime(),
                                prefs.getInt(SuggestionInterval.KEY, SuggestionInterval.DEFAULT_MINUTES))
                            val alignment = value.getJSONObject("result").getString("goal_alignment")
                            val names = mutableListOf<String>()
                            val trace = value.getJSONArray("trace")
                            for (i in 0 until trace.length()) names.add(trace.getJSONObject(i).getString("tool"))
                            val model = value.getJSONObject("result")
                            val text = if (model.optString("usage_pattern", "unknown") == "possible_chaining")
                                "前回: コンテンツを次々に開く連鎖の可能性（AIの仮説）" else when (alignment) {
                                "aligned" -> "前回: ゴールと整合している可能性"
                                "possibly_diverged" -> "前回: ゴールから離れた可能性（AIの仮説）"
                                else -> "前回: 観測からは判断できません"
                            }
                            prefs.edit().putString("live_last_alignment", alignment)
                                .putInt("live_last_tool_count", trace.length()).apply()
                            val toolDetails = (0 until trace.length()).map { i ->
                                val row = trace.getJSONObject(i)
                                AnalysisExplanation.Tool(row.getString("tool"), row.getInt("count"), row.getLong("elapsed_ms"))
                            }
                            val claims = mutableListOf<AnalysisExplanation.Claim>()
                            if (model.optString("usage_pattern", "unknown") == "possible_chaining") {
                                val ids = model.getJSONArray("usage_pattern_evidence_ids")
                                claims.add(AnalysisExplanation.Claim("コンテンツを次々に開く連鎖の可能性。意図的な調べ物の可能性もあります",
                                    (0 until ids.length()).map { ids.getString(it) }))
                            }
                            for (section in listOf("segments", "transitions")) {
                                val rows = model.getJSONArray(section)
                                for (i in 0 until rows.length()) {
                                    val row = rows.getJSONObject(i)
                                    val ids = row.getJSONArray("evidence_ids")
                                    claims.add(AnalysisExplanation.Claim(
                                        if (section == "segments") row.getString("topic") + ": " + row.getString("intent_hypothesis")
                                        else row.getString("hypothesis"),
                                        (0 until ids.length()).map { ids.getString(it) }))
                                }
                            }
                            pendingExplanation = AnalysisExplanation.render(body.getString("user_goal"), alignment, toolDetails, claims,
                                events.map { event -> AnalysisExplanation.Observation(event.eventId,
                                    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                                        .format(Instant.ofEpochMilli(event.timestamp)), event.eventType, event.text, event.textStatus) },
                                model.getString("uncertainty"))
                            val explanation = model.optString("suggestion_reason", "").takeUnless { model.isNull("suggestion_reason") }.orEmpty()
                            finish(id, text, value.getString("status"), "unknown", alignment, AnalysisDiagnostics.tools(names))
                            val comment = model.optString("suggestion_comment", "").takeUnless { model.isNull("suggestion_comment") }.orEmpty()
                            if (allowSuggestion && comment.isNotBlank() && explanation.isNotBlank()) suggest(comment, explanation) {
                                currentKey == key && schedule.epoch == epoch && !destroyed
                            }
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
        val tools = (0 until trace.length()).map { trace.getJSONObject(it).getString("tool") }.toSet()
        require(tools.containsAll(setOf("get_user_goals", "get_timeline")) &&
            tools.any { it in setOf("get_content_events", "get_click_events") })
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
        val usagePattern = result.optString("usage_pattern", "unknown")
        require(usagePattern in setOf("possible_chaining", "no_clear_chaining", "unknown"))
        val patternRows = result.optJSONArray("usage_pattern_evidence_ids")
        val patternEvidence = if (patternRows == null) emptySet() else (0 until patternRows.length()).map { patternRows.getString(it) }.toSet()
        if (usagePattern == "possible_chaining") {
            require(patternEvidence.size in 3..10 && snapshot.availableContentEventIds.containsAll(patternEvidence))
            evidence.addAll(patternEvidence)
        } else require(patternEvidence.isEmpty())
        require(result.getString("goal_alignment") in setOf("unknown", "aligned", "possibly_diverged"))
        if (result.has("suggestion_reason") && !result.isNull("suggestion_reason")) {
            require(result.getString("suggestion_reason").isNotBlank() && result.getString("suggestion_reason").length <= 1200 &&
                result.has("suggestion_comment") && !result.isNull("suggestion_comment"))
        }
        if (result.has("suggestion_comment") && !result.isNull("suggestion_comment")) {
            val comment = result.getString("suggestion_comment")
            require(comment.isNotBlank() && comment.length <= 300 &&
                (result.getString("goal_alignment") == "possibly_diverged" || usagePattern == "possible_chaining"))
            val ids = result.getJSONArray("suggestion_evidence_ids")
            val commentEvidence = (0 until ids.length()).map { ids.getString(it) }.toSet()
            require(ids.length() in 1..10 && snapshot.eventIds.containsAll(commentEvidence) &&
                commentEvidence.any { it in snapshot.availableContentEventIds })
            if (usagePattern == "possible_chaining") require(commentEvidence.containsAll(patternEvidence))
        }
        if (snapshot.availableContentEventIds.isEmpty()) require(result.getString("goal_alignment") == "unknown" && evidence.isEmpty() &&
            result.getJSONArray("segments").length() == 0 && result.getJSONArray("transitions").length() == 0)
        return LiveAnalysisResult(snapshot.snapshotId, snapshot.context, snapshot.clickSequence, true,
            result.getString("goal_alignment") == "possibly_diverged", true, evidence, usagePattern == "possible_chaining")
    }

    private fun finish(id: String, status: String, code: String, stage: String = "unknown",
                       alignment: String = "unknown", tools: String = "none") {
        if (activeId != id) return
        policy.finishFailure(id)
        activeId = null; AgentRunState.live.finish(id)
        lastStatus = status
        if (currentKey != null) {
            explanations.addLast("${clockNow()} — $status\n" +
                (pendingExplanation ?: "この回の判断説明はありません。状態: $code / 段階: $stage\n失敗から判断理由を推測しません。"))
            while (explanations.size > 8) explanations.removeFirst()
        }
        pendingExplanation = null
        if (currentKey != null) {
            remember(AnalysisDiagnostics.line(clockNow(), code, stage, alignment, tools))
            prefs.edit().putString("live_last_status", code).putLong("live_last_finished_at", System.currentTimeMillis())
                .putInt("live_total_finished", prefs.getInt("live_total_finished", 0) + 1).apply()
        }
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
        explanations.clear()
        pendingExplanation = null
        if (activeId != null) CloudRepository.cancel()
        // The request owns judging until its transport returns; no overlapping replacement run.
    }

    companion object {
        private val network = Executors.newSingleThreadExecutor()
        private val ready = LiveEligibility(true, true, true, true, true, true, true, true)
    }
}
