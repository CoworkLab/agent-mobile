package com.mobileagent.app.data.skills

import android.content.Context
import android.util.Log
import com.mobileagent.app.data.memory.SkillDao
import com.mobileagent.app.data.memory.SkillEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

/**
 * Owns the SKILLS layer: pre-trained patterns bundled in assets/skills/builtin_patterns.json,
 * runtime-learned skills (model saves them via the skill_save tool), trigger matching and the
 * compact prompt section injected into the system instruction every session.
 */
class SkillManager(
    private val context: Context,
    private val dao: SkillDao,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "SkillManager"
        private const val BUILTIN_ASSET = "skills/builtin_patterns.json"
        /** Cap the prompt cost: at most this many skills are rendered into the system prompt. */
        private const val PROMPT_MAX_SKILLS = 24
        private const val PROMPT_MAX_STEPS_PER_SKILL = 10
    }

    /** Loads bundled pre-trained patterns once (idempotent). Call at app start. */
    fun ensureBuiltinsLoaded() {
        scope.launch(Dispatchers.IO) {
            try {
                if (dao.builtinCount() > 0) return@launch
                val json = context.assets.open(BUILTIN_ASSET).bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                var inserted = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name").trim()
                    if (name.isEmpty()) continue
                    if (dao.getByName(name) != null) continue
                    dao.insert(
                        SkillEntity(
                            name = name,
                            triggers = o.optJSONArray("triggers")?.toString() ?: "[]",
                            steps = o.optJSONArray("steps")?.toString() ?: "[]",
                            notes = o.optString("notes", ""),
                            source = SkillEntity.SOURCE_BUILTIN
                        )
                    )
                    inserted++
                }
                if (inserted > 0) Log.i(TAG, "Loaded $inserted pre-trained skills from assets")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load builtin skills: ${e.message}")
            }
        }
    }

    /** Upsert used by the skill_save tool: insert, or refresh content when the name exists. */
    suspend fun saveLearnedSkill(
        nameRaw: String,
        triggers: List<String>,
        steps: List<String>,
        notes: String
    ): Pair<Boolean, String> {
        val name = nameRaw.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        if (name.isBlank()) return false to "Skill name is empty after normalisation."
        val cleanTriggers = triggers.map { it.trim() }.filter { it.length >= 3 }
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanTriggers.isEmpty()) return false to "A skill needs at least one trigger phrase."
        if (cleanSteps.isEmpty()) return false to "A skill needs at least one step."

        val existing = dao.getByName(name)
        if (existing != null) {
            dao.updateContent(name, JSONArray(cleanTriggers).toString(), JSONArray(cleanSteps).toString(), notes)
            return true to if (existing.source == SkillEntity.SOURCE_BUILTIN) {
                "Updated the built-in skill '$name'."
            } else {
                "Refreshed learned skill '$name'."
            }
        }
        dao.insert(
            SkillEntity(
                name = name,
                triggers = JSONArray(cleanTriggers).toString(),
                steps = JSONArray(cleanSteps).toString(),
                notes = notes,
                source = SkillEntity.SOURCE_LEARNED
            )
        )
        return true to "Saved skill '$name' with ${cleanSteps.size} steps. It will auto-activate on its triggers."
    }

    /**
     * Matches the user's utterance against skill triggers. Scoring: full utterance contains
     * the trigger (loose, case/punctuation-insensitive). Returns the best few, bumping useCount.
     */
    suspend fun match(userText: String, limit: Int = 3): List<SkillEntity> {
        val utterance = normalize(userText)
        if (utterance.length < 4) return emptyList()
        val enabled = try { dao.getEnabled() } catch (e: Exception) { return emptyList() }
        if (enabled.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<SkillEntity, Int>>()
        for (skill in enabled) {
            var best = 0
            for (t in skill.triggerList()) {
                val trigger = normalize(t)
                if (trigger.isBlank()) continue
                var score = 0
                if (utterance == trigger) score = 100
                else if (utterance.contains(trigger)) score = 60 + minOf(30, trigger.length)
                else {
                    // Loose containment: every word of a short trigger present in the utterance.
                    val words = trigger.split(" ").filter { it.length >= 3 }
                    if (words.isNotEmpty() && words.all { utterance.contains(it) }) score = 35 + words.size
                }
                if (score > best) best = score
            }
            if (best > 0) scored.add(skill to best)
        }
        val top = scored.sortedByDescending { it.second }.take(limit).map { it.first }
        if (top.isNotEmpty()) {
            for (s in top) scope.launch(Dispatchers.IO) {
                try { dao.bumpUseCount(s.name) } catch (_: Exception) {}
            }
        }
        return top
    }

    /** Compact prompt section describing available skills; null when there are none. */
    suspend fun promptSection(): String? {
        val enabled = try { dao.getEnabled() } catch (e: Exception) { return null }
        if (enabled.isEmpty()) return null
        val sb = StringBuilder("\n[AVAILABLE SKILLS - PROVEN STEP RECIPES]:\n")
        sb.append("When the user's request clearly matches a skill's trigger, follow its steps as your starting plan (adapt to the live screen).\n")
        for (skill in enabled.take(PROMPT_MAX_SKILLS)) {
            sb.append("- ${skill.name}: when the user says like \"${skill.triggerList().take(3).joinToString("\", \"")}\".\n")
            val steps = skill.stepList().take(PROMPT_MAX_STEPS_PER_SKILL)
            steps.forEachIndexed { i, step ->
                sb.append("    ${i + 1}. $step\n")
            }
            if (skill.notes.isNotBlank()) sb.append("    Note: ${skill.notes.take(200)}\n")
        }
        return sb.toString()
    }
}

/** JSON helpers on the entity (kept out of the Room data class). */
fun SkillEntity.triggerList(): List<String> = try {
    val arr = JSONArray(triggers); (0 until arr.length()).map { arr.optString(it) }
} catch (_: Exception) { emptyList() }

fun SkillEntity.stepList(): List<String> = try {
    val arr = JSONArray(steps); (0 until arr.length()).map { arr.optString(it) }
} catch (_: Exception) { emptyList() }
