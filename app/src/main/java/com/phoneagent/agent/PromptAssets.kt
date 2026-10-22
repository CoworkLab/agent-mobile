package com.phoneagent.agent

import android.content.Context
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Loads the system prompt from assets.
 *
 * It lives in a file rather than in code so it can be tuned by reading it, and so a test can
 * assert that it still covers the rules the brief requires. A missing file is a real failure
 * rather than a silent fallback to an empty prompt, because an agent with no rules is worse
 * than an agent that will not start.
 */
object PromptAssets {

    const val SYSTEM_PROMPT_PATH = "prompts/system.md"

    fun loadSystemPrompt(context: Context, logger: Logger): String = try {
        context.assets.open(SYSTEM_PROMPT_PATH).bufferedReader().use { it.readText() }
    } catch (error: Throwable) {
        logger.log(LogLevel.ERROR, "PromptAssets", "Missing $SYSTEM_PROMPT_PATH: ${error.message}")
        throw IllegalStateException("The agent's instructions are missing from the app.", error)
    }
}
