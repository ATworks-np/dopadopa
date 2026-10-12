package com.example.dopadopa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.dopadopa.cloud.CloudRepository
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

/** AI-generated questions. No static question fallback is presented as an agent response. */
@Composable fun UsageConversation(context: Context, saving: Boolean, confirm: (String) -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.uid }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (uid == null) {
        Text("AIと相談して、YouTubeの使い方を決めましょう", style = MaterialTheme.typography.headlineSmall)
        Text("AIとの対話にはログインが必要です。入力した回答の送信は、次の画面で選べます。")
        OutlinedTextField(email, { email = it }, label = { Text("メールアドレス") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("パスワード") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        fun login(create: Boolean) {
            loggingIn = true; loginError = null
            val task = if (create) auth.createUserWithEmailAndPassword(email.trim(), password) else auth.signInWithEmailAndPassword(email.trim(), password)
            task.addOnCompleteListener {
                loggingIn = false; password = ""
                if (!it.isSuccessful) loginError = "ログインできませんでした。入力と通信状態を確認してください。"
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loggingIn && email.isNotBlank() && password.length >= 6, onClick = { login(false) }) { Text("ログイン") }
            OutlinedButton(enabled = !loggingIn && email.isNotBlank() && password.length >= 6, onClick = { login(true) }) { Text("新規登録") }
        }
        loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else key(uid) {
        AgentConversation(context, uid!!, saving, confirm)
    }
}

@Composable private fun AgentConversation(context: Context, uid: String, saving: Boolean, confirm: (String) -> Unit) {
    var consent by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var step by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestHistory by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val requestSequence = remember { java.util.concurrent.atomic.AtomicLong() }
    DisposableEffect(uid) { onDispose { live.set(false) } }
    fun ask(next: List<JSONObject>) {
        if (busy || !consent) return
        busy = true; error = null; requestHistory = next
        val requestId = requestSequence.incrementAndGet()
        CloudRepository.io.execute {
            val result = runCatching {
                val value = CloudRepository(context).consultUsage(JSONArray().also { rows -> next.forEach { rows.put(it) } }, consent, uid)
                val message = value.getString("message")
                val choices = value.getJSONArray("choices")
                require(message.isNotBlank() && message.length <= 500)
                if (next.size == 4) require(choices.length() == 0 && !value.isNull("summary") && value.getString("summary").isNotBlank() && value.getString("summary").length <= 500)
                else require(choices.length() in 2..5 && value.isNull("summary") &&
                    (0 until choices.length()).map { choices.getString(it) }.let { options ->
                        options.distinct().size == options.size && options.all { it.isNotBlank() && it.length <= 150 }
                    })
                value
            }
            handler.post {
                if (live.get() && requestSequence.get() == requestId && FirebaseAuth.getInstance().currentUser?.uid == uid) {
                    busy = false
                    result.onSuccess { history = next; step = it }
                    if (result.isFailure) error = "AIに接続できませんでした。回答は追加せず、そのまま再試行できます。"
                }
            }
        }
    }
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!consent) {
            Text("AIと相談して、使い方を決める", style = MaterialTheme.typography.headlineSmall)
            Text("あなたの選択に合わせてAIが質問を考えます。約5ターンでまとめ、最後にあなたが確認します。")
            Text("質問と選んだ回答をGeminiへ送ります。YouTubeの操作履歴は送りません。相談履歴はサーバーに保存せず、収集や自動分析の同意とは別です。")
            Button(onClick = { consent = true; ask(emptyList()) }) { Text("回答のAI送信に同意して相談を始める") }
        } else {
            Text("${history.size + 1} / 5 · AIとの相談", style = MaterialTheme.typography.labelLarge)
            step?.let { current ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.getString("message"), style = MaterialTheme.typography.titleLarge)
                        if (history.size == 4) Text(current.getString("summary"))
                    }
                }
                if (history.size < 4) {
                    val choices = current.getJSONArray("choices")
                    for (i in 0 until choices.length()) {
                        val option = choices.getString(i)
                        OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy && !saving,
                            onClick = { ask(history + JSONObject().put("question", current.getString("message")).put("choices", choices).put("answer", option)) }) { Text(option) }
                    }
                } else Button(enabled = !saving && !busy, onClick = { confirm(current.getString("summary")) }) { Text(if (saving) "保存中…" else "この使い方で始める") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("AIが回答を受けて考えています…") }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                OutlinedButton(enabled = !busy, onClick = { ask(requestHistory) }) { Text("同じ回答で再試行") }
            }
            if (history.isNotEmpty()) TextButton(enabled = !busy && !saving, onClick = { ask(history.dropLast(1)) }) { Text("ひとつ前から選び直す") }
            TextButton(enabled = !saving, onClick = {
                requestSequence.incrementAndGet(); CloudRepository.cancel(); busy = false
                consent = false; step = null; history = emptyList(); requestHistory = emptyList(); error = null
            }) { Text("相談をやめる") }
        }
    }
}
