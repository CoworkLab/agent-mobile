package com.phoneagent.brain

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * The "Test connection" button.
 *
 * The test is deliberately tiny and checks three separate things, because a key can be valid
 * while the model name is wrong or the endpoint does not accept images:
 *  1. the endpoint answers at all
 *  2. the model name is accepted
 *  3. if the provider claims vision, an image is actually accepted
 *
 * A failure is reported with the provider's own words, since "invalid model name" from the
 * provider is far more useful than a generic failure.
 */
class ConnectionTester(
    private val providerFactory: (ProviderConfig) -> LlmProvider,
    private val logger: Logger,
) {

    data class Result(
        val ok: Boolean,
        val message: String,
        val latencyMs: Long = 0,
        val sawImage: Boolean = false,
        val usedTools: Boolean = false,
    )

    suspend fun test(config: ProviderConfig): Result {
        if (config.baseUrl.isBlank()) {
            return Result(false, "Add the base URL first.")
        }
        if (config.apiKey.isBlank()) {
            return Result(false, "Add the API key first.")
        }
        if (config.model.isBlank()) {
            return Result(false, "Add the model name first.")
        }

        val provider = providerFactory(config)
        val started = System.currentTimeMillis()

        // One pixel of JPEG: enough to prove the image path works without spending tokens.
        val tinyImage = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
            0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
        )

        val request = LlmRequest(
            systemPrompt = "Reply with the single word: ok",
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.USER,
                    text = if (config.supportsVision) "Reply with the single word: ok" else "Reply ok",
                    imageJpeg = if (config.supportsVision) tinyImage else null,
                ),
            ),
            tools = if (config.supportsTools) TEST_TOOLS else emptyList(),
            maxImageSide = 64,
            maxTokens = 16,
        )

        return try {
            val response = provider.chat(request)
            val latency = System.currentTimeMillis() - started

            val answered = response.text?.isNotBlank() == true || response.hasToolCalls
            if (!answered) {
                Result(
                    false,
                    "The model answered with nothing. The model name may be wrong.",
                    latency,
                )
            } else {
                logger.log(
                    LogLevel.INFO,
                    TAG,
                    "${config.name} test ok in ${latency}ms, tools=${response.hasToolCalls}",
                )
                Result(
                    ok = true,
                    message = buildString {
                        append("Works. Answered in ${latency}ms")
                        if (config.supportsVision) append(", image accepted")
                        if (response.hasToolCalls) append(", tool calling works")
                        append('.')
                    },
                    latencyMs = latency,
                    sawImage = config.supportsVision,
                    usedTools = response.hasToolCalls,
                )
            }
        } catch (error: LlmError) {
            val hint = when (error) {
                is LlmError.Auth -> " The API key looks wrong."
                is LlmError.RateLimited -> " The key is valid but the quota is used up."
                is LlmError.BadRequest ->
                    " The model name may be wrong, or the endpoint rejected the request."
                is LlmError.Network -> " Could not reach the address."
                is LlmError.Server -> " The provider is having trouble. Try again later."
                is LlmError.Unknown -> ""
            }
            Result(false, "${error.message.orEmpty()}$hint")
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "Test failed: ${error.message}")
            Result(false, error.message ?: "The test failed for an unknown reason.")
        }
    }

    private companion object {
        const val TAG = "ConnectionTest"

        /** A harmless tool, so the test proves tool calling without any real capability. */
        val TEST_TOOLS = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "report_ok",
                    "description" to "Report that the connection works. Call this once.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "status" to mapOf(
                                "type" to "string",
                                "description" to "The word ok",
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
