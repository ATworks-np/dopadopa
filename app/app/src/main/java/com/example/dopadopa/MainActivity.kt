package com.example.dopadopa

import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import com.example.dopadopa.ui.GoalSelection
import com.example.dopadopa.ui.goalOptions
import com.example.dopadopa.intervention.AnalysisInterval
import com.example.dopadopa.intervention.SuggestionInterval
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val permissionEpoch = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        permissionEpoch.intValue++
    }

    private fun usageAccessAllowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun guardServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.contains("GuardAccessibilityService") && it.contains("dopadopa") }
    }

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
        var notificationMinutes by remember { mutableIntStateOf(SuggestionInterval.minutes(prefs.getInt(SuggestionInterval.KEY, SuggestionInterval.DEFAULT_MINUTES))) }
        var analysisMinutes by remember { mutableIntStateOf(AnalysisInterval.minutes(prefs.getInt(AnalysisInterval.KEY, AnalysisInterval.DEFAULT_MINUTES))) }
        var confirmed by remember { mutableStateOf(prefs.getBoolean("goal_confirmed", false)) }
        var collection by remember { mutableStateOf(prefs.getBoolean("collection_consent", false)) }
        var automatic by remember { mutableStateOf(prefs.getBoolean("automatic_consent", false)) }
        var editingGoal by remember { mutableStateOf(false) }
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
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var confirmReset by remember { mutableStateOf(false) }
        var resetting by remember { mutableStateOf(false) }
        val permissionTick = permissionEpoch.intValue
        val usageOn = remember(permissionTick) { usageAccessAllowed() }
        val accessOn = remember(permissionTick) { guardServiceEnabled() }
        val permissionsReady = usageOn && accessOn
        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "automatic_consent") automatic = prefs.getBoolean("automatic_consent", false)
                if (key == SuggestionInterval.KEY || key == null) notificationMinutes = SuggestionInterval.minutes(prefs.getInt(SuggestionInterval.KEY, SuggestionInterval.DEFAULT_MINUTES))
                if (key == AnalysisInterval.KEY || key == null) analysisMinutes = AnalysisInterval.minutes(prefs.getInt(AnalysisInterval.KEY, AnalysisInterval.DEFAULT_MINUTES))
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
        fun setGuardEnabled(checked: Boolean) {
            if (checked && (!collection || !cloud || account == null || !usageAccessAllowed() || !guardServiceEnabled())) return
            revision = "consent-" + UUID.randomUUID()
            synchronized(PrivacyGate.lock) {
                val editor = prefs.edit().putBoolean("automatic_consent", checked)
                    .putString("automatic_owner_uid", auth.currentUser?.uid)
                    .putLong("automatic_since", System.currentTimeMillis()).putString("consent_revision", revision)
                if (checked && prefs.getString("goal_revision", null) == null) editor.putString("goal_revision", "g-" + UUID.randomUUID())
                editor.commit()
                CloudRepository.cancel()
            }
            automatic = checked
            saveConsent()
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
        fun saveSelectedGoal(selected: Set<String>) {
            saving = true; error = null
            val description = "YouTubeの使い方：" + goalOptions.filter { it in selected }.joinToString("、")
            val value = UserGoal("g-" + UUID.randomUUID(), description, "youtube", System.currentTimeMillis(), "local")
            if (confirmed) {
                automatic = false
                synchronized(PrivacyGate.lock) {
                    prefs.edit().putBoolean("automatic_consent", false).commit()
                    CloudRepository.cancel()
                }
            }
            PrivacyGate.io.execute {
                val result = runCatching { GuardDatabase.get(this@MainActivity).dao().saveGoal(value) }
                runOnUiThread {
                    saving = false
                    if (result.isSuccess) {
                        synchronized(PrivacyGate.lock) {
                            prefs.edit().putString("goal", description).putString("goal_revision", value.goalId)
                                .putStringSet("goal_options", selected).putBoolean("goal_confirmed", true).commit()
                        }
                        goal = description; confirmed = true; editingGoal = false; tab = 0
                    } else error = "使い方の保存に失敗しました。再実行してください。"
                }
            }
        }
        fun refreshHistory() {
            PrivacyGate.io.execute {
                val value = runCatching {
                    val dao = GuardDatabase.get(this@MainActivity).dao()
                    val sessions = dao.sessions().take(10)
                    if (sessions.isEmpty()) "まだYouTubeでの操作記録はありません。"
                    else sessions.joinToString("\n\n") { session ->
                        val time = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm:ss")
                            .withZone(java.time.ZoneId.systemDefault())
                        val events = dao.recentTimeline(session.sessionId, 200).reversed()
                        val clicks = events.filter { it.eventType == "CLICK" }
                        val withText = clicks.count { it.textStatus == "available" }
                        "${time.format(java.time.Instant.ofEpochMilli(session.startedAt))} · ${if (session.dataOrigin == "real") "実際の操作" else "サンプル"}\n" +
                            "クリック${clicks.size}件（文字あり${withText}件）\n" + events.joinToString("\n") { event ->
                                val name = mapOf("CLICK" to "クリック", "SCROLL" to "スクロール", "WINDOW_CHANGE" to "画面の切り替え",
                                    "APP_FOREGROUND" to "利用開始", "APP_BACKGROUND" to "離脱")[event.eventType] ?: "操作"
                                val label = if (event.textStatus == "available") " · " + event.text.orEmpty()
                                else if (event.eventType != "CLICK") "" else " · " + when (event.textStatus) {
                                    "available" -> event.text.orEmpty()
                                    "redacted" -> "保護された情報を除外"
                                    else -> "クリック対象の文字を取得できませんでした"
                                }
                                "${time.format(java.time.Instant.ofEpochMilli(event.timestamp))}  $name$label"
                            }
                    }
                }.getOrDefault("履歴を読み込めませんでした。もう一度更新してください。")
                runOnUiThread { summary = value }
            }
        }
        Scaffold(modifier = Modifier.safeDrawingPadding(), containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("DopaGuard", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (!confirmed) "あなたの目的から、はじめよう" else listOf("ホーム", "行動の履歴", "設定")[tab],
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, bottomBar = {
                if (confirmed) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("ホーム", "履歴", "設定").forEachIndexed { index, name ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("⌂", "≡", "⚙")[index], style = MaterialTheme.typography.titleLarge) },
                            label = { Text(name) })
                    }
                }
            }) { padding ->
        key(tab, confirmed) {
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!confirmed) {
                GoalSelection(saving = saving, save = ::saveSelectedGoal)
            } else {
                if (tab == 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (automatic) "DopaGuard 有効中" else "DopaGuard 無効", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Switch(checked = automatic, onCheckedChange = ::setGuardEnabled,
                                enabled = !saving && !editingGoal && (automatic || (collection && cloud && account != null && permissionsReady)))
                        }
                        Text(if (automatic) "YouTubeでの行動が望む使い方に沿っているか、${analysisMinutes}分ごとに判断します。" else "トグルで有効にすると、YouTube上で見守ります。")
                        if (!permissionsReady || !collection || !cloud || account == null) TextButton(onClick = { tab = 2 }) { Text("データと同意を確認する →") }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("あなたが望む使い方", style = MaterialTheme.typography.titleLarge)
                Text(goal)
                OutlinedButton(enabled = !saving, onClick = { editingGoal = true }) { Text("使い方を変更") }
                if (editingGoal) {
                    GoalSelection(initial = prefs.getStringSet("goal_options", emptySet()).orEmpty(),
                        saving = saving, save = ::saveSelectedGoal)
                    OutlinedButton(onClick = { editingGoal = false }) { Text("キャンセル") }
                }
                Text("使い方はあなたが選びます。AIが勝手に変更することはありません。")
                }
                }
                }
                if (tab == 2) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("分析の間隔", style = MaterialTheme.typography.titleLarge)
                        Text("${analysisMinutes}分", style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Slider(value = analysisMinutes.toFloat(), valueRange = 1f..20f, steps = 18,
                            enabled = !saving, onValueChange = { analysisMinutes = it.roundToInt() },
                            onValueChangeFinished = {
                                prefs.edit().putInt(AnalysisInterval.KEY, analysisMinutes).apply()
                            })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1分"); Text("20分")
                        }
                        Text("YouTube利用中の自動分析の間隔です。オーバーレイの「今すぐ分析」は待たずに1回実行します。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("提案通知の間隔", style = MaterialTheme.typography.titleLarge)
                        Text("${notificationMinutes}分", style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Slider(value = notificationMinutes.toFloat(), valueRange = 1f..20f, steps = 18,
                            enabled = !saving, onValueChange = { notificationMinutes = it.roundToInt() },
                            onValueChangeFinished = {
                                prefs.edit().putInt(SuggestionInterval.KEY, notificationMinutes).apply()
                            })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1分"); Text("20分")
                        }
                        Text("変更は自動で保存されます。分析の間隔とは別です。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("データと同意", style = MaterialTheme.typography.titleLarge)
                        Text(if (permissionsReady) "Androidの権限は入っています。" else "Androidの権限がまだ足りません。",
                            color = if (permissionsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        PermissionLine("ユーザー補助", accessOn) {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        PermissionLine("利用状況アクセス", usageOn) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        Text("権限があっても、下の同意をオンにするまで収集は始まりません。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("同意", style = MaterialTheme.typography.titleMedium)
                        Text("YouTubeの操作時刻と、取得できた文言を端末内に保存します。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CheckRow("端末内での収集に同意する", collection) { checked ->
                            collection = checked
                            automatic = false
                            revision = "consent-" + UUID.randomUUID()
                            synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("collection_consent", checked)
                                .putBoolean("automatic_consent", false).putString("consent_revision", revision).putLong("collection_since", System.currentTimeMillis()).commit() }
                            CloudRepository.cancel()
                            saveConsent()
                        }
                        Text("許可した操作とゴールをクラウドのAIへ送ります。同意より前の記録は送りません。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CheckRow("クラウド解析への送信に同意する", cloud) { checked ->
                            cloud = checked
                            automatic = false
                            revision = "consent-" + UUID.randomUUID()
                            synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", checked).putBoolean("automatic_consent", false).putString("consent_revision", revision).commit() }
                            CloudRepository.cancel()
                            saveConsent()
                        }
                        Text("有効と無効は、ホームのトグルで切り替えます。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("アカウントとデータ", style = MaterialTheme.typography.titleMedium)
                        Text("DopaGuardのログインです。Google Cloud管理アカウントとは別です。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            OutlinedButton(enabled = !cloudBusy && !saving, onClick = { confirmReset = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("データを削除して初期化") }
                            OutlinedButton(enabled = !cloudBusy, onClick = {
                                cloud = false; automatic = false
                                revision = "consent-" + UUID.randomUUID()
                                synchronized(PrivacyGate.lock) { prefs.edit().putBoolean("cloud_consent", false).putBoolean("automatic_consent", false).putString("consent_revision", revision).commit(); CloudRepository.cancel() }
                                saveConsent(); auth.signOut()
                            }) { Text("ログアウト") }
                        }
                        OutlinedButton(onClick = { revoke(false) }) { Text("収集とクラウド同意を撤回") }
                        Text("記録ごと消す場合は「データを削除して初期化」です。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
                if (tab == 1) {
                Text("端末に記録した行動", style = MaterialTheme.typography.titleLarge)
                Text("YouTubeでの操作を時系列で確認できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LaunchedEffect(Unit) {
                    while (true) {
                        refreshHistory()
                        kotlinx.coroutines.delay(3_000)
                    }
                }
                OutlinedButton(onClick = { refreshHistory() }) { Text("履歴を更新") }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(summary, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        }
        }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
            title = { Text("データを削除して初期化しますか？") },
            text = { Text("このアカウントのクラウド記録と、この端末の使い方・操作履歴・分析・同意設定を削除し、最初の設定に戻ります。元に戻せません。ログインアカウントは残ります。") },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
            confirmButton = { TextButton(onClick = {
                confirmReset = false
                val resetUid = auth.currentUser?.uid
                if (resetUid == null) { error = "削除するアカウントにログインしてください。" }
                else {
                    resetting = true; cloudBusy = true; saving = true; error = null
                    collection = false; cloud = false; automatic = false
                    synchronized(PrivacyGate.lock) {
                        prefs.edit().putBoolean("collection_consent", false).putBoolean("cloud_consent", false)
                            .putBoolean("automatic_consent", false).commit()
                        CloudRepository.cancel()
                    }
                    CloudRepository.io.execute {
                        val result = runCatching { CloudRepository(this@MainActivity).deleteCloud(resetUid) }
                        if (result.isFailure) runOnUiThread {
                            resetting = false; cloudBusy = false; saving = false
                            error = "送信と収集は停止しました。クラウド削除に失敗したため初期化していません。ログインを維持して再実行してください。"
                        } else PrivacyGate.io.execute {
                            val local = runCatching {
                                synchronized(PrivacyGate.lock) {
                                    require(auth.currentUser?.uid == resetUid)
                                    GuardDatabase.get(this@MainActivity).dao().deleteAll()
                                    require(getSharedPreferences("cloud_queue", MODE_PRIVATE).edit().clear().commit())
                                    deleteFile("spike-events.jsonl")
                                    require(prefs.edit().clear().commit())
                                }
                            }
                            runOnUiThread {
                                resetting = false; cloudBusy = false; saving = false
                                if (local.isSuccess) {
                                    confirmed = false; editingGoal = false; goal = ""; tab = 0
                                    summary = "まだ取得状態を確認していません。"
                                    revision = "consent-v1"
                                } else error = "クラウドデータは削除しました。端末の初期化が完了していないため、再実行してください。"
                            }
                        }
                    }
                }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("削除して初期化") } })
        if (resetting) AlertDialog(onDismissRequest = {}, title = { Text("データを削除しています") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("完了すると初回設定に戻ります。") } }, confirmButton = {})
    }

    @Composable private fun PermissionLine(title: String, ok: Boolean, open: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ok, onCheckedChange = null, colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.error,
                disabledCheckedColor = MaterialTheme.colorScheme.primary,
                disabledUncheckedColor = MaterialTheme.colorScheme.error,
                disabledIndeterminateColor = MaterialTheme.colorScheme.primary
            ))
            Text(title, style = MaterialTheme.typography.bodyLarge)
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
