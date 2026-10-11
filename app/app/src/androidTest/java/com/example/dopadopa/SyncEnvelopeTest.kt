package com.example.dopadopa

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.data.*
import org.junit.Assert.*
import org.junit.Test

class SyncEnvelopeTest {
    @Test fun retrySnapshotIsImmutableOwnerBoundAndExpiresWithSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GuardDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insertSession(Session("sample", "youtube", "com.google.android.youtube", 1))
            dao.freezeEnvelope(SyncEnvelope("alice:sample", "sample", "v1", "original fictional payload"))
            // A later candidate must not overwrite an uncertain first transmission.
            dao.freezeEnvelope(SyncEnvelope("alice:sample", "sample", "v1", "later event and changed goal"))
            assertEquals("original fictional payload", dao.envelope("alice:sample")!!.json)
            assertNull(dao.envelope("bob:sample"))
            dao.expireSessions(2)
            assertNull(dao.envelope("alice:sample"))
        } finally { db.close() }
    }
    @Test fun localDeleteRemovesPendingSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GuardDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insertSession(Session("sample", "youtube", "com.google.android.youtube", 1))
            dao.freezeEnvelope(SyncEnvelope("alice:sample", "sample", "v1", "fictional"))
            dao.deleteAll()
            assertNull(dao.envelope("alice:sample"))
        } finally { db.close() }
    }
}
