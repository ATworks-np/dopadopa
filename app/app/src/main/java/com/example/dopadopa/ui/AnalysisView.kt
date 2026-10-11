package com.example.dopadopa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** All model text is shown under hypothesis labels, never as observed fact. */
@Composable fun AnalysisView(record: JSONObject, timeline: JSONObject) {
    var selectedEvidence by remember(record.toString()) { mutableStateOf<String?>(null) }
    val status = record.getString("status")
    Text("分析結果", style = MaterialTheme.typography.titleLarge)
    Text("data_origin: ${record.getString("data_origin")} · ${if (record.getString("data_origin") == "sample") "架空サンプル" else "端末で収集した観測"}")
    when (status) {
        "running", "pending" -> Text("分析中です。YouTubeの操作には割り込みません。")
        "failed" -> Text("分析に失敗しました。通信状態を確認して再実行できます。", color = MaterialTheme.colorScheme.error)
        "completed", "insufficient_data" -> {
            val result = record.getJSONObject("result")
            Text("AIの仮説です。本人の確認が必要です。")
            val alignment = when (result.getString("goal_alignment")) {
                "aligned" -> "ゴールと整合している可能性"
                "possibly_diverged" -> "ゴールから離れた可能性"
                else -> "不明（unknown）"
            }
            Text(alignment, style = MaterialTheme.typography.titleMedium)
            if (status == "insufficient_data") Text("観測が足りないため、利用意図を判断できません。")
            Text("不確実性: ${result.getString("uncertainty")}")
            Text("閲読・視線・実際の視聴、依存や後悔を確定するものではありません。意図的な休憩の可能性もあります。")
            val segments = result.getJSONArray("segments")
            for (i in 0 until segments.length()) {
                val segment = segments.getJSONObject(i)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("話題の候補: ${segment.getString("topic")}")
                        Text("利用意図の仮説: ${segment.getString("intent_hypothesis")}")
                        Text("範囲: ${segment.getString("from_event_id")} → ${segment.getString("to_event_id")}")
                        Text("観測時刻: ${eventTime(timeline, segment.getString("from_event_id"))} → ${eventTime(timeline, segment.getString("to_event_id"))}")
                        EvidenceButtons(segment, { selectedEvidence = it })
                    }
                }
            }
            val transitions = result.getJSONArray("transitions")
            for (i in 0 until transitions.length()) {
                val transition = transitions.getJSONObject(i)
                Text("転換点の候補: ${transition.getString("hypothesis")}")
                Text("観測時刻: ${eventTime(timeline, transition.getString("at_event_id"))}")
                EvidenceButtons(transition, { selectedEvidence = it })
            }
            if (segments.length() == 0 && transitions.length() == 0) Text("提示できる話題・転換点の根拠がありません。")
        }
        else -> Text("分析状態を確認できません。")
    }
    selectedEvidence?.let { id ->
        val events = timeline.getJSONArray("events")
        val event = (0 until events.length()).map { events.getJSONObject(it) }.firstOrNull { it.getString("event_id") == id }
        AlertDialog(onDismissRequest = { selectedEvidence = null },
            title = { Text("観測根拠 · $id") },
            text = { if (event == null) Text("このセッションの根拠を取得できません。") else Column {
                Text("時刻: ${event.getString("timestamp")}")
                Text("操作: ${event.getString("event_type")}")
                Text("text_status: ${event.getString("text_status")}")
                Text("source: ${event.getString("source")} · observability: ${event.getString("observability")}")
                Text(if (event.getString("text_status") == "available") "取得した文言: ${event.getString("text")}" else "取得できる原文はありません。")
            } }, confirmButton = { TextButton(onClick = { selectedEvidence = null }) { Text("閉じる") } })
    }
    val trace = record.optJSONArray("trace")
    if (trace != null) {
        Text("調査Tool", style = MaterialTheme.typography.titleMedium)
        for (i in 0 until trace.length()) {
            val row = trace.getJSONObject(i)
            Text("${row.getString("tool")} · ${row.getInt("count")}件 · ${row.getInt("elapsed_ms")}ms")
        }
    }
}

private fun eventTime(timeline: JSONObject, id: String): String {
    val events = timeline.getJSONArray("events")
    return (0 until events.length()).map { events.getJSONObject(it) }
        .firstOrNull { it.getString("event_id") == id }?.getString("timestamp") ?: "取得不能"
}

@Composable private fun EvidenceButtons(value: JSONObject, selected: (String) -> Unit) {
    val ids = value.getJSONArray("evidence_ids")
    Column {
        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            TextButton(onClick = { selected(id) }) { Text("根拠を見る · $id") }
        }
    }
}
