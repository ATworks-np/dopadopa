package com.example.dopadopa.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "sessions")
data class Session(@PrimaryKey val sessionId: String, val appId: String, val packageName: String,
    val startedAt: Long, val endedAt: Long? = null, val dataOrigin: String = "real")

@Entity(tableName = "events", indices = [Index(value = ["sessionId", "timestamp", "eventId"])],
    foreignKeys = [ForeignKey(entity = Session::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class InteractionEvent(@PrimaryKey val eventId: String, val sessionId: String, val appId: String,
    val packageName: String, val timestamp: Long, val eventType: String, val text: String?,
    val textStatus: String, val source: String, val observability: String, val dataOrigin: String,
    val consentRevision: String) {
    fun validate() {
        require(eventId.isNotBlank() && sessionId.isNotBlank() && appId.isNotBlank() && packageName.isNotBlank())
        require(timestamp > 0)
        require(eventType in setOf("CLICK", "SCROLL", "WINDOW_CHANGE", "APP_FOREGROUND", "APP_BACKGROUND"))
        require(textStatus in setOf("available", "missing", "redacted"))
        require(source in setOf("clicked_node", "descendant", "parent_context", "none"))
        require(observability in setOf("direct", "inferred", "unavailable"))
        require(dataOrigin in setOf("real", "sample"))
        if (textStatus == "available") require(!text.isNullOrBlank() && text.length <= 160 && source != "none" && observability != "unavailable")
        else require(text == null && source == "none" && observability == "unavailable")
        require(consentRevision.isNotBlank())
    }
}

@Entity(tableName = "goals")
data class UserGoal(@PrimaryKey val goalId: String, val description: String, val targetAppIds: String,
    val confirmedAt: Long, val consentScope: String)
@Entity(tableName = "consents")
data class ConsentRecord(@PrimaryKey val revision: String, val collection: Boolean, val cloud: Boolean, val createdAt: Long)
@Entity(tableName = "analyses", indices = [Index("sessionId")])
data class BehaviorAnalysis(@PrimaryKey val analysisId: String, val sessionId: String, val status: String,
    val json: String?, val createdAt: Long, @ColumnInfo(defaultValue = "''") val ownerUid: String = "")
@Entity(tableName = "feedback", indices = [Index("analysisId")])
data class BehaviorFeedback(@PrimaryKey val feedbackId: String, val analysisId: String, val verdict: String,
    val correctedIntent: String?, val createdAt: Long)

@Entity(tableName = "sync_envelopes", foreignKeys = [ForeignKey(entity = Session::class,
    parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")])
data class SyncEnvelope(@PrimaryKey val queueKey: String, val sessionId: String,
    val consentRevision: String, val json: String)

@Dao
interface GuardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun freezeEnvelope(value: SyncEnvelope)
    @Query("SELECT * FROM sync_envelopes WHERE queueKey = :key LIMIT 1") fun envelope(key: String): SyncEnvelope?
    @Query("DELETE FROM sync_envelopes") fun deleteEnvelopes()
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertSession(value: Session)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertEvent(value: InteractionEvent)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveGoal(value: UserGoal)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveConsent(value: ConsentRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveAnalysis(value: BehaviorAnalysis)
    @Insert fun saveFeedback(value: BehaviorFeedback)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 100") fun sessions(): List<Session>
    @Query("SELECT sessionId FROM sessions") fun sessionIds(): List<String>
    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY startedAt LIMIT 1000") fun closedSessions(): List<Session>
    @Query("SELECT * FROM consents WHERE revision = :revision LIMIT 1") fun consent(revision: String): ConsentRecord?
    @Query("SELECT * FROM analyses WHERE sessionId = :sessionId AND ownerUid = :uid ORDER BY createdAt DESC LIMIT 1")
    fun latestAnalysis(sessionId: String, uid: String): BehaviorAnalysis?
    @Query("SELECT * FROM sessions WHERE appId = :appId AND startedAt <= :at AND (endedAt IS NULL OR endedAt >= :at) ORDER BY startedAt DESC LIMIT 1")
    fun sessionAt(appId: String, at: Long): Session?
    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY timestamp, eventId LIMIT :limit")
    fun timeline(sessionId: String, limit: Int = 500): List<InteractionEvent>
    @Query("SELECT * FROM events WHERE sessionId = :sessionId AND eventId IN (:ids) ORDER BY timestamp, eventId")
    fun eventsByIds(sessionId: String, ids: List<String>): List<InteractionEvent>
    @Query("SELECT COUNT(*) FROM events WHERE sessionId = :sessionId") fun eventCount(sessionId: String): Int
    @Query("SELECT * FROM goals ORDER BY confirmedAt DESC LIMIT 1") fun latestGoal(): UserGoal?
    @Query("SELECT * FROM consents ORDER BY createdAt DESC LIMIT 1") fun latestConsent(): ConsentRecord?
    @Query("UPDATE sessions SET endedAt = :at WHERE sessionId = :id AND endedAt IS NULL") fun endSession(id: String, at: Long)
    @Query("DELETE FROM sessions WHERE startedAt < :before") fun expireSessions(before: Long)
    @Query("DELETE FROM analyses WHERE createdAt < :before") fun expireAnalyses(before: Long)
    @Query("DELETE FROM feedback WHERE createdAt < :before") fun expireFeedback(before: Long)
    @Query("DELETE FROM events") fun deleteEvents()
    @Query("DELETE FROM sessions") fun deleteSessions()
    @Query("DELETE FROM analyses") fun deleteAnalyses()
    @Query("DELETE FROM feedback") fun deleteFeedback()
    @Query("DELETE FROM goals") fun deleteGoals()
    @Query("DELETE FROM consents") fun deleteConsents()
    @Transaction fun deleteAll() { deleteEnvelopes(); deleteFeedback(); deleteAnalyses(); deleteEvents(); deleteSessions(); deleteGoals(); deleteConsents() }
}

@Database(entities = [Session::class, InteractionEvent::class, UserGoal::class, ConsentRecord::class,
    BehaviorAnalysis::class, BehaviorFeedback::class, SyncEnvelope::class], version = 3, exportSchema = true)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun dao(): GuardDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_envelopes (queueKey TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, consentRevision TEXT NOT NULL, json TEXT NOT NULL, FOREIGN KEY(sessionId) REFERENCES sessions(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_envelopes_sessionId ON sync_envelopes(sessionId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analyses ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance: GuardDatabase? = null
        fun get(context: Context): GuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GuardDatabase::class.java, "guard.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
