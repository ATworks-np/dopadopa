package com.example.dopadopa.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.dopadopa.cloud.CloudRepository
import com.example.dopadopa.data.GuardDatabase
import org.json.JSONObject

@Composable fun CloudAnalysisScreen(context: Context, uid: String, cloudConsent: Boolean) {
    var sessions by remember(uid) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(uid) { mutableStateOf<JSONObject?>(null) }
    var record by remember(uid) { mutableStateOf<JSONObject?>(null) }
    var history by remember(uid) { mutableStateOf(org.json.JSONArray()) }
    var feedbackReady by remember(uid) { mutableStateOf(false) }
    var busy by remember(uid) { mutableStateOf(false) }
    var message by remember(uid) { mutableStateOf("同期済みのセッションを取得してください。") }
    val ui = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Dispose guards late responses after account switches/logout.
    val lifecycle = remember(uid) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(uid) { onDispose { lifecycle.set(false) } }
    fun execute(action: () -> Unit) {
        busy = true
        CloudRepository.io.execute { action() }
    }
    LaunchedEffect(record?.optString("analysis_id")) {
        history = org.json.JSONArray(); feedbackReady = false
        val current = record ?: return@LaunchedEffect
        if (current.optString("status") !in setOf("completed", "insufficient_data")) return@LaunchedEffect
        val aid = current.getString("analysis_id")
        CloudRepository.io.execute {
            val result = runCatching { CloudRepository(context).feedback(aid) }
            ui.post { if (lifecycle.get() && record?.optString("analysis_id") == aid) {
                result.onSuccess { history = it; feedbackReady = true }
                if (result.isFailure) message = "回答履歴を取得できませんでした。セッションを選び直してください。"
            } }
        }
    }
    Text("セッションを振り返る", style = MaterialTheme.typography.titleLarge)
    Button(enabled = !busy, onClick = {
        execute {
            val result = runCatching { CloudRepository(context).sessions() }
            ui.post { if (lifecycle.get()) {
                busy = false
                sessions = result.getOrNull()?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } } ?: emptyList()
                message = if (result.isFailure) "セッションを取得できませんでした。" else if (sessions.isEmpty()) "同期済みセッションはありません。" else "表示するセッションを選んでください。"
            } }
        }
    }) { Text("同期済みセッションを更新") }
    Text(message)
    sessions.forEachIndexed { index, session ->
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            execute {
                val sid = session.getString("session_id")
                val result = runCatching {
                    val timeline = CloudRepository(context).timeline(sid)
                    val cache = GuardDatabase.get(context).dao().latestAnalysis(sid, uid)?.json?.let { JSONObject(it) }
                    timeline to cache
                }
                ui.post { if (lifecycle.get()) {
                    busy = false; selected = result.getOrNull()?.first; record = result.getOrNull()?.second
                    if (result.isFailure) message = "セッションの観測根拠を取得できませんでした。"
                } }
            }
        }) { Text("記録 ${index + 1} · ${if (session.getString("data_origin") == "real") "実際の操作" else "サンプル"} · ${session.getInt("event_count")}件の操作") }
    }
    selected?.let { timeline ->
        Text("選択した記録", style = MaterialTheme.typography.titleMedium)
        Text(if (timeline.getJSONArray("events").getJSONObject(0).getString("data_origin") == "real") "端末から取得した実際の操作" else "動作説明用のサンプル")
        Button(enabled = cloudConsent && !busy, onClick = {
            record = null; message = "分析中です。別のアプリの利用を続けられます。"
            execute {
                val result = runCatching { CloudRepository(context).analyze(timeline.getString("session_id")) }
                ui.post { if (lifecycle.get()) {
                    busy = false; record = result.getOrNull()
                    message = if (result.isFailure) "分析を取得できませんでした。同意と通信状態を確認してください。" else "分析結果は仮説です。根拠と照合してください。"
                } }
            }
        }) { Text(if (busy) "処理中…" else "このセッションをGeminiで分析") }
        if (!cloudConsent) Text("新しい分析にはクラウド同意が必要です。")
        record?.let { analysis ->
            AnalysisView(analysis, timeline)
            if (feedbackReady) key(analysis.getString("analysis_id")) {
                FeedbackView(history, cloudConsent && !busy) { verdict, correction ->
                    execute {
                        val result = runCatching {
                            val cloud = CloudRepository(context)
                            cloud.saveFeedback(analysis.getString("analysis_id"), verdict, correction)
                            cloud.feedback(analysis.getString("analysis_id"))
                        }
                        ui.post { if (lifecycle.get()) {
                            busy = false
                            result.onSuccess { history = it }
                            message = if (result.isSuccess) "本人の回答を保存しました。" else "保存または履歴確認に失敗しました。再送前にセッションを選び直して履歴を確認してください。"
                        } }
                    }
                }
            }
        }
    }
}
