package com.example.dopadopa

import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.cloud.CloudRepository
import com.example.dopadopa.data.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/** Explicit opt-in: temporary Firebase account and fictional sample records only. */
class CloudSyncIntegrationTest {
    @Test fun consentedSampleSyncRevokeAndDeleteAgainstRealCloud() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudIntegration") == "true")
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = FirebaseAuth.getInstance()
        check(auth.currentUser == null) { "integration test requires a signed-out emulator" }
        val prefs = context.getSharedPreferences("guard", 0)
        val queue = context.getSharedPreferences("cloud_queue", 0)
        val dao = GuardDatabase.get(context).dao()
        val cloud = CloudRepository(context)
        dao.deleteAll(); prefs.edit().clear().commit(); queue.edit().clear().commit()
        try {
            Tasks.await(auth.createUserWithEmailAndPassword("android-smoke-${UUID.randomUUID()}@example.invalid",
                UUID.randomUUID().toString() + UUID.randomUUID()), 60, TimeUnit.SECONDS)
            val now = System.currentTimeMillis()
            val revision = "sample-consent"
            dao.saveGoal(UserGoal("sample-goal", "架空サンプルでAndroid開発を調査する", "youtube", now - 20_000, "cloud"))
            dao.saveConsent(ConsentRecord(revision, true, true, now - 10_000))
            dao.saveConsent(ConsentRecord("old-consent", true, false, now - 20_000))
            fun fixture(id: String, consent: String) {
                dao.insertSession(Session(id, "youtube", "com.google.android.youtube", now - 5000, now - 1000, "sample"))
                dao.insertEvent(InteractionEvent("e-$id", id, "youtube", "com.google.android.youtube", now - 4000,
                    "CLICK", "Kotlinの基本", "available", "clicked_node", "direct", "sample", consent))
                dao.insertEvent(InteractionEvent("redacted-$id", id, "youtube", "com.google.android.youtube", now - 3000,
                    "CLICK", null, "redacted", "none", "unavailable", "sample", consent))
            }
            fixture("sample-allowed", revision); fixture("sample-not-consented", "old-consent")
            prefs.edit().putString("consent_revision", revision).putBoolean("collection_consent", true)
                .putBoolean("cloud_consent", false).commit()
            cloud.sync()
            assertEquals(0, cloudSessions(auth).length())
            prefs.edit().putBoolean("cloud_consent", true).commit()
            // Freeze the batch, then fail credential transport before POST.
            val retryTokenCalls = java.util.concurrent.atomic.AtomicInteger()
            val retryCloud = CloudRepository(context) { user ->
                if (retryTokenCalls.incrementAndGet() == 2) throw java.io.IOException("fictional batch transport failure")
                Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token!!
            }
            retryCloud.sync()
            val retryKey = auth.currentUser!!.uid + ":sample-allowed"
            val frozen = dao.envelope(retryKey)!!
            assertEquals(0, cloudSessions(auth).length())
            assertTrue(queue.getLong("retry:$retryKey", 0) > System.currentTimeMillis())
            dao.insertEvent(InteractionEvent("late-sample", "sample-allowed", "youtube", "com.google.android.youtube", now - 2000,
                "CLICK", "後から保存した架空イベント", "available", "clicked_node", "direct", "sample", revision))
            repeat(500) { index ->
                dao.insertEvent(InteractionEvent("late-extra-$index", "sample-allowed", "youtube", "com.google.android.youtube", now - 2000,
                    "CLICK", null, "missing", "none", "unavailable", "sample", revision))
            }
            dao.saveGoal(UserGoal("changed-sample-goal", "再送後に変更した架空ゴール", "youtube", now, "cloud"))
            // Expedite the retry in this fixture; production retains its backoff.
            queue.edit().remove("retry:$retryKey").commit()
            cloud.sync()
            assertEquals(frozen.json, dao.envelope(retryKey)!!.json)
            val retriedTimeline = cloud.timeline("sample-allowed")
            assertEquals(2, retriedTimeline.getJSONArray("events").length())
            assertEquals(503, dao.eventCount("sample-allowed"))
            assertTrue(cloud.sync().contains("端末のみ）501"))
            val rows = cloudSessions(auth)
            assertEquals(1, rows.length())
            assertEquals("sample-allowed", rows.getJSONObject(0).getString("session_id"))
            assertEquals("sample", rows.getJSONObject(0).getString("data_origin"))
            cloud.sync() // Durable acknowledgement prevents a duplicate batch.
            assertEquals(1, cloudSessions(auth).length())
            val analysis = cloud.analyze("sample-allowed")
            assertTrue(analysis.getString("status") in setOf("completed", "insufficient_data"))
            assertEquals("sample", analysis.getJSONObject("result").getString("data_origin"))
            assertTrue(analysis.getJSONObject("result").getBoolean("user_confirmation_required"))
            assertEquals(analysis.getString("analysis_id"), dao.latestAnalysis("sample-allowed", auth.currentUser!!.uid)!!.analysisId)
            assertNull(dao.latestAnalysis("sample-allowed", "another-person"))
            val evidence = cloud.timeline("sample-allowed").getJSONArray("events")
            assertEquals("redacted", evidence.getJSONObject(1).getString("text_status"))
            assertTrue(evidence.getJSONObject(1).isNull("text"))
            val aid = analysis.getString("analysis_id")
            assertEquals(0, cloud.feedback(aid).length())
            cloud.saveFeedback(aid, "incorrect", "サンプル学習動画を探していた")
            cloud.saveFeedback(aid, "unknown", null)
            val feedback = CloudRepository(context).feedback(aid)
            assertEquals(2, feedback.length())
            val answers = (0 until feedback.length()).map { feedback.getJSONObject(it) }
            assertTrue(answers.any { it.getString("verdict") == "incorrect" && it.getString("corrected_intent") == "サンプル学習動画を探していた" })
            assertTrue(answers.any { it.getString("verdict") == "unknown" && it.isNull("corrected_intent") })
            assertEquals(evidence.toString(), cloud.timeline("sample-allowed").getJSONArray("events").toString())
            fixture("sample-after-revoke", revision)
            synchronized(PrivacyGate.lock) {
                prefs.edit().putBoolean("cloud_consent", false).commit()
                CloudRepository.cancel()
            }
            cloud.sync()
            assertEquals(1, cloudSessions(auth).length())
            cloud.deleteCloud()
            assertEquals(0, cloudSessions(auth).length())
            assertNull(dao.latestAnalysis("sample-allowed", auth.currentUser!!.uid))

            // Hold the batch credential fetch AFTER fetching its generation.
            // Deletion occurs before the held batch first reaches Cloud Run.
            prefs.edit().putBoolean("cloud_consent", true).commit()
            val batchReady = CountDownLatch(1)
            val batchRelease = CountDownLatch(1)
            val tokenCalls = java.util.concurrent.atomic.AtomicInteger()
            val delayedBatchCloud = CloudRepository(context) { user ->
                if (tokenCalls.incrementAndGet() == 2) {
                    batchReady.countDown()
                    check(batchRelease.await(30, TimeUnit.SECONDS))
                }
                Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token!!
            }
            val delayedBatch = CloudRepository.io.submit<String> { delayedBatchCloud.sync() }
            try {
                assertTrue(batchReady.await(15, TimeUnit.SECONDS))
                cloud.deleteCloud()
            } finally { batchRelease.countDown() }
            delayedBatch.get(30, TimeUnit.SECONDS)
            assertEquals(0, cloudSessions(auth).length())

            // Hold a real credential fetch before transmission, then revoke.
            // The collector executor must remain usable during this network wait.
            fixture("sample-race", revision)
            prefs.edit().putBoolean("cloud_consent", true).commit()
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waitingCloud = CloudRepository(context) { user ->
                ready.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token!!
            }
            val pending = CloudRepository.io.submit<String> { waitingCloud.sync() }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                PrivacyGate.io.submit { fixture("sample-collector-progress", revision) }.get(5, TimeUnit.SECONDS)
                assertEquals(2, dao.timeline("sample-collector-progress").size)
                synchronized(PrivacyGate.lock) {
                    prefs.edit().putBoolean("cloud_consent", false).commit()
                    CloudRepository.cancel()
                }
            } finally { release.countDown() }
            pending.get(20, TimeUnit.SECONDS)
            assertEquals(0, cloudSessions(auth).length())

            prefs.edit().putBoolean("cloud_consent", true).commit()
            val failingCloud = CloudRepository(context) { throw java.io.IOException("simulated credential transport failure") }
            CloudRepository.io.submit<String> { failingCloud.sync() }.get(10, TimeUnit.SECONDS)
            PrivacyGate.io.submit { fixture("sample-after-transport-failure", revision) }.get(5, TimeUnit.SECONDS)
            assertEquals(2, dao.timeline("sample-after-transport-failure").size)
            assertTrue(prefs.getBoolean("collection_consent", false))
            assertEquals(0, cloudSessions(auth).length())
        } finally {
            // Do not silently pass when remote cleanup fails.
            try {
                if (auth.currentUser != null) {
                    cloud.deleteCloud()
                    Tasks.await(auth.currentUser!!.delete(), 30, TimeUnit.SECONDS)
                }
            } finally {
                auth.signOut(); CloudRepository.cancel()
                dao.deleteAll(); prefs.edit().clear().commit(); queue.edit().clear().commit()
            }
        }
    }

    private fun cloudSessions(auth: FirebaseAuth): JSONArray {
        val token = Tasks.await(auth.currentUser!!.getIdToken(false), 15, TimeUnit.SECONDS).token!!
        val connection = URL(CloudRepository.BASE + "/v1/sessions").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            assertEquals(200, connection.responseCode)
            return JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
