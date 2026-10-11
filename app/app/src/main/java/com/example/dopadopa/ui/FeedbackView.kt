package com.example.dopadopa.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray

/** The form has no default verdict. Only the explicit save callback can write. */
@Composable fun FeedbackView(history: JSONArray, enabled: Boolean, onSave: (String, String?) -> Unit) {
    var verdict by remember { mutableStateOf<String?>(null) }
    var correction by remember { mutableStateOf("") }
    Column {
        Text("本人による確認・訂正", style = MaterialTheme.typography.titleMedium)
        Text("訂正はクラウドへ保存されます。個人情報や秘密は入力しないでください。元の観測イベントは変更しません。")
        if (history.length() == 0) Text("未回答")
        for (i in 0 until history.length()) {
            val row = history.getJSONObject(i)
            Text("${row.getString("created_at")} · ${feedbackLabel(row.getString("verdict"))}")
            if (!row.isNull("corrected_intent")) Text("本人の訂正: ${row.getString("corrected_intent")}")
        }
        listOf("correct", "incorrect", "unknown").forEach { choice ->
            OutlinedButton(enabled = enabled, onClick = { verdict = choice }) {
                Text((if (verdict == choice) "選択中: " else "") + feedbackLabel(choice))
            }
        }
        if (verdict == "incorrect") OutlinedTextField(value = correction,
            onValueChange = { if (it.length <= 500) correction = it }, enabled = enabled,
            label = { Text("利用意図の訂正（任意・500文字まで）") })
        Button(enabled = enabled && verdict != null, onClick = {
            onSave(verdict!!, if (verdict == "incorrect") correction.trim().ifEmpty { null } else null)
        }) { Text("この回答を保存") }
    }
}

private fun feedbackLabel(value: String) = when (value) {
    "correct" -> "合っている"
    "incorrect" -> "違う"
    else -> "分からない"
}
