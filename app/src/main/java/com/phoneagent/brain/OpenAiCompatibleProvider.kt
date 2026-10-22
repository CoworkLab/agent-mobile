package com.phoneagent.brain

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * A provider that speaks the OpenAI `/chat/completions` shape.
 *
 * One implementation covers Gemini, OpenRouter and Groq, which is why the brief only needs
 * this one class. The retry policy is deliberately narrow: it retries only errors that can
 * plausibly succeed on a second attempt, and it honours a `Retry-After` when the provider
 * supplies one rather than guessing a backoff.
 */
class OpenAiCompatibleProvider(
    override val config: ProviderConfig,
    private val logger: Logger,
    private val client: OkHttpClient = defaultClient(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : LlmProvider {

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun chat(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = ChatCompletionsBody.build(
            request = request,
            model = config.model,
            supportsTools = config.supportsTools,
        )

        var attempt = 0
        var lastError: LlmError? = null

        while (attempt <= MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1))
                logger.log(LogLevel.INFO, TAG, "Retry $attempt for ${config.name} in ${backoff}ms")
                sleeper(backoff)
            }

            try {
                return@withContext executeOnce(body, request)
            } catch (error: LlmError) {
                lastError = error
                if (!error.isRetryable) throw error

                // A provider-supplied delay is respected even when it exceeds our backoff.
                val retryAfter = (error as? LlmError.RateLimited)?.retryAfterMs
                if (retryAfter != null && retryAfter > 0) {
                    logger.log(LogLevel.INFO, TAG, "${config.name} asked to wait ${retryAfter}ms")
                    sleeper(retryAfter.coerceAtMost(MAX_RETRY_AFTER_MS))
                }
                attempt++
            }
        }

        throw lastError ?: LlmError.Unknown("The request failed for an unknown reason.")
    }

    private fun executeOnce(body: String, request: LlmRequest): LlmResponse {
        val httpRequest = Request.Builder()
            .url(config.chatCompletionsUrl)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (timeout: SocketTimeoutException) {
            throw LlmError.Network("${config.name} took too long to answer.", timeout)
        } catch (io: IOException) {
            throw LlmError.Network("Could not reach ${config.name}. Check the connection.", io)
        }

        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // The key must never reach the log, and the body can echo it back on some
                // providers, so only the status and a short snippet are recorded.
                logger.log(LogLevel.WARN, TAG, "${config.name} HTTP ${r.code}: ${text.take(200)}")
                throw LlmErrorClassifier.fromHttp(r.code, text)
            }

            return ChatCompletionsBody.parseResponse(
                body = text,
                providerName = config.name,
                model = config.model,
                allowJsonFallback = !config.supportsTools && request.tools.isNotEmpty(),
            )
        }
    }

    companion object {
        const val TAG = "LlmProvider"

        /** Two retries after the first try: three attempts total. */
        const val MAX_RETRIES = 2
        const val BASE_BACKOFF_MS = 700L

        /** Even when a provider asks for longer, the loop must not stall for minutes. */
        const val MAX_RETRY_AFTER_MS = 20_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Generous read timeout: a vision request with a screenshot takes real time.
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
