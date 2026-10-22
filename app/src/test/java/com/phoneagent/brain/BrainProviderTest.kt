package com.phoneagent.brain

import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatCompletionsBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json.parseToJsonElement(body).jsonObject

    private fun request(
        messages: List<LlmMessage> = listOf(LlmMessage(LlmMessage.Role.USER, text = "hi")),
        tools: List<Map<String, Any?>> = emptyList(),
    ) = LlmRequest(systemPrompt = "You are an agent.", messages = messages, tools = tools)

    @Test
    fun `the system prompt is the first message`() {
        val body = parse(ChatCompletionsBody.build(request(), "m", supportsTools = true))
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are an agent.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the model name is included`() {
        val body = parse(ChatCompletionsBody.build(request(), "gemini-2.0-flash", true))
        assertEquals("gemini-2.0-flash", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a text-only user message is a plain string`() {
        val body = parse(ChatCompletionsBody.build(request(), "m", true))
        val user = body["messages"]!!.jsonArray[1].jsonObject
        assertTrue("content should be a plain string", user["content"] is JsonPrimitive)
    }

    @Test
    fun `an image message becomes content parts with a data uri`() {
        val message = LlmMessage(
            role = LlmMessage.Role.USER,
            text = "What is on screen?",
            imageJpeg = byteArrayOf(1, 2, 3, 4),
        )
        val body = parse(ChatCompletionsBody.build(request(listOf(message)), "m", true))
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)

        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `an image without text sends only the image part`() {
        val message = LlmMessage(LlmMessage.Role.USER, imageJpeg = byteArrayOf(9, 9))
        val body = parse(ChatCompletionsBody.build(request(listOf(message)), "m", true))
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `base64 encoding has no line breaks`() {
        val encoded = ChatCompletionsBody.encode(ByteArray(200) { 42 })
        assertFalse("base64 must be a single line inside a data uri", encoded.contains('\n'))
    }

    @Test
    fun `tools are sent as function definitions when supported`() {
        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "tap",
                    "description" to "tap",
                    "parameters" to mapOf("type" to "object"),
                ),
            ),
        )
        val body = parse(ChatCompletionsBody.build(request(tools = tools), "m", true))

        assertNotNull(body["tools"])
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertNull("no json mode when native tools are used", body["response_format"])
    }

    @Test
    fun `json mode is requested when the provider has no native tools`() {
        val tools = listOf(mapOf("type" to "function", "function" to mapOf("name" to "tap")))
        val body = parse(ChatCompletionsBody.build(request(tools = tools), "m", supportsTools = false))

        assertNull("no tools array when unsupported", body["tools"])
        assertEquals(
            "json_object",
            body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an assistant tool call is rendered in the wire shape`() {
        val assistant = LlmMessage(
            role = LlmMessage.Role.ASSISTANT,
            toolCalls = listOf(ToolCallRequest(id = "c1", name = "tap", argumentsJson = """{"mark":1}""")),
        )
        val body = parse(ChatCompletionsBody.build(request(listOf(assistant)), "m", true))
        val calls = body["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray

        assertEquals(1, calls.size)
        val function = calls[0].jsonObject["function"]!!.jsonObject
        assertEquals("tap", function["name"]!!.jsonPrimitive.content)
        assertEquals("""{"mark":1}""", function["arguments"]!!.jsonPrimitive.content)
        assertEquals("c1", calls[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result message carries the call id`() {
        val tool = LlmMessage(
            role = LlmMessage.Role.TOOL,
            text = "Tapped element [1]",
            toolCallId = "c1",
        )
        val body = parse(ChatCompletionsBody.build(request(listOf(tool)), "m", true))
        val rendered = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("tool", rendered["role"]!!.jsonPrimitive.content)
        assertEquals("c1", rendered["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("Tapped element [1]", rendered["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `temperature and max tokens are only sent when set`() {
        val without = parse(ChatCompletionsBody.build(request(), "m", true))
        assertNull(without["temperature"])
        assertNull(without["max_tokens"])

        val with = parse(
            ChatCompletionsBody.build(request().copy(temperature = 0.2, maxTokens = 500), "m", true),
        )
        assertEquals(0.2, with["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("500", with["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a native tool call is read from the response`() {
        val body = """
            {"choices":[{"message":{"content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"tap","arguments":"{\"mark\":2}"}}
            ]},"finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()

        val response = ChatCompletionsBody.parseResponse(body, "P", "m", allowJsonFallback = false)

        assertTrue(response.hasToolCalls)
        assertEquals("tap", response.toolCalls[0].name)
        assertEquals("""{"mark":2}""", response.toolCalls[0].argumentsJson)
        assertEquals(15, response.usage.totalTokens)
        assertEquals("tool_calls", response.finishReason)
        assertFalse(response.wasJsonFallback)
    }

    @Test
    fun `a prose answer is returned as text with no tool call`() {
        val body = """
            {"choices":[{"message":{"content":"I cannot do that."},"finish_reason":"stop"}]}
        """.trimIndent()

        val response = ChatCompletionsBody.parseResponse(body, "P", "m", allowJsonFallback = true)

        assertEquals("I cannot do that.", response.text)
        assertFalse(response.hasToolCalls)
    }

    @Test
    fun `the json fallback turns prose-wrapped json into a tool call`() {
        val body = """
            {"choices":[{"message":{"content":"```json\n{\"tool\":\"tap\",\"args\":{\"mark\":1}}\n```"},
            "finish_reason":"stop"}]}
        """.trimIndent()

        val response = ChatCompletionsBody.parseResponse(body, "P", "m", allowJsonFallback = true)

        assertTrue(response.hasToolCalls)
        assertEquals("tap", response.toolCalls[0].name)
        assertTrue(response.wasJsonFallback)
        assertNull("the raw json must not also be shown as text", response.text)
    }

    @Test
    fun `the fallback is not attempted when the provider supports native tools`() {
        val body = """
            {"choices":[{"message":{"content":"{\"tool\":\"tap\",\"args\":{}}"}}]}
        """.trimIndent()

        val response = ChatCompletionsBody.parseResponse(body, "P", "m", allowJsonFallback = false)

        assertFalse(response.hasToolCalls)
        assertNotNull(response.text)
    }

    @Test
    fun `an error inside a 200 response is raised`() {
        val body = """{"error":{"message":"bad model","code":400}}"""
        try {
            ChatCompletionsBody.parseResponse(body, "P", "m", false)
            org.junit.Assert.fail("expected an error")
        } catch (error: LlmError) {
            assertTrue(error.message!!, error.message!!.contains("bad model"))
        }
    }

    @Test
    fun `a response with no choices is an error`() {
        try {
            ChatCompletionsBody.parseResponse("""{"choices":[]}""", "P", "m", false)
            org.junit.Assert.fail("expected an error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.Unknown)
        }
    }

    @Test
    fun `non-json is an error rather than a crash`() {
        try {
            ChatCompletionsBody.parseResponse("<html>502</html>", "P", "m", false)
            org.junit.Assert.fail("expected an error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.Unknown)
        }
    }

    @Test
    fun `usage is zero when the provider omits it`() {
        val body = """{"choices":[{"message":{"content":"hi"}}]}"""
        val response = ChatCompletionsBody.parseResponse(body, "P", "m", false)
        assertEquals(0, response.usage.totalTokens)
    }

    @Test
    fun `total tokens is derived when only the parts are given`() {
        val body = """
            {"choices":[{"message":{"content":"hi"}}],
             "usage":{"prompt_tokens":7,"completion_tokens":3}}
        """.trimIndent()
        val response = ChatCompletionsBody.parseResponse(body, "P", "m", false)
        assertEquals(10, response.usage.totalTokens)
    }
}

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun fail(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    private fun provider(
        supportsTools: Boolean = true,
        model: String = "test-model",
    ) = OpenAiCompatibleProvider(
        config = ProviderConfig(
            id = "test",
            name = "Test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "test-key",
            model = model,
            supportsTools = supportsTools,
        ),
        logger = Logger(),
        sleeper = { },
    )

    private fun request() = LlmRequest(
        systemPrompt = "sys",
        messages = listOf(LlmMessage(LlmMessage.Role.USER, text = "hi")),
        tools = listOf(mapOf("type" to "function", "function" to mapOf("name" to "tap"))),
    )

    @Test
    fun `a successful call returns the parsed tool call`() = runBlocking {
        server.enqueue(
            ok(
                """{"choices":[{"message":{"tool_calls":[
                   {"id":"c1","function":{"name":"tap","arguments":"{\"mark\":1}"}}]}}]}""",
            ),
        )

        val response = provider().chat(request())

        assertTrue(response.hasToolCalls)
        assertEquals("tap", response.toolCalls[0].name)
        assertEquals("Test", response.providerName)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertTrue(recorded.body!!.utf8().contains("test-model"))
    }

    @Test
    fun `a 429 becomes a rate limited error after retries`() = runBlocking {
        repeat(3) {
            server.enqueue(fail(429, """{"error":"rate limit"}"""))
        }

        try {
            provider().chat(request())
            org.junit.Assert.fail("expected a rate limit error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.RateLimited)
        }
        assertEquals("three attempts: the first plus two retries", 3, server.requestCount)
    }

    @Test
    fun `a retry after a 500 succeeds`() = runBlocking {
        server.enqueue(fail(500, "boom"))
        server.enqueue(ok("""{"choices":[{"message":{"content":"recovered"}}]}"""))

        val response = provider().chat(request())

        assertEquals("recovered", response.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 401 is not retried`() = runBlocking {
        server.enqueue(fail(401, """{"error":"invalid api key"}"""))

        try {
            provider().chat(request())
            org.junit.Assert.fail("expected an auth error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.Auth)
        }
        assertEquals("a bad key must not be retried", 1, server.requestCount)
    }

    @Test
    fun `a 400 is not retried`() = runBlocking {
        server.enqueue(fail(400, "bad request"))

        try {
            provider().chat(request())
            org.junit.Assert.fail("expected a bad request error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.BadRequest)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the json fallback works end to end when tools are unsupported`() = runBlocking {
        server.enqueue(
            ok("""{"choices":[{"message":{"content":"{\"tool\":\"back\",\"args\":{}}"}}]}"""),
        )

        val response = provider(supportsTools = false).chat(request())

        assertTrue(response.hasToolCalls)
        assertEquals("back", response.toolCalls[0].name)
        assertTrue(response.wasJsonFallback)

        val body = server.takeRequest().body!!.utf8()
        assertTrue("json mode must be requested", body.contains("json_object"))
        assertFalse("no tools array for a text-only model", body.contains("\"tools\""))
    }

    @Test
    fun `a connection failure is a network error`() = runBlocking {
        val unreachable = OpenAiCompatibleProvider(
            config = ProviderConfig(
                id = "dead", name = "Dead",
                baseUrl = "http://127.0.0.1:1/v1", apiKey = "k", model = "m",
            ),
            logger = Logger(),
            sleeper = { },
        )

        try {
            unreachable.chat(request())
            org.junit.Assert.fail("expected a network error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.Network)
            assertTrue(error.isRetryable)
        }
    }

    @Test
    fun `the api key never appears in a log line`() = runBlocking {
        val logger = Logger()
        val leaking = OpenAiCompatibleProvider(
            config = ProviderConfig(
                id = "t", name = "Test", baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "super-secret-key-12345", model = "m",
            ),
            logger = logger,
            sleeper = { },
        )
        server.enqueue(fail(401, "nope"))

        runCatching { leaking.chat(request()) }

        val logged = logger.entries.value.joinToString("\n") { it.message }
        assertFalse(
            "a key must never be written to the log",
            logged.contains("super-secret-key-12345"),
        )
    }
}
