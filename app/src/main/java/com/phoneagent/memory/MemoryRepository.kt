package com.phoneagent.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the rest of the app uses to read and write memory.
 *
 * The interface exists so the storage behind it can change without touching the screens or the
 * orchestrator. [InMemoryMemoryRepository] is what runs today; a Room-backed implementation
 * keeps the same rules and the same signature, which is why nothing above this line needs to
 * know which one is in use.
 */
interface MemoryRepository {

    /** Everything remembered about the person, for the Memory screen. */
    fun observe(): Flow<MemorySnapshot>

    /** The skills, which are listed separately because a wipe does not touch them. */
    fun observeSkills(): Flow<List<Skill>>

    suspend fun rememberFact(key: String, value: String)

    suspend fun forgetFact(key: String)

    suspend fun recordEpisode(
        goal: String,
        summary: String,
        stepCount: Int,
        succeeded: Boolean,
        timestampMs: Long,
    )

    suspend fun forgetEpisode(id: Long)

    /** Past episodes that mention a word, for giving the model context. */
    suspend fun recall(query: String, limit: Int = 10): List<MemoryStore.Episode>

    /** Deletes the personal layers. Skills are kept. */
    suspend fun forgetAll()
}

/** A read-only view of what is remembered, for the Memory screen. */
data class MemorySnapshot(
    val facts: Map<String, String> = emptyMap(),
    val episodes: List<MemoryStore.Episode> = emptyList(),
    val skills: List<Skill> = emptyList(),
) {
    /** True when there is something personal to forget. */
    val hasAnythingPersonal: Boolean get() = facts.isNotEmpty() || episodes.isNotEmpty()
}

/**
 * A memory repository over the pure-Kotlin [MemoryStore].
 *
 * Every write goes through the store, so the secret filter applies to everything that reaches
 * storage no matter which screen asked for it.
 */
class InMemoryMemoryRepository(
    private val store: MemoryStore = MemoryStore(),
) : MemoryRepository {

    private val version = MutableStateFlow(0L)

    override fun observe(): Flow<MemorySnapshot> = version.asStateFlow().map { snapshot() }

    override fun observeSkills(): Flow<List<Skill>> =
        version.asStateFlow().map { store.skills.all() }

    override suspend fun rememberFact(key: String, value: String) {
        store.rememberFact(key, value)
        bump()
    }

    override suspend fun forgetFact(key: String) {
        store.forgetFact(key)
        bump()
    }

    override suspend fun recordEpisode(
        goal: String,
        summary: String,
        stepCount: Int,
        succeeded: Boolean,
        timestampMs: Long,
    ) {
        store.recordEpisode(goal, summary, stepCount, succeeded, timestampMs)
        bump()
    }

    override suspend fun forgetEpisode(id: Long) {
        store.forgetEpisode(id)
        bump()
    }

    override suspend fun recall(query: String, limit: Int): List<MemoryStore.Episode> =
        store.recall(query, limit)

    override suspend fun forgetAll() {
        store.forgetAll()
        bump()
    }

    private fun snapshot() = MemorySnapshot(
        facts = store.facts(),
        episodes = store.episodes(),
        skills = store.skills.all(),
    )

    private fun bump() {
        version.value = version.value + 1
    }
}
