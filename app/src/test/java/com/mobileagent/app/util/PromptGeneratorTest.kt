package com.mobileagent.app.util

import com.mobileagent.app.data.model.GeminiConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the agent's system instruction — the brain's operating manual.
 * These guarantee the doctrine the user demanded: dynamic identity (no hardcoded "sir",
 * no fake creator), tool routing doctrine, and zero-fake-completion rules.
 */
class PromptGeneratorTest {

    private fun prompt(
        personality: String = "",
        userName: String = "",
        memoryContext: String = "",
        language: String = GeminiConstants.DEFAULT_LANGUAGE,
        isSilentExecutionMode: Boolean = false,
        deviceProfile: String = ""
    ) = PromptGenerator.generateSystemPrompt(
        personality = personality,
        userName = userName,
        memoryContext = memoryContext,
        language = language,
        isSilentExecutionMode = isSilentExecutionMode,
        deviceProfile = deviceProfile
    )

    @Test
    fun identityIsDynamicNotHardcoded() {
        val p = prompt()
        assertTrue("Prompt must declare identity as dynamic", p.contains("[IDENTITY - DYNAMIC"))
        assertTrue("Prompt must contain the current self name", p.contains("Mobile Agent"))
        assertTrue(
            "Addressing must be memory-driven",
            p.contains("[HOW TO ADDRESS THE USER - DYNAMIC]")
        )
        assertTrue(
            "Prompt must forbid defaulting to 'sir'",
            p.contains("Never default to \"sir\" on your own")
        )
    }

    @Test
    fun noThirdPartyIdentityLeakage() {
        val p = prompt(personality = GeminiConstants.PERSONALITY_PRO, userName = "Arjun")
        val lower = p.lowercase()
        for (banned in listOf("jarvis", "techgptx", "rehaan", "tadipaar")) {
            assertFalse("System prompt leaks '$banned'", lower.contains(banned))
        }
    }

    @Test
    fun memoryContextIsEmbeddedVerbatim() {
        val memory = "preferred_address: user wants to be called 'Boss' (taught 2026-10-25)."
        val p = prompt(memoryContext = memory)
        assertTrue("User memory must reach the model", p.contains(memory))
    }

    @Test
    fun storedNameIsUsedWithoutNameFallsBackToNeutral() {
        assertTrue(
            prompt(userName = "Arjun").contains("The user's stored name is 'Arjun'")
        )
        assertTrue(
            prompt(userName = "").contains("No stored name yet")
        )
    }

    @Test
    fun toolDoctrinesArePresent() {
        val p = prompt()
        listOf(
            "[TOOL DOCTRINE - ROUTING PRIORITY]",
            "[TOOL DOCTRINE - PERCEPTION BEFORE ACTION]",
            "[DEVICE DISCOVERY",
            "[INTENT RECIPES",
            "[TRUTHFULNESS - ZERO FAKE COMPLETIONS]",
            "[LISTEN-FIRST MANDATE]"
        ).forEach { section ->
            assertTrue("System prompt missing doctrine section: $section", p.contains(section))
        }
        // The exact failure mode the user complained about ("long press wala tool nahi hai")
        assertTrue(
            "Prompt must teach long-press capability",
            p.contains("long_press")
        )
        assertTrue(
            "Prompt must forbid 'no tool' claims",
            p.contains("Never claim \"no tool exists\"") || p.contains("never say \"I don't have a tool")
        )
    }

    @Test
    fun languageMandateTogglesCorrectly() {
        val hindi = prompt(language = "Hindi")
        assertTrue(hindi.contains("[STRICT SPOKEN LANGUAGE MANDATE - Hindi ONLY]"))

        val auto = prompt(language = GeminiConstants.DEFAULT_LANGUAGE)
        assertFalse("Auto language must not pin a language", auto.contains("STRICT SPOKEN LANGUAGE MANDATE"))
    }

    @Test
    fun silentExecutionModeToggles() {
        val silent = prompt(isSilentExecutionMode = true)
        assertTrue(silent.contains("SILENT EXECUTION MODE IS ACTIVE"))
        assertFalse(prompt(isSilentExecutionMode = false).contains("SILENT EXECUTION MODE IS ACTIVE"))
    }

    @Test
    fun deviceProfilePassthroughAndFallback() {
        val p1 = prompt(deviceProfile = "Battery 80%; Android 14; perms: CONTACTS granted")
        assertTrue(p1.contains("Battery 80%"))
        val p2 = prompt(deviceProfile = "")
        assertTrue(p2.contains("get_capabilities"))
    }

    @Test
    fun personalityBlocksRender() {
        assertTrue(prompt(personality = GeminiConstants.PERSONALITY_PRO).contains("Pro Mode"))
        assertTrue(prompt(personality = "").contains("Agent Mode"))
    }
}
