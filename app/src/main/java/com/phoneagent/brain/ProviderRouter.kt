package com.phoneagent.brain

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Tries providers in order and remembers which ones are temporarily unusable.
 *
 * Two rules shape this:
 *  - A provider that returned 429, a 5xx or a network error is put in cooldown rather than
 *    being retried on every step. Without that, one dead provider doubles the latency of the
 *    whole run.
 *  - An auth failure also moves to the next provider, but it is surfaced to the user, because
 *    a wrong key is something only they can fix.
 *
 * Screenshots are only sent to providers that can see them. Sending an image to a text-only
 * model wastes the upload and usually returns a worse answer than the text tree alone.
 */
class ProviderRouter(
    private val providers: List<LlmProvider>,
    private val logger: Logger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private val cooldowns = mutableMapOf<String, Long>()
    private val usage = mutableMapOf<String, TokenUsage>()

    /** Providers that are enabled and configured, in priority order. */
    val available: List<ProviderConfig>
        get() = providers.map { it.config }.filter { it.isUsable }.sortedBy { it.priority }

    /**
     * Sends the request to the first provider that is not in cooldown.
     *
     * If every provider is in cooldown the earliest to recover is used anyway: refusing to
     * try at all would fail a run that a short wait would have saved.
     */
    suspend fun chat(request: LlmRequest): LlmResponse {
        val ordered = orderedProviders()
        if (ordered.isEmpty()) {
            throw LlmError.Auth(
                "No model is configured. Add one in Settings, with its model name.",
            )
        }

        val now = clock()
        val ready = ordered.filter { provider ->
            val until = cooldowns[provider.config.id] ?: 0L
            until <= now
        }

        val candidates = ready.ifEmpty {
            logger.log(LogLevel.WARN, TAG, "All providers are in cooldown; trying the first anyway")
            listOf(ordered.minByOrNull { cooldowns[it.config.id] ?: 0L } ?: ordered.first())
        }

        var lastError: LlmError? = null

        candidates.forEach { provider ->
            val config = provider.config
            try {
                val response = provider.chat(adapt(request, config))
                usage[config.id] = (usage[config.id] ?: TokenUsage()) + response.usage
                clearCooldown(config.id)
                return response
            } catch (error: LlmError) {
                lastError = error
                logger.log(
                    LogLevel.WARN,
                    TAG,
                    "${config.name} failed: ${error.message?.take(160)}",
                )
                if (error.shouldTryNextProvider) {
                    startCooldown(config.id, error)
                }
                if (!error.shouldTryNextProvider) throw error
            }
        }

        throw lastError ?: LlmError.Unknown("Every provider failed.")
    }

    /**
     * Drops the image for a provider that cannot see it, and says so in the text so the model
     * knows a screenshot existed and was withheld.
     */
    private fun adapt(request: LlmRequest, config: ProviderConfig): LlmRequest {
        if (config.supportsVision) return request

        val stripped = request.messages.map { message ->
            if (!message.hasImage) {
                message
            } else {
                message.copy(
                    imageJpeg = null,
                    text = buildString {
                        append(message.text.orEmpty())
                        if (isNotEmpty()) append('\n')
                        append("(A screenshot was available but this model cannot see images.)")
                    },
                )
            }
        }
        return request.copy(messages = stripped)
    }

    private fun startCooldown(id: String, error: LlmError) {
        val duration = when (error) {
            is LlmError.RateLimited -> error.retryAfterMs?.coerceAtMost(MAX_COOLDOWN_MS) ?: cooldownMs
            is LlmError.Auth -> AUTH_COOLDOWN_MS
            else -> cooldownMs
        }
        cooldowns[id] = clock() + duration
        logger.log(LogLevel.INFO, TAG, "Cooling down $id for ${duration}ms")
    }

    private fun clearCooldown(id: String) {
        cooldowns.remove(id)
    }

    /** Visible for the Settings screen, so the user can see a provider is being skipped. */
    fun cooldownRemainingMs(providerId: String): Long =
        ((cooldowns[providerId] ?: 0L) - clock()).coerceAtLeast(0)

    fun usageFor(providerId: String): TokenUsage = usage[providerId] ?: TokenUsage()

    fun totalUsage(): TokenUsage = usage.values.fold(TokenUsage()) { acc, u -> acc + u }

    private fun orderedProviders(): List<LlmProvider> =
        providers.filter { it.config.isUsable }.sortedBy { it.config.priority }

    companion object {
        const val TAG = "ProviderRouter"

        /** Five minutes: long enough to stop hammering, short enough to recover in a run. */
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
        const val MAX_COOLDOWN_MS = 15 * 60 * 1000L
        const val AUTH_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
