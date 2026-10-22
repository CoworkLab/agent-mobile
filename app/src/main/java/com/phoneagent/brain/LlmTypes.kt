package com.phoneagent.brain

import kotlinx.serialization.Serializable

/**
 * One message in the conversation sent to a model.
 *
 * Images are carried as raw JPEG bytes rather than base64, so the encoding cost is paid
 * once, in the provider, and the history stays cheap to hold in memory.
 */
@Serializable
data class LlmMessage(
    val role: Role,
    val text: String? = null,
    val imageJpeg: ByteArray? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    val hasImage: Boolean get() = imageJpeg != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmMessage) return false
        return role == other.role &&
            text == other.text &&
            imageJpeg.contentEquals(other.imageJpeg) &&
            toolCallId == other.toolCallId &&
            toolName == other.toolName &&
            toolCalls == other.toolCalls
    }

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (imageJpeg?.contentHashCode() ?: 0)
        result = 31 * result + (toolCallId?.hashCode() ?: 0)
        result = 31 * result + (toolName?.hashCode() ?: 0)
        result = 31 * result + toolCalls.hashCode()
        return result
    }
}

/** A tool call the model asked for, as it arrived over the wire. */
@Serializable
data class ToolCallRequest(
    val id: String? = null,
    val name: String,
    val argumentsJson: String = "{}",
)

/**
 * One request to a model.
 *
 * [maxImageSide] is on the request rather than only on the config, because the same provider
 * can be asked for a small image for a cheap step and a larger one when detail matters.
 */
data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val tools: List<Map<String, Any?>> = emptyList(),
    val maxImageSide: Int? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val forceJsonToolCall: Boolean = false,
)

/** Token accounting, so cost is visible rather than guessed. */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}

/**
 * What the model answered.
 *
 * A response can carry text, tool calls, or both. A model that was asked for a tool call but
 * answered with prose is not an error: the text is kept so the loop can log what it said,
 * and [toolCalls] being empty is what the loop reacts to.
 */
data class LlmResponse(
    val text: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val usage: TokenUsage = TokenUsage(),
    val providerName: String = "",
    val model: String = "",
    val finishReason: String? = null,
    val wasJsonFallback: Boolean = false,
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * A failure the loop can act on. The distinction matters: a 429 should move to the next
 * provider, an auth failure should tell the user, and a bad request means the prompt itself
 * is wrong and retrying will not help.
 */
sealed class LlmError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 429 or a provider-specific quota message. */
    class RateLimited(message: String, val retryAfterMs: Long? = null) : LlmError(message)

    /** 401 or 403. Retrying is pointless; the key is wrong or revoked. */
    class Auth(message: String) : LlmError(message)

    /** 400 or 422: the request was malformed, usually the prompt or an image. */
    class BadRequest(message: String) : LlmError(message)

    /** 5xx. Worth retrying, and worth moving to the next provider. */
    class Server(message: String, val statusCode: Int) : LlmError(message)

    /** No connectivity, DNS failure, or timeout. */
    class Network(message: String, cause: Throwable? = null) : LlmError(message, cause)

    /** Anything unrecognised, including an unparseable body. */
    class Unknown(message: String, cause: Throwable? = null) : LlmError(message, cause)

    val isRetryable: Boolean
        get() = this is RateLimited || this is Server || this is Network

    /** True when another provider might succeed where this one failed. */
    val shouldTryNextProvider: Boolean
        get() = this is RateLimited || this is Server || this is Network || this is Auth
}

/**
 * Maps an HTTP status and body to a classified error.
 *
 * The body is inspected because providers disagree about status codes: Gemini and OpenRouter
 * both return 400 for a bad key in some paths, and a quota message often arrives as 403.
 */
object LlmErrorClassifier {

    fun fromHttp(statusCode: Int, body: String): LlmError {
        val lower = body.lowercase()
        val snippet = body.take(400)

        return when {
            statusCode == 429 || lower.contains("rate limit") || lower.contains("quota") ||
                lower.contains("resource_exhausted") ->
                LlmError.RateLimited(snippet, parseRetryAfter(body))

            statusCode == 401 || statusCode == 403 || lower.contains("api key not valid") ||
                lower.contains("invalid api key") || lower.contains("unauthorized") ->
                LlmError.Auth("The API key was rejected. Check it in Settings. ($snippet)")

            statusCode == 400 || statusCode == 422 -> LlmError.BadRequest(snippet)

            // Checked before the general 5xx bucket: 504 is a gateway timeout, which is a
            // network condition rather than the provider itself being broken.
            statusCode == 408 || statusCode == 504 ->
                LlmError.Network("The provider timed out. ($snippet)")

            statusCode in 500..599 -> LlmError.Server(snippet, statusCode)

            else -> LlmError.Unknown("HTTP $statusCode: $snippet")
        }
    }

    /**
     * Reads a retry delay when the provider gives one. A `Retry-After` header is seconds;
     * Gemini puts the delay in the body as a duration string.
     */
    private fun parseRetryAfter(body: String): Long? {
        val match = Regex(""""retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"""").find(body)
        if (match != null) {
            return (match.groupValues[1].toDoubleOrNull()?.times(1000))?.toLong()
        }
        val seconds = Regex("""[Rr]etry-?[Aa]fter"?\s*[:=]\s*"?(\d+)""").find(body)
        return seconds?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
    }
}
