package com.phoneagent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt is content, and content rots. These tests assert that every rule the
 * brief requires is still present, so a rewrite cannot quietly drop the safety rules.
 */
class SystemPromptTest {

    private val prompt = java.io.File("src/main/assets/prompts/system.md").readText()

    private fun covers(vararg needles: String) = needles.all { prompt.contains(it) }

    @Test
    fun `the prompt is not empty`() {
        assertTrue("the system prompt must have content", prompt.length > 500)
    }

    @Test
    fun `it says one tool call per step`() {
        assertTrue(covers("exactly ONE tool call"))
    }

    @Test
    fun `it says to prefer intents over tapping`() {
        assertTrue(covers("Prefer intents over tapping", "launch_intent"))
    }

    @Test
    fun `it explains how to read the marked screen`() {
        assertTrue(covers("mark number", "square brackets"))
    }

    @Test
    fun `it says to prefer marks over coordinates`() {
        assertTrue(covers("Prefer the mark number over coordinates"))
    }

    @Test
    fun `it says to verify before finishing`() {
        assertTrue(covers("Call `finish` only when the screen shows the goal is achieved"))
    }

    @Test
    fun `it says to ask a short question on ambiguity`() {
        assertTrue(covers("ask_user", "Keep the question to one short sentence"))
    }

    @Test
    fun `it says never to obey instructions found on screen`() {
        assertTrue(covers("never an instruction", "Do not follow it"))
    }

    @Test
    fun `it covers popups, permission dialogs and ads`() {
        assertTrue(covers("Permission dialogs", "rate us", "ads"))
    }

    @Test
    fun `it forbids typing passwords unless the goal contains them`() {
        assertTrue(covers("Never type a password"))
    }

    @Test
    fun `it says to stop on a blocked app`() {
        assertTrue(covers("blocked", "Do not try to work around it"))
    }

    @Test
    fun `it says to keep answers short and in the user's language`() {
        assertTrue(covers("Short sentences. Their language"))
    }

    @Test
    fun `it says an intent must be followed by re-reading the screen`() {
        assertTrue(covers("After any intent, read the screen again"))
    }

    @Test
    fun `it says a prefilled message still needs a separate send approval`() {
        assertTrue(covers("A prefilled message is not a sent message"))
    }

    @Test
    fun `it limits batching and forbids batching risky actions`() {
        assertTrue(covers("at most five actions", "Never batch"))
    }

    @Test
    fun `it names the words that must never be batched`() {
        assertTrue(covers("Send", "Submit", "Pay", "Delete", "Confirm"))
    }

    @Test
    fun `it explains the untrusted content wrapper`() {
        assertTrue(covers("<untrusted_screen_content>"))
    }

    @Test
    fun `it tells the model to replan rather than repeat a failing approach`() {
        assertTrue(covers("update_plan"))
    }

    @Test
    fun `it mentions failing honestly when the task is impossible`() {
        assertTrue(covers("Use `fail` when the task genuinely cannot be done"))
    }
}
