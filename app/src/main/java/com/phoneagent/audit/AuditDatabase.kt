package com.phoneagent.audit

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.phoneagent.safety.RiskLevel
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Insert
    suspend fun insert(event: AuditEvent): Long

    /** Newest first, which is the order the History screen shows. */
    @Query("SELECT * FROM audit_events ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<AuditEvent>

    @Query("DELETE FROM audit_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM audit_events")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM audit_events")
    suspend fun count(): Int
}

/** Stores [RiskLevel] and [AuditDecision] as their names, so a reordered enum cannot corrupt rows. */
class AuditConverters {
    @TypeConverter
    fun riskToString(value: RiskLevel): String = value.name

    @TypeConverter
    fun stringToRisk(value: String): RiskLevel =
        runCatching { RiskLevel.valueOf(value) }.getOrDefault(RiskLevel.LOW)

    @TypeConverter
    fun decisionToString(value: AuditDecision): String = value.name

    @TypeConverter
    fun stringToDecision(value: String): AuditDecision =
        runCatching { AuditDecision.valueOf(value) }.getOrDefault(AuditDecision.ALLOWED)
}

@Database(entities = [AuditEvent::class], version = 1, exportSchema = false)
@TypeConverters(AuditConverters::class)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao

    companion object {
        fun build(context: Context): AuditDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AuditDatabase::class.java,
                "phoneagent_audit.db",
            ).build()
    }
}
