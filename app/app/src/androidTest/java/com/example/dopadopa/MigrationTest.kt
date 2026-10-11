package com.example.dopadopa

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dopadopa.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MigrationTest {
    @Test fun preservesV1DataAndScopesNewAnalyses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("com.example.dopadopa.data.GuardDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO analyses (analysisId, sessionId, status, json, createdAt) VALUES ('old', 's1', 'failed', NULL, 1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, GuardDatabase::class.java, name).addMigrations(GuardDatabase.MIGRATION_1_2, GuardDatabase.MIGRATION_2_3).build()
        try {
            val dao = db.dao()
            assertNull(dao.latestAnalysis("s1", "alice"))
            dao.saveAnalysis(BehaviorAnalysis("new", "s1", "failed", null, 2, "alice"))
            assertEquals("new", dao.latestAnalysis("s1", "alice")!!.analysisId)
            assertNull(dao.latestAnalysis("s1", "bob"))
            db.openHelper.readableDatabase.query("SELECT ownerUid FROM analyses WHERE analysisId='old'").use {
                assertTrue(it.moveToFirst()); assertEquals("", it.getString(0))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
