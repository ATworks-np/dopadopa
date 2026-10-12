package com.example.dopadopa

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.dopadopa.data.*
import java.util.UUID
import com.example.dopadopa.cloud.CloudRepository
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.dopadopa.ui.CloudAnalysisScreen
import com.example.dopadopa.ui.UsageConversation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFA9B5FF),
                background = Color(0xFF11131C), surface = Color(0xFF1A1D29)) else lightColorScheme(
                primary = Color(0xFF5364DB), onPrimary = Color.White, primaryContainer = Color(0xFFE5E9FF),
                background = Color(0xFFF6F7FC), surface = Color.White, onSurface = Color(0xFF202437))
            MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
                GuardScreen()
            }
        }
    }

    @Composable private fun GuardScreen() {
        val prefs = remember { getSharedPreferences("guard", MODE_PRIVATE) }
        var confirmed by remember { mutableStateOf(prefs.getBoolean("goal_confirmed", false)) }
        var collection by remember { mutableStateOf(prefs.getBoolean("collection_consent", false)) }
        var automatic by remember { mutableStateOf(prefs.getBoolean("automatic_consent", false)) }
        var editingGoal by remember { mutableStateOf(false) }
        var goalDraft by remember { mutableStateOf("") }
        var cloud by remember { mutableStateOf(prefs.getBoolean("cloud_consent", false)) }
        var goal by remember { mutableStateOf(prefs.getString("goal", "目的を確認してYouTubeを使い、意図しない長時間利用を減らしたい").orEmpty()) }
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
        var tab by rememberSaveable { mutableIntStateOf(0) }
        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "automatic_consent") automatic = prefs.getBoolean("automatic_consent", false)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        DisposableEffect(auth) {
            val listener = FirebaseAuth.AuthStateListener {
                val uid = it.currentUser?.uid
                if (prefs.getString("automatic_owner_uid", null) != uid) {
                    synchronized(PrivacyGate.lock) {
                        prefs.edit().putBoolean("automatic_consent", false).commit()
                        CloudRepository.cancel()
                    }
                    automatic = false
                }
                account = it.currentUser?.email
            }
            auth.addAuthStateListener(listener)
            onDispose { auth.removeAuthStateListener(listener) }
        }

        fun saveConsent() {
            val record = ConsentRecord(revision, collection, cloud, System.currentTimeMillis())
            PrivacyGate.io.execute { runCatching { GuardDatabase.get(this).dao().saveConsent(record) } }
        }
        fun revoke(delete: Boolean) {
            synchronized(PrivacyGate.lock) {
                prefs.edit().putBoolean("collection_consent", false).putBoolean("cloud_consent", false).putBoolean("automatic_consent", false).commit()
                CloudRepository.cancel()
                if (delete) deleteFile("spike-events.jsonl")
            }
            collection = false; cloud = false; automatic = false
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
        Scaffold(modifier = Modifier.safeDrawingPadding(), containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("DopaGuard", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (!confirmed) "あなたの目的から、はじめよう" else listOf("ホーム", "AIの分析", "行動の履歴", "設定")[tab],
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, bottomBar = {
                if (confirmed) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("ホーム", "分析", "履歴", "設定").forEachIndexed { index, name ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("⌂", "◎", "≡", "⚙")[index], style = MaterialTheme.typography.titleLarge) },
                            label = { Text(name) })
                    }
                }
            }) { padding ->
        key(tab, confirmed) {
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!confirmed) {
                UsageConversation(this@MainActivity, saving) { description ->
                    saving = true; error = null
                    val value = UserGoal("g-" + UUID.randomUUID(), description, "youtube", System.currentTimeMillis(), "local")
                    PrivacyGate.io.execute {
                        val result = runCatching { GuardDatabase.get(this@MainActivity).dao().saveGoal(value) }
                        runOnUiThread {
                            saving = false
                            if (result.isSuccess) {
                                prefs.edit().putString("goal", value.description).putString("goal_revision", value.goalId).putBoolean("goal_confirmed", true).commit(); confirmed = true
                                goal = value.description; tab = 0
                            } else error = "使い方の保存に失敗しました。再実行してください。"
                        }
                    }
                }
            } else {
                if (tab == 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (automatic) "● 自動分析 ON" else "○ 自動分析 OFF", style = MaterialTheme.typography.titleLarge)
                        Text(if (automatic) "YouTubeでの行動を1分ごとに振り返ります。" else "ゴールと同意を確認して、自分のペースで始めましょう。")
                        if (!collection || !cloud || account == null) TextButton(onClick = { tab = 3 }) { Text("設定を確認する →") }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("あなたが望む使い方", style = MaterialTheme.typography.titleLarge)
                Text(goal)
                OutlinedButton(enabled = !saving, onClick = { goalDraft = goal; editingGoal = true }) { Text("使い方を変更") }
                if (editingGoal) {
                    OutlinedTextField(goalDraft, { if (it.length <= 500) goalDraft = it }, label = { Text("YouTubeをどのように使いたいですか？") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = goalDraft.isNotBlank() && !saving, onClick = {
                        saving = true
                        val value = UserGoal("g-" + UUID.randomUUID(), goalDraft.trim(), "youtube", System.currentTimeMillis(), "local")
                        // Invalidate pending work before changing the goal.
                        automatic = false
                        synchronized(PrivacyGate.lock) {
                            prefs.edit().putBoolean("automatic_consent", false).commit()
                            CloudRepository.cancel()
                        }
                        PrivacyGate.io.execute {
                            val result = runCatching { GuardDatabase.get(this@MainActivity).dao().saveGoal(value) }
                            runOnUiThread {
                                saving = false
                                if (result.isSuccess) {
                                    synchronized(PrivacyGate.lock) {
                                        prefs.edit().putString("goal", value.description).putString("goal_revision", value.goalId).commit()
                                    }
                                    goal = value.description; editingGoal = false
                                } else error = "ゴールの保存に失敗しました。再実行してください。"
                            }
                        }
                    }) { Text("変更を確認して保存") }
                    OutlinedButton(onClick = { editingGoal = false }) { Text("キャンセル") }
                }
                Text("使い方はあなたが選びます。AIが勝手に変更することはありません。")
                }
                }
                }
                if (tab == 3) {
                Text("データと同意", style = MaterialTheme.typography.titleLarge)
                Text("操作時刻と取得可能なクリック文言を端末内に保存します。入力欄・認証・機密UIは除外し、取得不能はmissingと記録します。")
                CheckRow("端末内での収集に同意する", collection) { checked ->
                    collection = checked
                    automatic = false
                    revision = "consent-" + UUID.randomUUID()
                    synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("collection_consent", checked)
                        .putBoolean("automatic_consent", false).putString("consent_revision", revision).putLong("collection_since", System.currentTimeMillis()).commit() }
                    CloudRepository.cancel()
                    saveConsent()
                }
                Text("Gemini解析では許可した操作文言とゴールをクラウドへ送信します。端末内収集とは別の同意です。同意以前のイベントは送りません。手動同期は下のボタンから利用できます。")
                CheckRow("クラウド解析への送信に同意する", cloud) { checked ->
                    cloud = checked
                    automatic = false
                    revision = "consent-" + UUID.randomUUID()
                    synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", checked).putBoolean("automatic_consent", false).putString("consent_revision", revision).commit() }
                    CloudRepository.cancel()
                    saveConsent()
                }
                }
                if (tab == 0) {
                Text("利用中の自動分析・提案", style = MaterialTheme.typography.titleLarge)
                Text("有効にすると、YouTube利用中の取得可能なクリック文言とゴールを自動送信して分析します。ゴールから離れた可能性がある場合に提案し、あなたが選んだ場合だけ10分休止します。休止はすぐ解除できます。")
                CheckRow("自動送信・分析と提案に同意する", automatic,
                    enabled = collection && cloud && account != null && !saving && !editingGoal) { checked ->
                    revision = "consent-" + UUID.randomUUID()
                    synchronized(PrivacyGate.lock) {
                        val editor = prefs.edit().putBoolean("automatic_consent", checked)
                            .putString("automatic_owner_uid", auth.currentUser?.uid)
                            .putLong("automatic_since", System.currentTimeMillis()).putString("consent_revision", revision)
                        if (prefs.getString("goal_revision", null) == null) editor.putString("goal_revision", "g-" + UUID.randomUUID())
                        editor.commit()
                        CloudRepository.cancel()
                    }
                    automatic = checked
                    saveConsent()
                }
                Text(if (automatic) "YouTube上のアイコンから状態を確認できます。提案の「理由を見る」でAIの説明を読み、休止はあなたが選んだ場合だけ始まります。" else "設定タブで端末内収集・クラウド送信への同意とログインを確認してください。")
                }
                if (tab == 3) {
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
                        cloud = false; automatic = false
                        synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", false).putBoolean("automatic_consent", false).commit(); CloudRepository.cancel() }
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
                        cloud = false; automatic = false
                        revision = "consent-" + UUID.randomUUID()
                        synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", false).putBoolean("automatic_consent", false).putString("consent_revision", revision).commit(); CloudRepository.cancel() }
                        saveConsent(); auth.signOut()
                    }) { Text("ログアウト") }
                }
                Text(cloudStatus)
                }
                if (tab == 1) {
                    Text("行動の流れと、目的が変わった可能性を振り返る", style = MaterialTheme.typography.titleLarge)
                    Text("分析はAIの仮説です。観測した操作と、その根拠を確認できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    auth.currentUser?.uid?.let { uid -> key(uid, cloudDataEpoch) { CloudAnalysisScreen(this@MainActivity, uid, cloud) } }
                        ?: Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                            Text("分析を見るにはログインが必要です。")
                            TextButton(onClick = { tab = 3 }) { Text("設定でログイン") }
                        } }
                }
                if (tab == 3) {
                Text("撤回後は新しい送信を停止します。すでに送信したデータを消す場合はクラウド削除を実行してください。端末内データの削除とは別です。")
                Text("同意しなくても設定画面を利用できます。OS権限だけでは収集は始まりません。")
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("UI操作取得の権限設定") }
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("利用状況アクセスの権限設定") }
                }
                if (tab == 2) {
                Text("端末に記録した行動", style = MaterialTheme.typography.titleLarge)
                Text("YouTubeでの操作を時系列で確認できます。同期した記録は分析タブで振り返れます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    PrivacyGate.io.execute {
                        val value = runCatching {
                            val dao = GuardDatabase.get(this@MainActivity).dao()
                            val sessions = dao.sessions().take(10)
                            val permission = TimelineRepository(this@MainActivity).hasUsagePermission()
                            "利用状況権限: ${if (permission) "許可" else "未許可"}\n直近${sessions.size}セッション\n" + sessions.joinToString("\n\n") { session ->
                                val time = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault())
                                "${time.format(java.time.Instant.ofEpochMilli(session.startedAt))} · ${if (session.dataOrigin == "real") "実際の操作" else "サンプル"}\n${if (session.endedAt == null) "終了は未確認" else "終了を確認済み"}\n" + dao.timeline(session.sessionId, 200).joinToString("\n") {
                                    val name = mapOf("CLICK" to "クリック", "SCROLL" to "スクロール", "WINDOW_CHANGE" to "画面の切り替え",
                                        "APP_FOREGROUND" to "利用開始", "APP_BACKGROUND" to "離脱")[it.eventType] ?: "操作"
                                    "${time.format(java.time.Instant.ofEpochMilli(it.timestamp))}  $name · " + when (it.textStatus) {
                                        "available" -> "文言を記録"; "redacted" -> "機密情報を除外"; else -> "文言は取得できません"
                                    }
                                }
                            }
                        }.getOrDefault("取得状態を読めませんでした。")
                        runOnUiThread { summary = value }
                    }
                }) { Text("取得状態を更新") }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(summary, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
                }
                if (tab == 3) {
                HorizontalDivider()
                Text("データの管理", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { revoke(false) }) { Text("収集とクラウド同意を撤回") }
                OutlinedButton(enabled = !saving, onClick = { revoke(true) }) { Text("端末内データをすべて削除") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        }
        }
    }

    @Composable private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked, change, enabled = enabled)
            }
        }
    }
}
