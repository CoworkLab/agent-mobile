package com.mobileagent.app.data.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reusable SKILL: a named multi-step recipe the agent follows whenever the user's
 * request matches one of its trigger phrases. Skills are the "pre-trained patterns"
 * layer - bundled ones ship in the APK, learned ones are saved at runtime (by the
 * model via skill_save) - and all of them ride along in the export/import ZIP.
 */
@Entity(tableName = "skills", indices = [Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Stable snake_case identifier, e.g. "close_app_from_recents". */
    val name: String,
    /** JSON array of trigger phrases (Hinglish + English). */
    val triggers: String,
    /** JSON array of ordered step instructions (tool calls with arguments). */
    val steps: String,
    /** When/how to use it, caveats. */
    val notes: String = "",
    /** builtin (shipped with the APK) or learned (taught at runtime). */
    val source: String = SOURCE_LEARNED,
    val enabled: Boolean = true,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_LEARNED = "learned"
    }
}
