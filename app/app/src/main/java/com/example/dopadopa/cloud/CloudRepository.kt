package com.example.dopadopa.cloud

import android.content.Context
import com.example.dopadopa.data.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

data class LiveCloudBinding(val uid: String, val revision: String, val goalRevision: String, val since: Long,
    val cancellationEpoch: Long = CloudRepository.epoch())

/** Network waits never occupy the collector executor. No tokens or content are logged. */
class CloudRepository(context: Context,
    private val tokenSource: (FirebaseUser) -> String = { user ->
        Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token ?: error("token unavailable")
    }) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val queue = app.getSharedPreferences("cloud_queue", Context.MODE_PRIVATE)
    private val dao = GuardDatabase.get(app).dao()
    private val auth = FirebaseAuth.getInstance()

    private fun authorized(uid: String, revision: String): Boolean =
        auth.currentUser?.uid == uid && prefs.getBoolean("cloud_consent", false) &&
            prefs.getString("consent_revision", "") == revision &&
            prefs.getString("cloud_owner", uid) == uid

    private fun liveAuthorized(binding: LiveCloudBinding): Boolean = binding.cancellationEpoch == cancellationEpoch.get() &&
        authorized(binding.uid, binding.revision) &&
        prefs.getBoolean("collection_consent", false) && prefs.getBoolean("automatic_consent", false) &&
        prefs.getBoolean("goal_confirmed", false) && prefs.getString("automatic_owner_uid", null) == binding.uid &&
        prefs.getString("goal_revision", null) == binding.goalRevision && prefs.getLong("automatic_since", -1) == binding.since

    fun liveGeneration(binding: LiveCloudBinding): Long =
        request("GET", "/v1/user/generation", null, binding.uid, binding.revision, live = binding).getLong("generation")

    fun analyzeLive(body: JSONObject, binding: LiveCloudBinding, generation: Long): JSONObject {
        require(liveAuthorized(binding))
        val id = body.getString("snapshot_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(body.getLong("generation") == generation)
        request("POST", "/v1/live/snapshots", body, binding.uid, binding.revision, generation, binding)
        return request("POST", "/v1/live/snapshots/$id/analyze", null, binding.uid, binding.revision, generation, binding)
    }

    fun liveBody(id: String, sessionId: String, goal: String, binding: LiveCloudBinding,
        generation: Long, sequence: Long, capturedAt: Long, events: List<InteractionEvent>): JSONObject {
        require(events.size in 1..100 && events.all {
            it.sessionId == sessionId && it.consentRevision == binding.revision && it.dataOrigin == "real" &&
                it.timestamp in binding.since..capturedAt
        })
        return JSONObject().put("snapshot_id", id).put("session_id", sessionId).put("user_goal", goal)
            .put("goal_revision", binding.goalRevision).put("consent_revision", binding.revision)
            .put("automatic_consent", true).put("collection_consent", true).put("cloud_consent", true)
            .put("consent_since", Instant.ofEpochMilli(binding.since).toString())
            .put("captured_at", Instant.ofEpochMilli(capturedAt).toString()).put("generation", generation)
            .put("context_sequence", sequence)
            .put("events", JSONArray().also { rows -> events.forEach { rows.put(eventJson(it)) } })
    }

    fun sync(): String {
        synchronized(PrivacyGate.lock) { LocalRetention.prune(dao, queue) }
        val user = auth.currentUser ?: return "ログインしてください。"
        val revision = prefs.getString("consent_revision", "").orEmpty()
        if (!authorized(user.uid, revision)) return "クラウド同意またはデータの所有者を確認してください。"
        synchronized(PrivacyGate.lock) {
            if (!authorized(user.uid, revision)) return "同意またはログイン状態が変わりました。"
            prefs.edit().putString("cloud_owner", user.uid).commit()
        }
        val goal = dao.latestGoal() ?: return "本人のゴールを確認してください。"
        val generation = try {
            request("GET", "/v1/user/generation", null, user.uid, revision).getLong("generation")
        } catch (_: Exception) { return "同期を開始できませんでした。同意・通信状態を確認してください。" }
        val consent = dao.consent(revision)
        var sent = 0; var pending = 0; var skipped = 0; var late = 0
        for (session in dao.closedSessions()) {
            if (sent >= 20 || !authorized(user.uid, revision)) break
            val key = user.uid + ":" + session.sessionId
            if (queue.getBoolean("sent:$key", false)) {
                dao.envelope(key)?.let { saved ->
                    val rows = JSONObject(saved.json).getJSONArray("events")
                    val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("event_id") }.toSet()
                    late += maxOf(0, dao.eventCount(session.sessionId) - dao.eventsByIds(session.sessionId, ids.toList()).size)
                }
                continue
            }
            val existing = dao.envelope(key)
            if (existing != null && existing.consentRevision != revision) { skipped++; continue }
            val frozenRows = existing?.let { JSONObject(it.json).getJSONArray("events") }
            val events = if (frozenRows == null) dao.timeline(session.sessionId, 501) else {
                val ids = (0 until frozenRows.length()).map { frozenRows.getJSONObject(it).getString("event_id") }
                if (ids.size !in 1..500) { skipped++; continue }
                dao.eventsByIds(session.sessionId, ids)
            }
            if (frozenRows != null && events.size != frozenRows.length()) { skipped++; continue }
            if (!SyncPolicy.permits(session, events, consent, revision, true)) { skipped++; continue }
            if (queue.getLong("retry:$key", 0) > System.currentTimeMillis()) { pending++; continue }
            val candidate = JSONObject().put("session_id", session.sessionId).put("user_goal", goal.description)
                .put("cloud_consent", true).put("consent_revision", revision)
                .put("events", JSONArray().also { rows -> events.forEach { rows.put(eventJson(it)) } })
            try {
                val payload = synchronized(PrivacyGate.lock) {
                    require(authorized(user.uid, revision))
                    require((existing?.json ?: candidate.toString()).toByteArray(Charsets.UTF_8).size <= 300_000)
                    dao.freezeEnvelope(SyncEnvelope(key, session.sessionId, revision, candidate.toString()))
                    val frozen = dao.envelope(key) ?: error("session expired before sync")
                    require(frozen.consentRevision == revision)
                    JSONObject(frozen.json)
                }
                request("POST", "/v1/sessions:batch", payload, user.uid, revision, generation)
                queue.edit().putBoolean("sent:$key", true).remove("retry:$key").remove("attempt:$key").commit()
                sent++
            } catch (_: Exception) {
                val attempt = minOf(queue.getInt("attempt:$key", 0) + 1, 10)
                queue.edit().putInt("attempt:$key", attempt)
                    .putLong("retry:$key", System.currentTimeMillis() + minOf(3_600_000L, 30_000L * (1L shl attempt))).commit()
                pending++
                break // A failed connection does not block collection or loop indefinitely.
            }
        }
        return "送信済み $sent · 再試行待ち $pending · 同意/件数/未確定のため対象外 $skipped · 同期後の追加観測（端末のみ）$late"
    }

    fun deleteCloud(): String {
        val uid = auth.currentUser?.uid ?: error("authentication required")
        request("DELETE", "/v1/user/data", null, uid, null)
        queue.edit().clear().commit()
        dao.deleteEnvelopes()
        dao.deleteFeedback(); dao.deleteAnalyses()
        return "このログインアカウントのクラウドデータを削除しました。"
    }

    fun sessions(): JSONArray {
        val uid = auth.currentUser?.uid ?: error("authentication required")
        return JSONArray(requestText("GET", "/v1/sessions", null, uid, null))
    }

    fun timeline(sessionId: String): JSONObject {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val uid = auth.currentUser?.uid ?: error("authentication required")
        return request("GET", "/v1/sessions/$sessionId", null, uid, null)
    }

    fun analyze(sessionId: String): JSONObject {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val uid = auth.currentUser?.uid ?: error("authentication required")
        val revision = prefs.getString("consent_revision", "").orEmpty()
        require(authorized(uid, revision))
        val record = request("POST", "/v1/analyses", JSONObject().put("session_id", sessionId), uid, revision)
        synchronized(PrivacyGate.lock) {
            require(authorized(uid, revision))
            require(record.getString("session_id") == sessionId)
            dao.saveAnalysis(BehaviorAnalysis(record.getString("analysis_id"), sessionId,
                record.getString("status"), record.toString(), System.currentTimeMillis(), uid))
        }
        return record
    }

    fun feedback(analysisId: String): JSONArray {
        require(analysisId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val uid = auth.currentUser?.uid ?: error("authentication required")
        return JSONArray(requestText("GET", "/v1/analyses/$analysisId/feedback", null, uid, null))
    }

    fun saveFeedback(analysisId: String, verdict: String, correction: String?): JSONObject {
        require(analysisId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(verdict in setOf("correct", "incorrect", "unknown"))
        require(correction == null || (verdict == "incorrect" && correction.length <= 500))
        val uid = auth.currentUser?.uid ?: error("authentication required")
        val revision = prefs.getString("consent_revision", "").orEmpty()
        require(authorized(uid, revision))
        return request("POST", "/v1/analyses/$analysisId/feedback", JSONObject()
            .put("verdict", verdict).put("corrected_intent", correction ?: JSONObject.NULL), uid, revision)
    }

    private fun request(method: String, path: String, body: JSONObject?, uid: String, revision: String?, generation: Long? = null,
        live: LiveCloudBinding? = null): JSONObject =
        JSONObject(requestText(method, path, body, uid, revision, generation, live))

    private fun requestText(method: String, path: String, body: JSONObject?, uid: String, revision: String?, generation: Long? = null,
        live: LiveCloudBinding? = null): String {
        val epoch = cancellationEpoch.get()
        val user = auth.currentUser ?: error("authentication required")
        require(user.uid == uid)
        require(live == null || liveAuthorized(live))
        val token = tokenSource(user)
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= 300_000)
        val connection = URL(BASE + path).openConnection() as HttpsURLConnection
        synchronized(PrivacyGate.lock) {
            require(epoch == cancellationEpoch.get())
            require(auth.currentUser?.uid == uid && (revision == null || authorized(uid, revision)))
            require(live == null || liveAuthorized(live))
            active.add(connection)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            // Cloud Run may use its full 60-second request budget, including
            // remote Engine startup. Allow its response to arrive before giving up.
            connection.readTimeout = if (path == "/v1/analyses" || path.endsWith("/analyze")) 65_000 else 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            if (generation != null) {
                require(generation >= 0)
                connection.setRequestProperty("X-DopaGuard-Generation", generation.toString())
            }
            if (bytes != null) {
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                require(epoch == cancellationEpoch.get() && (revision == null || authorized(uid, revision)))
                require(live == null || liveAuthorized(live))
                connection.outputStream.use { it.write(bytes) }
            }
            require(connection.responseCode in 200..299) { "cloud request failed" }
            val content = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 300_000)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(content.size <= 300_000 && epoch == cancellationEpoch.get() && auth.currentUser?.uid == uid)
            require(live == null || liveAuthorized(live))
            return content.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
            synchronized(PrivacyGate.lock) { active.remove(connection) }
        }
    }

    private fun eventJson(event: InteractionEvent): JSONObject = JSONObject()
        .put("event_id", event.eventId).put("session_id", event.sessionId).put("app_id", event.appId)
        .put("package_name", event.packageName).put("timestamp", Instant.ofEpochMilli(event.timestamp).toString())
        .put("event_type", event.eventType).put("text", event.text ?: JSONObject.NULL)
        .put("text_status", event.textStatus).put("source", event.source).put("observability", event.observability)
        .put("data_origin", event.dataOrigin).put("consent_revision", event.consentRevision)

    companion object {
        const val BASE = "https://dopaguard-api-463106527119.asia-northeast1.run.app"
        val io = Executors.newSingleThreadExecutor()
        private val active = mutableSetOf<HttpsURLConnection>()
        private val cancellationEpoch = AtomicLong()
        fun epoch(): Long = cancellationEpoch.get()
        fun cancel() {
            cancellationEpoch.incrementAndGet()
            val connections = synchronized(PrivacyGate.lock) { active.toList() }
            connections.forEach { it.disconnect() }
        }
    }
}
