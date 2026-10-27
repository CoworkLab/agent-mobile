package com.mobileagent.app.data.backup

import android.content.Context
import android.util.Log
import com.mobileagent.app.data.memory.MemoryDatabase
import com.mobileagent.app.data.memory.SkillEntity
import com.mobileagent.app.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Single-ZIP brain backup: memories + skills + non-secret preferences + chat history + schedules.
 * Export before uninstalling, import after reinstalling - no re-teaching anything.
 * The Gemini API key and the owner-voice biometric profile are deliberately NEVER exported.
 */
class BackupManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val database: MemoryDatabase
) {

    companion object {
        private const val TAG = "BackupManager"
        const val MANIFEST_NAME = "manifest.json"
        const val MEMORIES_NAME = "memories.json"
        const val SKILLS_NAME = "skills.json"
        const val PREFS_NAME = "preferences.json"
        const val CHAT_NAME = "chat_history.json"
        const val SCHEDULES_NAME = "schedules.json"
        const val FORMAT_VERSION = 1
    }

    /** Writes everything into the SAF-provided output stream. Returns the counts per section. */
    suspend fun exportZip(out: OutputStream): JSONObject = withContext(Dispatchers.IO) {
        val dao = database.memoryDao()
        val skillDao = database.skillDao()
        val memories = JSONArray()
        dao.getAllMemories().forEach { m ->
            memories.put(
                JSONObject()
                    .put("category", m.category)
                    .put("key", m.key)
                    .put("value", m.value)
                    .put("rawText", m.rawText)
                    .put("timestamp", m.timestamp)
            )
        }
        val skills = JSONArray()
        skillDao.getAll().forEach { s ->
            skills.put(
                JSONObject()
                    .put("name", s.name)
                    .put("triggers", s.triggers)
                    .put("steps", s.steps)
                    .put("notes", s.notes)
                    .put("source", s.source)
                    .put("enabled", s.enabled)
                    .put("useCount", s.useCount)
                    .put("createdAt", s.createdAt)
            )
        }
        val prefsJson = preferences.exportBackup()
        val chatJson = preferences.exportChatHistory()
        val schedulesJson = com.mobileagent.app.schedule.ScheduleStore.exportAll(context)

        val manifest = JSONObject()
            .put("app", "Mobile Agent")
            .put("format", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("memoryCount", memories.length())
            .put("skillCount", skills.length())

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME)); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(MEMORIES_NAME)); zip.write(memories.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(SKILLS_NAME)); zip.write(skills.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(PREFS_NAME)); zip.write(prefsJson.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(CHAT_NAME)); zip.write(chatJson.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(SCHEDULES_NAME)); zip.write(schedulesJson.toString().toByteArray()); zip.closeEntry()
        }
        Log.i(TAG, "Exported brain: ${memories.length()} memories, ${skills.length()} skills")
        manifest
    }

    /** Reads a SAF-provided zip and merges everything. Returns a human summary. */
    suspend fun importZip(input: InputStream): String = withContext(Dispatchers.IO) {
        val sections = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val bytes = zip.readBytes()
                sections[entry.name] = bytes
                entry = zip.nextEntry
            }
        }
        var memCount = 0
        var skillCount = 0
        var prefCount = 0

        sections[MEMORIES_NAME]?.let { bytes ->
            val arr = JSONArray(String(bytes))
            val dao = database.memoryDao()
            val existing = dao.getAllMemories()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.optString("key")
                val category = o.optString("category", "Notes")
                val raw = o.optString("rawText")
                // Upsert by (category, key): keep the newer timestamp on conflict.
                val duplicate = existing.firstOrNull { it.category == category && it.key == key }
                if (duplicate == null) {
                    dao.insertMemory(
                        com.mobileagent.app.data.memory.MemoryEntity(
                            category = category,
                            key = key,
                            value = o.optString("value"),
                            rawText = raw,
                            timestamp = o.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                    memCount++
                } else if (o.optLong("timestamp", 0L) > duplicate.timestamp) {
                    dao.updateMemory(
                        duplicate.copy(
                            value = o.optString("value"),
                            rawText = raw,
                            timestamp = o.optLong("timestamp", duplicate.timestamp)
                        )
                    )
                    memCount++
                }
            }
        }

        sections[SKILLS_NAME]?.let { bytes ->
            val arr = JSONArray(String(bytes))
            val skillDao = database.skillDao()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                val existing = skillDao.getByName(name)
                val imported = SkillEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    triggers = o.optString("triggers", "[]"),
                    steps = o.optString("steps", "[]"),
                    notes = o.optString("notes", ""),
                    source = o.optString("source", SkillEntity.SOURCE_LEARNED),
                    enabled = o.optBoolean("enabled", true),
                    useCount = o.optInt("useCount", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
                if (existing == null) {
                    skillDao.insert(imported)
                    skillCount++
                } else if (imported.createdAt > existing.createdAt) {
                    skillDao.updateContent(name, imported.triggers, imported.steps, imported.notes)
                    skillDao.setEnabled(imported.id, imported.enabled)
                    skillCount++
                }
            }
        }

        sections[PREFS_NAME]?.let { bytes ->
            prefCount = preferences.importBackup(JSONObject(String(bytes)))
        }

        sections[CHAT_NAME]?.let { bytes ->
            preferences.importChatHistory(String(bytes))
        }

        sections[SCHEDULES_NAME]?.let { bytes ->
            com.mobileagent.app.schedule.ScheduleStore.importAll(context, String(bytes))
        }

        val summary = "Imported: $memCount memories, $skillCount skills, $prefCount preferences" +
            (if (sections.containsKey(CHAT_NAME)) ", chat history" else "") +
            (if (sections.containsKey(SCHEDULES_NAME)) ", schedules" else "") + "."
        Log.i(TAG, summary)
        summary
    }
}
