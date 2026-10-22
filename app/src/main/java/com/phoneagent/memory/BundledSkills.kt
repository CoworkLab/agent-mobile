package com.phoneagent.memory

import android.content.Context
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * The skills that ship with the app.
 *
 * They are patterns rather than recordings: no coordinates, no element ids, nothing tied to
 * one phone. A bundled skill that said "tap at 540,1820" would be wrong on every device but
 * the one it was written on.
 *
 * A file that fails to parse is skipped with a log line rather than failing the load. One bad
 * bundled file should cost the agent that one skill, not all of them.
 */
object BundledSkills {

    const val DIRECTORY = "skills"

    fun load(context: Context, logger: Logger): List<Skill> = try {
        context.assets.list(DIRECTORY)
            .orEmpty()
            .filter { it.endsWith(".md") }
            .sorted()
            .mapNotNull { fileName ->
                val text = context.assets.open("$DIRECTORY/$fileName")
                    .bufferedReader()
                    .use { it.readText() }
                val parsed = SkillParser.parse(text)
                if (parsed.skill == null) {
                    logger.log(LogLevel.WARN, "BundledSkills", "$fileName was skipped: ${parsed.error}")
                }
                parsed.skill
            }
    } catch (error: Throwable) {
        logger.log(LogLevel.ERROR, "BundledSkills", "Could not load the bundled skills: ${error.message}")
        emptyList()
    }
}
