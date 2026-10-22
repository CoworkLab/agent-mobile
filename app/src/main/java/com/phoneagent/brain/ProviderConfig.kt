package com.phoneagent.brain

import kotlinx.serialization.Serializable

/**
 * One model endpoint.
 *
 * `priority` orders the fallback chain: the router tries enabled providers from lowest
 * priority number to highest. `maxImageSide` is per provider because the useful size depends
 * on the model, and sending a 1080px image to a cheap text-only model is wasted tokens.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val supportsVision: Boolean = true,
    val supportsTools: Boolean = true,
    val maxImageSide: Int = 1080,
    val enabled: Boolean = true,
    val priority: Int = 0,
) {
    /** The chat-completions URL, tolerating a base URL given with or without the path. */
    val chatCompletionsUrl: String
        get() {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

    val isUsable: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    /** Never log or display a key; this is what the UI and logs show instead. */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> "(no key)"
            apiKey.length <= 8 -> "****"
            else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
        }
}

/**
 * Known-good endpoints. The model name is deliberately empty: model names change faster than
 * apps ship, and a wrong default produces a confusing 404, so the user fills it in.
 */
object ProviderPresets {

    val gemini = ProviderConfig(
        id = "gemini",
        name = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        model = "",
        supportsVision = true,
        supportsTools = true,
        priority = 0,
    )

    val openRouter = ProviderConfig(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "",
        supportsVision = true,
        supportsTools = true,
        priority = 1,
    )

    val groq = ProviderConfig(
        id = "groq",
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "",
        supportsVision = false,
        supportsTools = true,
        priority = 2,
    )

    val custom = ProviderConfig(
        id = "custom",
        name = "Custom",
        baseUrl = "",
        model = "",
        supportsVision = true,
        supportsTools = true,
        priority = 3,
    )

    val all = listOf(gemini, openRouter, groq, custom)

    fun byId(id: String): ProviderConfig? = all.firstOrNull { it.id == id }
}

/** Talks to one provider. Implementations must classify failures as [LlmError]. */
interface LlmProvider {
    val config: ProviderConfig
    suspend fun chat(request: LlmRequest): LlmResponse
}
