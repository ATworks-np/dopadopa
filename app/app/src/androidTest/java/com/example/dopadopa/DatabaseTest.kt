package com.example.dopadopa

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.data.*
import org.junit.Assert.*
import org.junit.Test

class DatabaseTest {
    @Test fun persistenceOrderingAndDeletion() {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, GuardDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insertSession(Session("s1", "generic", "org.example", 1))
            fun event(id: String, time: Long) = InteractionEvent(id, "s1", "generic", "org.example", time,
                "CLICK", null, "missing", "none", "unavailable", "real", "v1")
            dao.insertEvent(event("b", 2)); dao.insertEvent(event("a", 2)); dao.insertEvent(event("c", 1))
            dao.insertEvent(event("a", 2))
            assertEquals(listOf("c", "a", "b"), dao.timeline("s1").map { it.eventId })
            dao.deleteAll()
            assertTrue(dao.timeline("s1").isEmpty())
            assertTrue(dao.sessions().isEmpty())
        } finally { db.close() }
    }
}
