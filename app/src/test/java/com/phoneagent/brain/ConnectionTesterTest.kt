package com.phoneagent.brain

import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTesterTest {

    private class StubProvider(
        override val config: ProviderConfig,
        private val result: () -> LlmResponse,
    ) : LlmProvider {
        var lastRequest: LlmRequest? = null
            private set

        override suspend fun chat(request: LlmRequest): LlmResponse {
            lastRequest = request
            return result()
        }
    }

    private fun config(
        vision: Boolean = true,
        tools: Boolean = true,
        key: String = "k",
        model: String = "m",
        baseUrl: String = "https://x/v1",
    ) = ProviderConfig(
        id = "t", name = "T", baseUrl = baseUrl, apiKey = key, model = model,
        supportsVision = vision, supportsTools = tools,
    )

    private fun tester(provider: LlmProvider) = ConnectionTester({ provider }, Logger())

    @Test
    fun `a working provider reports success with the latency`() = runBlocking {
        val provider = StubProvider(config()) { LlmResponse(text = "ok") }

        val result = tester(provider).test(config())

        assertTrue(result.message, result.ok)
        assertTrue(result.message, result.message.contains("Works"))
    }

    @Test
    fun `a vision provider is sent an image, so the image path is really tested`() = runBlocking {
        val provider = StubProvider(config(vision = true)) { LlmResponse(text = "ok") }

        tester(provider).test(config(vision = true))

        val message = provider.lastRequest!!.messages.single()
        assertTrue("the test must send an image for a vision provider", message.imageJpeg != null)
        assertTrue(provider.lastRequest!!.tools.isNotEmpty())
    }

    @Test
    fun `a text-only provider is not sent an image`() = runBlocking {
        val provider = StubProvider(config(vision = false)) { LlmResponse(text = "ok") }

        tester(provider).test(config(vision = false))

        assertTrue(provider.lastRequest!!.messages.single().imageJpeg == null)
    }

    @Test
    fun `a text-only provider is not sent tools`() = runBlocking {
        val provider = StubProvider(config(tools = false)) { LlmResponse(text = "ok") }

        tester(provider).test(config(tools = false))

        assertTrue(provider.lastRequest!!.tools.isEmpty())
    }

    @Test
    fun `the test asks for very few tokens so it is cheap`() = runBlocking {
        val provider = StubProvider(config()) { LlmResponse(text = "ok") }

        tester(provider).test(config())

        assertTrue(provider.lastRequest!!.maxTokens!! <= 32)
        assertTrue(provider.lastRequest!!.maxImageSide!! <= 128)
    }

    @Test
    fun `a tool call counts as an answer`() = runBlocking {
        val provider = StubProvider(config()) {
            LlmResponse(toolCalls = listOf(ToolCallRequest(name = "report_ok")))
        }

        assertTrue(tester(provider).test(config()).ok)
    }

    @Test
    fun `an empty answer is a failure with a hint about the model name`() = runBlocking {
        val provider = StubProvider(config()) { LlmResponse(text = "   ") }

        val result = tester(provider).test(config())

        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("model name"))
    }

    @Test
    fun `an auth failure says the key looks wrong`() = runBlocking {
        val provider = StubProvider(config()) { throw LlmError.Auth("401 from provider") }

        val result = tester(provider).test(config())

        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("API key"))
    }

    @Test
    fun `a bad request points at the model name`() = runBlocking {
        val provider = StubProvider(config()) { throw LlmError.BadRequest("unknown model") }

        val result = tester(provider).test(config())

        assertTrue(result.message, result.message.contains("model name"))
    }

    @Test
    fun `a rate limit is reported as a quota problem, not a bad key`() = runBlocking {
        val provider = StubProvider(config()) { throw LlmError.RateLimited("429") }

        val result = tester(provider).test(config())

        assertTrue(result.message, result.message.contains("quota"))
        assertTrue(result.message, result.message.contains("valid"))
    }

    @Test
    fun `a network failure says the address could not be reached`() = runBlocking {
        val provider = StubProvider(config()) { throw LlmError.Network("no route") }

        assertTrue(tester(provider).test(config()).message.contains("reach"))
    }

    @Test
    fun `missing settings are reported before any request is made`() = runBlocking {
        var called = false
        val provider = StubProvider(config()) { called = true; LlmResponse(text = "ok") }

        assertTrue(tester(provider).test(config(baseUrl = "")).message.contains("base URL"))
        assertTrue(tester(provider).test(config(key = "")).message.contains("API key"))
        assertTrue(tester(provider).test(config(model = "")).message.contains("model name"))
        assertFalse("a request must not be sent when settings are incomplete", called)
    }

    @Test
    fun `an unexpected error is reported rather than crashing the settings screen`() = runBlocking {
        val provider = StubProvider(config()) { throw RuntimeException("something odd") }

        val result = tester(provider).test(config())

        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("something odd"))
    }
}
