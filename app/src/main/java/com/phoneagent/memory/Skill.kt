package com.phoneagent.memory

/**
 * A skill: a short procedure the agent can follow, written by a person or drafted by the
 * model and approved by a person.
 *
 * Skills describe *patterns*, never coordinates. "Dismiss a permission dialog by tapping the
 * option that is not Allow" survives a screen change and an app update; "tap at 540,1820"
 * does not, and would be actively dangerous on a different phone.
 */
data class Skill(
    val name: String,
    val description: String,
    val apps: List<String> = emptyList(),
    val version: Int = 1,
    val body: String,
    val status: SkillStatus = SkillStatus.APPROVED,
) {
    val id: String get() = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The one line the prompt carries. The body is never sent until it is asked for. */
    fun indexLine(): String {
        val where = if (apps.isEmpty()) "" else " (${apps.joinToString(", ")})"
        return "- $name: $description$where"
    }
}

enum class SkillStatus {
    /** Written by a person, or a draft a person approved. Usable. */
    APPROVED,

    /**
     * Drafted by the model, not yet reviewed.
     *
     * A draft is never loaded automatically, because a procedure the model invented from one
     * successful run may be wrong in a way that is invisible on the next task. It becomes
     * usable only when a person approves it.
     */
    DRAFT,

    /** Kept for the record but never offered. */
    ARCHIVED,
    ;

    val isUsable: Boolean get() = this == APPROVED
}

/**
 * Reads and writes the skill file format.
 *
 * The format is markdown with a small front matter block, because that is what a person can
 * read and edit by hand, and what an LLM can produce reliably.
 *
 * Parsing is deliberately forgiving: a missing field gets a sensible default rather than an
 * error, because a malformed skill should degrade to "not very useful" rather than break the
 * agent. The one exception is the body, which cannot be defaulted, so a file with no body
 * after the front matter is rejected.
 */
object SkillParser {

    private const val FENCE = "---"

    data class Parsed(val skill: Skill?, val error: String?)

    fun parse(text: String): Parsed {
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != FENCE) {
            return Parsed(null, "A skill must start with a --- front matter block.")
        }

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == FENCE }
        if (endIndex < 0) {
            return Parsed(null, "The front matter block is not closed with ---.")
        }

        val headerLines = lines.subList(1, endIndex + 1)
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()

        if (body.isBlank()) {
            return Parsed(null, "A skill must have a body after the front matter.")
        }

        val fields = headerLines
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to
                    line.substring(separator + 1).trim()
            }
            .toMap()

        val name = fields["name"]?.takeIf { it.isNotBlank() }
            ?: return Parsed(null, "A skill must have a name.")

        return Parsed(
            Skill(
                name = name,
                description = fields["description"].orEmpty(),
                apps = fields["apps"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                version = fields["version"]?.toIntOrNull() ?: 1,
                body = body,
                status = when (fields["status"]?.lowercase()) {
                    "draft" -> SkillStatus.DRAFT
                    "archived" -> SkillStatus.ARCHIVED
                    else -> SkillStatus.APPROVED
                },
            ),
            null,
        )
    }

    fun render(skill: Skill): String = buildString {
        appendLine(FENCE)
        appendLine("name: ${skill.name}")
        appendLine("description: ${skill.description}")
        if (skill.apps.isNotEmpty()) appendLine("apps: ${skill.apps.joinToString(", ")}")
        appendLine("version: ${skill.version}")
        if (skill.status != SkillStatus.APPROVED) {
            appendLine("status: ${skill.status.name.lowercase()}")
        }
        appendLine(FENCE)
        appendLine()
        appendLine(skill.body.trim())
    }
}

/**
 * The skills the agent can use, and the rules for choosing them.
 *
 * This holds the whole set in memory. The set is small by design: a bundled handful plus the
 * ones a person has approved. A large library would make the index itself expensive and would
 * make the model's choice worse.
 */
class SkillLibrary(initial: List<Skill> = emptyList()) {

    private val skills = linkedMapOf<String, Skill>()

    init {
        initial.forEach { put(it) }
    }

    fun put(skill: Skill) {
        skills[skill.id] = skill
    }

    fun remove(id: String) {
        skills.remove(id)
    }

    fun all(): List<Skill> = skills.values.toList()

    /** The skills that may be used or offered. Drafts are excluded. */
    fun usable(): List<Skill> = skills.values.filter { it.status.isUsable }

    fun drafts(): List<Skill> = skills.values.filter { it.status == SkillStatus.DRAFT }

    fun get(id: String): Skill? = skills[id]

    /**
     * The index that goes into the prompt.
     *
     * Only the name, the description and the apps, never the body. This is the whole point of
     * the split: a library of twenty skills costs twenty lines rather than twenty documents.
     * Drafts are excluded, so an unreviewed procedure can never influence a run.
     */
    fun indexFor(packageName: String?): String {
        val candidates = usable().filter { it.appliesTo(packageName) }
        if (candidates.isEmpty()) return ""
        return buildString {
            appendLine("You have these skills. Call load_skill to read one in full.")
            candidates.forEach { appendLine(it.indexLine()) }
        }.trimEnd()
    }

    /** Everything usable, for a screen that lists what the agent knows. */
    fun index(): String {
        val candidates = usable()
        if (candidates.isEmpty()) return ""
        return candidates.joinToString("\n") { it.indexLine() }
    }

    /** The full text of a skill, or a message explaining why it cannot be read. */
    fun read(id: String): String {
        val skill = skills[id]
            ?: return "There is no skill called \"$id\"."
        if (!skill.status.isUsable) {
            return "\"${skill.name}\" has not been approved yet, so it cannot be used."
        }
        return buildString {
            appendLine("# ${skill.name}")
            appendLine()
            appendLine(skill.body)
        }.trimEnd()
    }

    private fun Skill.appliesTo(packageName: String?): Boolean {
        if (apps.isEmpty()) return true
        val pkg = packageName ?: return false
        return apps.any { pattern ->
            val clean = pattern.trim()
            when {
                clean.isEmpty() -> false
                clean.endsWith("*") -> pkg.startsWith(clean.dropLast(1))
                else -> pkg.equals(clean, ignoreCase = true) || pkg.contains(clean, ignoreCase = true)
            }
        }
    }
}

/**
 * Decides whether a finished task is worth turning into a draft skill.
 *
 * The rules come from the design: at least four steps, and the task has to have actually
 * finished. A short task teaches nothing general, and a failed one would teach the wrong
 * thing. Requiring several steps is what makes the draft worth a person's attention.
 */
object SkillDraftPolicy {

    const val MIN_STEPS = 4

    data class Candidate(
        val worthDrafting: Boolean,
        val reason: String,
    )

    fun assess(stepCount: Int, succeeded: Boolean, planSummary: String?): Candidate = when {
        !succeeded -> Candidate(false, "The task did not finish, so there is nothing to learn.")
        stepCount < MIN_STEPS -> Candidate(
            false,
            "The task took $stepCount steps, which is too few to be worth a skill.",
        )
        planSummary.isNullOrBlank() -> Candidate(false, "There is nothing to describe.")
        else -> Candidate(true, "The task took $stepCount steps and finished.")
    }

    /**
     * The instruction sent to the model when a draft is wanted.
     *
     * It asks for patterns rather than coordinates, and for the front matter the parser
     * expects, so the reply can be stored without a repair step.
     */
    fun draftingPrompt(goal: String, stepSummary: String): String = buildString {
        appendLine("You just completed a task. Write a short skill that would help next time.")
        appendLine()
        appendLine("The task was: $goal")
        appendLine()
        appendLine("What you did:")
        appendLine(stepSummary)
        appendLine()
        appendLine("Write general guidance, not a recording. Never include coordinates, element")
        appendLine("ids, or anything specific to this one screen; those change. Describe how to")
        appendLine("recognise the situation and what to do about it.")
        appendLine()
        appendLine("Reply with markdown and this front matter, and nothing else:")
        appendLine()
        appendLine("---")
        appendLine("name: a short name")
        appendLine("description: one line saying when to use it")
        appendLine("apps: comma separated package names, or leave empty")
        appendLine("version: 1")
        appendLine("status: draft")
        appendLine("---")
        appendLine()
        appendLine("Then three to six short steps.")
    }.trimEnd()
}
