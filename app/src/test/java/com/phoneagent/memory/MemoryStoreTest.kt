package com.phoneagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory store is where the three layers the design asks for live: profile facts,
 * episodes, and the skills index. The one rule that overrides everything is that a secret is
 * never written, so most of these tests are about that.
 */
class MemoryStoreTest {

    private val store = MemoryStore()

    // ---- profile ------------------------------------------------------------------------

    @Test
    fun `a profile fact is remembered`() {
        store.rememberFact("language", "Hindi")

        assertEquals("Hindi", store.fact("language"))
    }

    @Test
    fun `a later fact replaces an earlier one`() {
        store.rememberFact("language", "Hindi")
        store.rememberFact("language", "English")

        assertEquals("English", store.fact("language"))
    }

    @Test
    fun `an unknown fact is null rather than an error`() {
        assertEquals(null, store.fact("never set"))
    }

    @Test
    fun `a fact that looks like a secret is refused`() {
        store.rememberFact("note", "password: hunter2secret")

        val stored = store.fact("note").orEmpty()
        assertFalse("a secret must never be stored", stored.contains("hunter2secret"))
        assertTrue(stored.contains(SensitiveDataFilter.REDACTED))
    }

    @Test
    fun `a card number in a fact is refused`() {
        store.rememberFact("card", "4111 1111 1111 1111")

        assertFalse(store.fact("card").orEmpty().contains("4111"))
    }

    @Test
    fun `forget removes a fact`() {
        store.rememberFact("language", "Hindi")
        store.forgetFact("language")

        assertEquals(null, store.fact("language"))
    }

    // ---- episodes -----------------------------------------------------------------------

    @Test
    fun `a finished task becomes an episode`() {
        store.recordEpisode(
            goal = "Send hello to Amit",
            summary = "Opened WhatsApp and sent the message",
            stepCount = 6,
            succeeded = true,
            timestampMs = 1000,
        )

        val episode = store.episodes().single()
        assertEquals("Send hello to Amit", episode.goal)
        assertEquals(6, episode.stepCount)
        assertTrue(episode.succeeded)
    }

    @Test
    fun `episodes are newest first, which is how they are shown`() {
        store.recordEpisode("first", "a", 3, true, timestampMs = 1000)
        store.recordEpisode("second", "b", 4, true, timestampMs = 2000)

        assertEquals("second", store.episodes().first().goal)
    }

    @Test
    fun `a secret in an episode summary is removed before it is stored`() {
        store.recordEpisode(
            goal = "Log in",
            summary = "Typed password: hunter2secret and pressed continue",
            stepCount = 5,
            succeeded = true,
            timestampMs = 1000,
        )

        val episode = store.episodes().single()
        assertFalse(episode.summary.contains("hunter2secret"))
        assertTrue(episode.summary.contains(SensitiveDataFilter.REDACTED))
    }

    @Test
    fun `a secret in a goal is removed too, because a goal can contain one`() {
        store.recordEpisode(
            goal = "Set my pin to 4821",
            summary = "Changed the pin",
            stepCount = 5,
            succeeded = true,
            timestampMs = 1000,
        )

        assertFalse(store.episodes().single().goal.contains("4821"))
    }

    @Test
    fun `recall finds an episode by a word in the goal`() {
        store.recordEpisode("Send hello to Amit", "done", 6, true, timestampMs = 1000)
        store.recordEpisode("Set an alarm for 7", "done", 5, true, timestampMs = 2000)

        val found = store.recall("alarm")

        assertEquals(1, found.size)
        assertEquals("Set an alarm for 7", found.single().goal)
    }

    @Test
    fun `recall searches the summary as well as the goal`() {
        store.recordEpisode("A task", "opened the clock app", 5, true, timestampMs = 1000)

        assertEquals(1, store.recall("clock").size)
    }

    @Test
    fun `recall is case insensitive`() {
        store.recordEpisode("Send hello to Amit", "done", 6, true, timestampMs = 1000)

        assertEquals(1, store.recall("AMIT").size)
    }

    @Test
    fun `recall with a blank query returns nothing rather than everything`() {
        store.recordEpisode("A task", "done", 5, true, timestampMs = 1000)

        assertTrue("an empty search must not dump the whole history", store.recall("").isEmpty())
        assertTrue(store.recall("   ").isEmpty())
    }

    @Test
    fun `recall returns the most recent match first`() {
        store.recordEpisode("Send to Amit", "first", 5, true, timestampMs = 1000)
        store.recordEpisode("Send to Amit again", "second", 5, true, timestampMs = 2000)

        assertEquals("Send to Amit again", store.recall("send").first().goal)
    }

    @Test
    fun `recall can be limited`() {
        repeat(10) { index ->
            store.recordEpisode("task $index", "done", 5, true, timestampMs = index.toLong())
        }

        assertEquals(3, store.recall("task", limit = 3).size)
    }

    @Test
    fun `old episodes are dropped past the cap, so memory cannot grow without bound`() {
        val small = MemoryStore(maxEpisodes = 3)
        repeat(10) { index ->
            small.recordEpisode("task $index", "done", 5, true, timestampMs = index.toLong())
        }

        assertEquals(3, small.episodes().size)
        assertEquals("task 9", small.episodes().first().goal)
    }

    @Test
    fun `a failed task is still remembered, because it is useful context`() {
        store.recordEpisode("Try the impossible", "could not", 3, succeeded = false, timestampMs = 1000)

        assertFalse(store.episodes().single().succeeded)
    }

    // ---- forget all ---------------------------------------------------------------------

    @Test
    fun `forget all clears facts and episodes`() {
        store.rememberFact("language", "Hindi")
        store.recordEpisode("a task", "done", 5, true, timestampMs = 1000)

        store.forgetAll()

        assertEquals(null, store.fact("language"))
        assertTrue(store.episodes().isEmpty())
    }

    @Test
    fun `forget all does not touch the skills, which are not personal data`() {
        val library = SkillLibrary(
            listOf(Skill(name = "Bundled", description = "d", body = "b")),
        )
        val withSkills = MemoryStore(skills = library)
        withSkills.rememberFact("language", "Hindi")

        withSkills.forgetAll()

        assertEquals(1, withSkills.skills.all().size)
    }

    @Test
    fun `an episode can be deleted on its own`() {
        store.recordEpisode("first", "a", 3, true, timestampMs = 1000)
        store.recordEpisode("second", "b", 4, true, timestampMs = 2000)

        store.forgetEpisode(store.episodes().first().id)

        assertEquals(1, store.episodes().size)
        assertEquals("first", store.episodes().single().goal)
    }

    @Test
    fun `facts are listed for the memory screen`() {
        store.rememberFact("language", "Hindi")
        store.rememberFact("city", "Pune")

        assertEquals(2, store.facts().size)
    }
}
