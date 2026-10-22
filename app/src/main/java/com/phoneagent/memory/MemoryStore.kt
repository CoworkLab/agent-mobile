package com.phoneagent.memory

/**
 * The three layers of memory the design asks for, in one place.
 *
 *  - profile: small facts about the person, such as their language or a contact nickname;
 *  - episodic: what happened on past tasks, which gives the model context and lets the person
 *    see what the agent has been doing;
 *  - procedural: the skills, held in a [SkillLibrary] and kept separate because they are not
 *    personal data and must survive "forget everything".
 *
 * Everything written here goes through [SensitiveDataFilter] first. That is the one rule that
 * cannot be relaxed: a memory store that can hold a password is a liability, and the person
 * cannot be expected to audit it.
 *
 * This is the pure-Kotlin core. The Room-backed store keeps the same rules; this one exists so
 * the rules can be tested without a device.
 */
class MemoryStore(
    maxEpisodes: Int = DEFAULT_MAX_EPISODES,
    val skills: SkillLibrary = SkillLibrary(),
) {
    private val facts = linkedMapOf<String, String>()
    private val episodes = mutableListOf<Episode>()
    private val maxEpisodes = maxEpisodes
    private var nextEpisodeId = 1L

    data class Episode(
        val id: Long,
        val timestampMs: Long,
        val goal: String,
        val summary: String,
        val stepCount: Int,
        val succeeded: Boolean,
    )

    // ---- profile ------------------------------------------------------------------------

    /** Stores a fact, with any secret in it removed rather than refused. */
    fun rememberFact(key: String, value: String) {
        if (key.isBlank()) return
        facts[key.trim()] = SensitiveDataFilter.redact(value).text
    }

    fun fact(key: String): String? = facts[key.trim()]

    fun forgetFact(key: String) {
        facts.remove(key.trim())
    }

    fun facts(): Map<String, String> = facts.toMap()

    // ---- episodic -----------------------------------------------------------------------

    fun recordEpisode(
        goal: String,
        summary: String,
        stepCount: Int,
        succeeded: Boolean,
        timestampMs: Long,
    ): Episode {
        val episode = Episode(
            id = nextEpisodeId++,
            timestampMs = timestampMs,
            goal = SensitiveDataFilter.redact(goal).text,
            summary = SensitiveDataFilter.redact(summary).text,
            stepCount = stepCount,
            succeeded = succeeded,
        )

        // Newest first, so the newest is dropped last when the cap is reached.
        episodes.add(0, episode)
        while (episodes.size > maxEpisodes) {
            episodes.removeAt(episodes.size - 1)
        }
        return episode
    }

    fun episodes(): List<Episode> = episodes.toList()

    fun forgetEpisode(id: Long) {
        episodes.removeAll { it.id == id }
    }

    /**
     * Finds past episodes that mention a word.
     *
     * A blank query returns nothing rather than everything: this is a search box, and a
     * person who has typed nothing has not asked for the whole history.
     */
    fun recall(query: String, limit: Int = DEFAULT_RECALL_LIMIT): List<Episode> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        return episodes
            .filter {
                it.goal.lowercase().contains(needle) || it.summary.lowercase().contains(needle)
            }
            .take(limit)
    }

    // ---- everything ---------------------------------------------------------------------

    /** Clears the personal layers. The skills stay, because they are not about the person. */
    fun forgetAll() {
        facts.clear()
        episodes.clear()
    }

    private companion object {
        const val DEFAULT_MAX_EPISODES = 500
        const val DEFAULT_RECALL_LIMIT = 10
    }
}
