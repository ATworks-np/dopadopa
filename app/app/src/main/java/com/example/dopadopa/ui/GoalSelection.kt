package com.example.dopadopa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

val goalOptions = listOf("学習・スキルアップ", "情報収集", "趣味・娯楽", "休憩・気分転換", "長時間利用を減らす")

@Composable fun GoalSelection(initial: Set<String> = emptySet(), saving: Boolean, save: (Set<String>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ArrayList(goalOptions.filter { it in initial })) }
    Text("YouTubeをどのように使いたいですか？", style = MaterialTheme.typography.titleLarge)
    Text("当てはまるものを選んでください（複数選択可）")
    goalOptions.forEach { option ->
        Row(Modifier.fillMaxWidth().toggleable(value = option in selected, enabled = !saving,
            role = Role.Checkbox, onValueChange = { checked ->
                selected = ArrayList(if (checked) selected + option else selected - option)
            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = option in selected, onCheckedChange = null, enabled = !saving)
            Text(option, Modifier.padding(start = 8.dp))
        }
    }
    Button(enabled = selected.isNotEmpty() && !saving, onClick = { save(selected.toSet()) },
        modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "保存する") }
}
