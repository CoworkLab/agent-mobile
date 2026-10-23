package com.phoneagent.settings

import com.phoneagent.perception.PerceptionMode
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.util.LogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `the defaults are the ones the design asks for`() {
        val settings = AppSettings()

        assertEquals(PerceptionMode.TREE_PLUS_SCREENSHOT, settings.perceptionMode)
        assertTrue(settings.speechEnabled)
        assertEquals("en-IN", settings.languageTag)
        assertTrue(settings.confirmRiskyActions)
        assertTrue("the denylist must not ship empty", settings.deniedPackages.isNotEmpty())
    }

    @Test
    fun `a step limit below the floor is raised, because one step finishes nothing`() {
        assertEquals(
            AppSettings.MIN_STEPS,
            AppSettings(maxSteps = 1).sanitized().maxSteps,
        )
    }

    @Test
    fun `a step limit above the ceiling is lowered, because an unbounded run costs money`() {
        assertEquals(
            AppSettings.MAX_STEPS,
            AppSettings(maxSteps = 100_000).sanitized().maxSteps,
        )
    }

    @Test
    fun `a negative step limit is raised rather than accepted`() {
        assertEquals(AppSettings.MIN_STEPS, AppSettings(maxSteps = -5).sanitized().maxSteps)
    }

    @Test
    fun `a zero-minute task is raised, because nothing can finish in no time`() {
        assertEquals(AppSettings.MIN_MINUTES, AppSettings(maxTaskMinutes = 0).sanitized().maxTaskMinutes)
    }

    @Test
    fun `a very long task is capped, to protect the battery`() {
        assertEquals(
            AppSettings.MAX_MINUTES,
            AppSettings(maxTaskMinutes = 10_000).sanitized().maxTaskMinutes,
        )
    }

    @Test
    fun `an unsupported language falls back rather than leaving speech broken`() {
        assertEquals(
            AppSettings.DEFAULT_LANGUAGE,
            AppSettings(languageTag = "fr-FR").sanitized().languageTag,
        )
    }

    @Test
    fun `each supported language is kept`() {
        AppSettings.SUPPORTED_LANGUAGES.forEach { tag ->
            assertEquals(tag, AppSettings(languageTag = tag).sanitized().languageTag)
        }
    }

    @Test
    fun `blank denylist entries are dropped and duplicates removed`() {
        val settings = AppSettings(deniedPackages = listOf(" com.bank ", "", "  ", "com.bank"))

        assertEquals(listOf("com.bank"), settings.sanitized().deniedPackages)
    }

    @Test
    fun `the loop limits follow the step and time settings`() {
        val settings = AppSettings(maxSteps = 25, maxTaskMinutes = 3).sanitized()

        assertEquals(25, settings.loopLimits.maxSteps)
        assertEquals(3 * 60_000L, settings.loopLimits.maxDurationMs)
    }

    @Test
    fun `sanitizing an already valid configuration changes nothing`() {
        val settings = AppSettings(maxSteps = 30, maxTaskMinutes = 5)

        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun `the defaults are recognised as the defaults`() {
        assertTrue(AppSettings().isDefault)
        assertFalse(AppSettings(maxSteps = 30).isDefault)
    }

    @Test
    fun `the default denylist covers the categories the design names`() {
        val patterns = PackageDenylist.DEFAULT_PATTERNS.joinToString(" ").lowercase()

        assertTrue("banking", patterns.contains("bank"))
        assertTrue("payments", patterns.contains("pay"))
        assertTrue("password managers", patterns.contains("password") || patterns.contains("1password"))
    }
}

class InMemorySettingsRepositoryTest {

    @Test
    fun `an update is visible to the next reader`() = runBlocking {
        val repo = InMemorySettingsRepository()

        repo.update { it.copy(maxSteps = 30) }

        assertEquals(30, repo.current().maxSteps)
    }

    @Test
    fun `an update is clamped on the way in`() = runBlocking {
        val repo = InMemorySettingsRepository()

        repo.update { it.copy(maxSteps = 999_999) }

        assertEquals(AppSettings.MAX_STEPS, repo.current().maxSteps)
    }

    @Test
    fun `a stored out-of-range value is clamped when the repository is built`() = runBlocking {
        val repo = InMemorySettingsRepository(AppSettings(maxSteps = 1, maxTaskMinutes = 0))

        val current = repo.current()

        assertEquals(AppSettings.MIN_STEPS, current.maxSteps)
        assertEquals(AppSettings.MIN_MINUTES, current.maxTaskMinutes)
    }

    @Test
    fun `the flow reports the current settings`() = runBlocking {
        val repo = InMemorySettingsRepository()

        repo.update { it.copy(speechEnabled = false) }

        assertFalse(repo.settings.first().speechEnabled)
    }

    @Test
    fun `reset returns every setting to its default`() = runBlocking {
        val repo = InMemorySettingsRepository()
        repo.update { it.copy(maxSteps = 30, speechEnabled = false, logLevel = LogLevel.DEBUG) }

        repo.reset()

        assertEquals(AppSettings(), repo.current())
    }

    @Test
    fun `updates do not lose the settings they do not touch`() = runBlocking {
        val repo = InMemorySettingsRepository()
        repo.update { it.copy(languageTag = "hi-IN") }

        repo.update { it.copy(maxSteps = 30) }

        assertEquals("hi-IN", repo.current().languageTag)
    }
}
