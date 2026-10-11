package com.example.dopadopa

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dopadopa.data.*
import java.util.UUID
import com.example.dopadopa.cloud.CloudRepository
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.dopadopa.ui.CloudAnalysisScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { GuardScreen() } } }
    }

    @Composable private fun GuardScreen() {
        val prefs = remember { getSharedPreferences("guard", MODE_PRIVATE) }
        var confirmed by remember { mutableStateOf(prefs.getBoolean("goal_confirmed", false)) }
        var collection by remember { mutableStateOf(prefs.getBoolean("collection_consent", false)) }
        var cloud by remember { mutableStateOf(prefs.getBoolean("cloud_consent", false)) }
        var goal by remember { mutableStateOf(prefs.getString("goal", "目的を確認してYouTubeを使い、意図しない長時間利用を減らしたい").orEmpty()) }
        var longUse by remember { mutableStateOf(false) }
        var repeated by remember { mutableStateOf(false) }
        var tendency by remember { mutableStateOf("両方") }
        var summary by remember { mutableStateOf("まだ取得状態を確認していません。") }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var revision by remember { mutableStateOf(prefs.getString("consent_revision", "consent-v1").orEmpty()) }
        val auth = remember { FirebaseAuth.getInstance() }
        var account by remember { mutableStateOf(auth.currentUser?.email) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var cloudBusy by remember { mutableStateOf(false) }
        var cloudStatus by remember { mutableStateOf("まだ同期していません。") }
        var cloudDataEpoch by remember { mutableIntStateOf(0) }
        DisposableEffect(auth) {
            val listener = FirebaseAuth.AuthStateListener { account = it.currentUser?.email }
            auth.addAuthStateListener(listener)
            onDispose { auth.removeAuthStateListener(listener) }
        }

        fun saveConsent() {
            val record = ConsentRecord(revision, collection, cloud, System.currentTimeMillis())
            PrivacyGate.io.execute { runCatching { GuardDatabase.get(this).dao().saveConsent(record) } }
        }
        fun revoke(delete: Boolean) {
            synchronized(PrivacyGate.lock) {
                prefs.edit().putBoolean("collection_consent", false).putBoolean("cloud_consent", false).commit()
                CloudRepository.cancel()
                if (delete) deleteFile("spike-events.jsonl")
            }
            collection = false; cloud = false
            if (delete) {
                saving = true
                PrivacyGate.io.execute {
                    val result = runCatching { GuardDatabase.get(this).dao().deleteAll() }
                    runOnUiThread {
                        saving = false
                        if (result.isSuccess) {
                            prefs.edit().clear().commit(); confirmed = false; summary = "端末内データを削除しました。"
                            getSharedPreferences("cloud_queue", MODE_PRIVATE).edit().clear().commit()
                        } else error = "収集は停止しました。削除に失敗したため再実行してください。"
                    }
                }
            } else saveConsent()
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DopaGuard", style = MaterialTheme.typography.headlineLarge)
            Text("操作の流れから、使い方を振り返る", style = MaterialTheme.typography.titleMedium)
            if (!confirmed) {
                Text("1 · 困っていること", style = MaterialTheme.typography.titleLarge)
                CheckRow("気づいたら長時間使っている", longUse) { longUse = it }
                CheckRow("同じアプリを何度も開く", repeated) { repeated = it }
                Text("2 · 対象アプリ")
                CheckRow("YouTube", true) { }
                Text("Instagram / X / TikTok — 今後対応予定", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("3 · 起きやすいこと")
                listOf("無意識に開く", "開くと止まらない", "両方").forEach { value ->
                    Row { RadioButton(tendency == value, { tendency = value }); Text(value, Modifier.padding(top = 12.dp)) }
                }
                Button(onClick = {
                    goal = when {
                        longUse && repeated -> "YouTubeを開く目的を確認し、意図しない起動と長時間利用を減らしたい"
                        repeated || tendency == "無意識に開く" -> "YouTubeを開く前に目的を確認したい"
                        else -> "YouTubeを使う目的を振り返り、意図しない長時間利用を減らしたい"
                    }
                }) { Text("選択から仮ゴールを作る") }
                Text("4 · ゴールを確認・修正")
                OutlinedTextField(goal, { if (it.length <= 500) goal = it }, label = { Text("本人のゴール") }, modifier = Modifier.fillMaxWidth())
                Text("これは仮の提案です。確認したゴールをAIが勝手に変更することはありません。")
                Button(enabled = goal.isNotBlank() && !saving && (longUse || repeated), onClick = {
                    saving = true; error = null
                    val value = UserGoal("g-" + UUID.randomUUID(), goal.trim(), "youtube", System.currentTimeMillis(), "local")
                    PrivacyGate.io.execute {
                        val result = runCatching { GuardDatabase.get(this@MainActivity).dao().saveGoal(value) }
                        runOnUiThread {
                            saving = false
                            if (result.isSuccess) {
                                prefs.edit().putString("goal", value.description).putBoolean("goal_confirmed", true).commit(); confirmed = true
                            } else error = "ゴールの保存に失敗しました。再実行してください。"
                        }
                    }
                }) { Text(if (saving) "保存中…" else "このゴールを確認して次へ") }
            } else {
                Text("確認済みのゴール", style = MaterialTheme.typography.titleLarge)
                Text(goal)
                Text("観察から始めます。強制的な利用制限は行いません。")
                HorizontalDivider()
                Text("データと同意", style = MaterialTheme.typography.titleLarge)
                Text("操作時刻と取得可能なクリック文言を端末内に保存します。入力欄・認証・機密UIは除外し、取得不能はmissingと記録します。")
                CheckRow("端末内での収集に同意する", collection) { checked ->
                    collection = checked
                    revision = "consent-" + UUID.randomUUID()
                    synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("collection_consent", checked)
                        .putString("consent_revision", revision).putLong("collection_since", System.currentTimeMillis()).commit() }
                    CloudRepository.cancel()
                    saveConsent()
                }
                Text("Gemini解析では許可した操作文言とゴールをクラウドへ送信します。端末内収集とは別の同意です。同意以前のイベントは送りません。同期は下のボタンで開始します。")
                CheckRow("クラウド解析への送信に同意する", cloud) { checked ->
                    cloud = checked
                    revision = "consent-" + UUID.randomUUID()
                    synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", checked).putString("consent_revision", revision).commit() }
                    if (!checked) CloudRepository.cancel()
                    saveConsent()
                }
                Text("クラウドアカウント", style = MaterialTheme.typography.titleLarge)
                Text("DopaGuardのログインです。Google Cloud管理アカウントとは別です。")
                if (account == null) {
                    OutlinedTextField(email, { email = it }, label = { Text("メールアドレス") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("パスワード") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    fun authenticate(create: Boolean) {
                        cloudBusy = true; error = null
                        val task = if (create) auth.createUserWithEmailAndPassword(email.trim(), password)
                            else auth.signInWithEmailAndPassword(email.trim(), password)
                        task.addOnCompleteListener {
                            password = ""; cloudBusy = false
                            if (!it.isSuccessful) error = "ログインできませんでした。入力と通信状態を確認してください。"
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !cloudBusy && email.isNotBlank() && password.length >= 6, onClick = { authenticate(false) }) { Text("ログイン") }
                        OutlinedButton(enabled = !cloudBusy && email.isNotBlank() && password.length >= 6, onClick = { authenticate(true) }) { Text("新規登録") }
                    }
                } else {
                    Text(account.orEmpty())
                    Button(enabled = cloud && !cloudBusy, onClick = {
                        cloudBusy = true
                        CloudRepository.io.execute {
                            val result = runCatching { CloudRepository(this@MainActivity).sync() }
                            runOnUiThread { cloudBusy = false; cloudStatus = result.getOrDefault("同期できませんでした。通信状態を確認して再実行してください。") }
                        }
                    }) { Text(if (cloudBusy) "処理中…" else "終了したセッションを同期") }
                    OutlinedButton(enabled = !cloudBusy, onClick = {
                        cloudBusy = true
                        // Stop new uploads before deletion; already received data is removed by the API.
                        cloud = false
                        synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", false).commit(); CloudRepository.cancel() }
                        saveConsent()
                        CloudRepository.io.execute {
                            val result = runCatching { CloudRepository(this@MainActivity).deleteCloud() }
                            runOnUiThread {
                                cloudBusy = false
                                if (result.isSuccess) cloudDataEpoch++
                                cloudStatus = result.getOrDefault("クラウド削除に失敗しました。ログインを維持して再実行してください。")
                            }
                        }
                    }) { Text("クラウドデータをすべて削除") }
                    OutlinedButton(enabled = !cloudBusy, onClick = {
                        cloud = false
                        revision = "consent-" + UUID.randomUUID()
                        synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", false).putString("consent_revision", revision).commit(); CloudRepository.cancel() }
                        saveConsent(); auth.signOut()
                    }) { Text("ログアウト") }
                }
                Text(cloudStatus)
                auth.currentUser?.uid?.let { uid -> key(uid, cloudDataEpoch) { CloudAnalysisScreen(this@MainActivity, uid, cloud) } }
                Text("撤回後は新しい送信を停止します。すでに送信したデータを消す場合はクラウド削除を実行してください。端末内データの削除とは別です。")
                Text("同意しなくても設定画面を利用できます。OS権限だけでは収集は始まりません。")
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("UI操作取得の権限設定") }
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("利用状況アクセスの権限設定") }
                Text("実機収集の検証", style = MaterialTheme.typography.titleLarge)
                Text("端末内タイムライン\n開始・終了は取得可能な利用状況イベントから記録します。終了を観測できないセッションは未確定です。同期済みの観測は上の画面から分析できます。")
                Button(onClick = {
                    PrivacyGate.io.execute {
                        val value = runCatching {
                            val dao = GuardDatabase.get(this@MainActivity).dao()
                            val sessions = dao.sessions().take(10)
                            val permission = TimelineRepository(this@MainActivity).hasUsagePermission()
                            "利用状況権限: ${if (permission) "許可" else "未許可"}\n直近${sessions.size}セッション\n" + sessions.joinToString("\n\n") { session ->
                                "${session.sessionId} · ${session.dataOrigin} · 終了${if (session.endedAt == null) "未確定" else "確認済み"}\n" + dao.timeline(session.sessionId, 200).joinToString("\n") {
                                    "${it.eventId} · ${it.eventType} · ${it.textStatus} / ${it.observability}"
                                }
                            }
                        }.getOrDefault("取得状態を読めませんでした。")
                        runOnUiThread { summary = value }
                    }
                }) { Text("取得状態を更新") }
                Text(summary)
                OutlinedButton(onClick = { revoke(false) }) { Text("収集とクラウド同意を撤回") }
                OutlinedButton(enabled = !saving, onClick = { revoke(true) }) { Text("端末内データをすべて削除") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    @Composable private fun CheckRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth()) {
            Checkbox(checked, change)
            Text(label, Modifier.padding(top = 12.dp))
        }
    }
}
