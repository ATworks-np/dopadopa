package com.example.dopadopa

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.data.*
import org.junit.Assert.*
import org.junit.Test

class RetentionTest {
    @Test fun expiresContentAndQueueWithoutDroppingLiveSessionMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GuardDatabase::class.java).build()
        val queue = context.getSharedPreferences("retention-fixture", 0)
        try {
            val now = System.currentTimeMillis()
            val dao = db.dao()
            dao.insertSession(Session("old", "youtube", "com.google.android.youtube", now - 8L * 86_400_000, dataOrigin = "sample"))
            dao.insertSession(Session("live", "youtube", "com.google.android.youtube", now, dataOrigin = "sample"))
            dao.freezeEnvelope(SyncEnvelope("owner:old", "old", "r", "{}"))
            for (kind in listOf("sent", "retry", "attempt")) {
                queue.edit().putLong("$kind:owner:old", 1).putLong("$kind:owner:live", 1)
                    .putLong("$kind:owner:missing", 1).commit()
            }
            synchronized(PrivacyGate.lock) { LocalRetention.prune(dao, queue, now) }
            assertNull(dao.envelope("owner:old"))
            assertEquals(listOf("live"), dao.sessionIds())
            assertEquals(setOf("sent:owner:live", "retry:owner:live", "attempt:owner:live"), queue.all.keys)
        } finally { queue.edit().clear().commit(); db.close() }
    }
}
