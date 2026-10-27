package com.mobileagent.app.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SkillDao {

    @Query("SELECT * FROM skills ORDER BY source ASC, useCount DESC, createdAt ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY useCount DESC, createdAt ASC")
    suspend fun getEnabled(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillEntity?

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM skills WHERE source = 'builtin'")
    suspend fun builtinCount(): Int

    @Insert
    suspend fun insert(skill: SkillEntity): Long

    @Query("UPDATE skills SET triggers = :triggers, steps = :steps, notes = :notes WHERE name = :name")
    suspend fun updateContent(name: String, triggers: String, steps: String, notes: String): Int

    @Query("UPDATE skills SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE skills SET useCount = useCount + 1 WHERE name = :name")
    suspend fun bumpUseCount(name: String)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM skills")
    suspend fun deleteAll()
}
