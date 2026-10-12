package com.example.dopadopa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local choice conversation. Confirmation never implies data/analysis consent. */
object UsageQuestions {
    data class Question(val text: String, val choices: List<String>)
    fun question(answers: List<String>): Question = when (answers.size) {
        0 -> Question("YouTubeをどのように使いたいですか？", listOf("学びや調べものに使いたい", "趣味や好きな動画を楽しみたい", "気分転換や休憩に使いたい"))
        1 -> when (answers[0]) {
            "学びや調べものに使いたい" -> Question("どんな学びに使いたいですか？", listOf("仕事や勉強に役立つ知識", "趣味や暮らしの知識", "そのとき気になったことを調べる"))
            "趣味や好きな動画を楽しみたい" -> Question("どんな楽しみ方が近いですか？", listOf("好きなテーマをじっくり楽しむ", "好きな投稿者の動画を楽しむ", "いろいろな動画に出会いたい"))
            else -> Question("どんな休憩にしたいですか？", listOf("作業の合間にひと息つく", "一日の終わりにくつろぐ", "気持ちを切り替える"))
        }
        2 -> Question("1回に使う時間の目安はありますか？", listOf("15分くらい", "30分くらい", "1時間くらい", "時間は決めず、内容を大切にしたい"))
        3 -> Question("使い方を振り返るとき、何を大切にしたいですか？", listOf("最初に選んだ目的を大切にしたい", "別の話題に移ったら気づきたい", "自由に楽しみつつ、ときどき振り返りたい"))
        else -> error("confirmation has no further question")
    }
    fun description(answers: List<String>): String {
        require(answers.size == 4)
        answers.indices.forEach { i -> require(answers[i] in question(answers.take(i)).choices) }
        return "YouTubeは、${answers[0]}。具体的には、${answers[1]}。" +
            "利用時間の希望は「${answers[2]}」。使い方では「${answers[3]}」。時間は目安で、自動的な制限を希望するものではありません。"
    }
}

@Composable fun UsageConversation(saving: Boolean, confirm: (String) -> Unit) {
    var answers by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${answers.size + 1} / 5", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { (answers.size + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DopaGuard", style = MaterialTheme.typography.labelLarge)
                Text(if (answers.size < 4) UsageQuestions.question(answers).text else "この使い方で始めますか？", style = MaterialTheme.typography.headlineSmall)
                if (answers.size == 4) Text(UsageQuestions.description(answers), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (answers.size < 4) {
            Text("近いものをひとつ選んでください。あとで変更できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            UsageQuestions.question(answers).choices.forEach { choice ->
                OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !saving,
                    onClick = { answers = ArrayList(answers + choice) }) { Text(choice) }
            }
        } else {
            Text("選んだ使い方だけを保存します。データ収集や自動分析は、設定で別に選べます。")
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !saving,
                onClick = { confirm(UsageQuestions.description(answers)) }) { Text(if (saving) "保存中…" else "この使い方で始める") }
        }
        if (answers.isNotEmpty()) TextButton(enabled = !saving, onClick = { answers = ArrayList(answers.dropLast(1)) }) { Text("ひとつ前に戻る") }
    }
}
