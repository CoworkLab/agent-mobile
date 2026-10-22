package com.phoneagent.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonToolCallParserTest {

    @Test
    fun `parses a clean tool call`() {
        val parsed = JsonToolCallParser.parse("""{"tool":"tap","args":{"mark":3}}""")
        assertNotNull(parsed)
        assertEquals("tap", parsed!!.name)
        assertEquals("""{"mark":3}""", parsed.argsJson)
        assertTrue(parsed.repairs.isEmpty())
    }

    @Test
    fun `strips a json code fence`() {
        val raw = """
            ```json
            {"tool":"tap","args":{"mark":1}}
            ```
        """.trimIndent()
        val parsed = JsonToolCallParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("tap", parsed!!.name)
        assertTrue(parsed.repairs.contains("removed code fence"))
    }

    @Test
    fun `strips a bare code fence with no language`() {
        val raw = "```\n{\"tool\":\"back\",\"args\":{}}\n```"
        assertEquals("back", JsonToolCallParser.parse(raw)!!.name)
    }

    @Test
    fun `handles an unclosed fence from a truncated response`() {
        val raw = "```json\n{\"tool\":\"tap\",\"args\":{\"mark\":2}}"
        assertEquals("tap", JsonToolCallParser.parse(raw)!!.name)
    }

    @Test
    fun `extracts json surrounded by prose`() {
        val raw = "Sure, I will tap that.\n{\"tool\":\"tap\",\"args\":{\"mark\":5}}\nDone."
        val parsed = JsonToolCallParser.parse(raw)
        assertEquals("tap", parsed!!.name)
        assertTrue(parsed.repairs.contains("trimmed surrounding text"))
    }

    @Test
    fun `repairs a trailing comma`() {
        val raw = """{"tool":"tap","args":{"mark":1,},}"""
        val parsed = JsonToolCallParser.parse(raw)
        assertNotNull("a trailing comma should not lose the call", parsed)
        assertEquals("tap", parsed!!.name)
        assertTrue(parsed.repairs.contains("repaired trailing commas"))
    }

    @Test
    fun `a brace inside a string does not truncate the object`() {
        val raw = """{"tool":"type_text","args":{"text":"a } brace"}}"""
        val parsed = JsonToolCallParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("type_text", parsed!!.name)
        assertTrue(parsed.argsJson, parsed.argsJson.contains("a } brace"))
    }

    @Test
    fun `an escaped quote inside a string does not break extraction`() {
        val raw = """{"tool":"type_text","args":{"text":"say \"hi\""}}"""
        val parsed = JsonToolCallParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("type_text", parsed!!.name)
    }

    @Test
    fun `unwraps a double-encoded payload`() {
        val raw = """"{\"tool\":\"tap\",\"args\":{\"mark\":1}}""""
        val parsed = JsonToolCallParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("tap", parsed!!.name)
    }

    @Test
    fun `accepts alternative key names`() {
        assertEquals("tap", JsonToolCallParser.parse("""{"name":"tap","arguments":{"mark":1}}""")!!.name)
        assertEquals("tap", JsonToolCallParser.parse("""{"function":"tap","parameters":{}}""")!!.name)
    }

    @Test
    fun `parses arguments given as a string`() {
        val raw = """{"tool":"tap","args":"{\"mark\":4}"}"""
        val parsed = JsonToolCallParser.parse(raw)
        assertEquals("tap", parsed!!.name)
        assertEquals("""{"mark":4}""", parsed.argsJson)
    }

    @Test
    fun `missing arguments become an empty object`() {
        val parsed = JsonToolCallParser.parse("""{"tool":"back"}""")
        assertEquals("{}", parsed!!.argsJson)
    }

    @Test
    fun `returns null for text with no call`() {
        assertNull(JsonToolCallParser.parse("I think you should tap the button."))
        assertNull(JsonToolCallParser.parse(""))
        assertNull(JsonToolCallParser.parse(null))
    }

    @Test
    fun `returns null when there is json but no tool name`() {
        assertNull(JsonToolCallParser.parse("""{"reasoning":"because","mark":1}"""))
    }

    @Test
    fun `a malformed object does not become a call`() {
        assertNull(JsonToolCallParser.parse("""{"tool": }"""))
    }

    @Test
    fun `an array of arguments is preserved`() {
        val raw = """{"tool":"batch_actions","args":[{"tool":"tap"}]}"""
        val parsed = JsonToolCallParser.parse(raw)
        assertEquals("batch_actions", parsed!!.name)
        assertTrue(parsed.argsJson.startsWith("["))
    }

    @Test
    fun `strips fences when the fence is on the same line`() {
        assertEquals(
            """{"tool":"tap","args":{}}""",
            JsonToolCallParser.stripFences("""```json {"tool":"tap","args":{}} ```""").trim(),
        )
    }

    @Test
    fun `extract keeps the outermost braces only`() {
        assertEquals(
            """{"a":{"b":1}}""",
            JsonToolCallParser.extractJsonObject("""noise {"a":{"b":1}} tail"""),
        )
    }

    @Test
    fun `extract is not confused by an apostrophe in prose`() {
        val text = """I'll do it: {"tool":"tap","args":{}}"""
        assertEquals("""{"tool":"tap","args":{}}""", JsonToolCallParser.extractJsonObject(text))
    }
}

class LlmErrorClassifierTest {

    @Test
    fun `429 is rate limited`() {
        val error = LlmErrorClassifier.fromHttp(429, """{"error":"rate limit"}""")
        assertTrue(error is LlmError.RateLimited)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `a quota message is rate limited even with a 400 status`() {
        val error = LlmErrorClassifier.fromHttp(400, """{"error":{"status":"RESOURCE_EXHAUSTED"}}""")
        assertTrue(error is LlmError.RateLimited)
    }

    @Test
    fun `a retry delay in the body is read`() {
        val error = LlmErrorClassifier.fromHttp(
            429,
            """{"error":{"details":[{"retryDelay":"7s"}]}}""",
        )
        assertEquals(7000L, (error as LlmError.RateLimited).retryAfterMs)
    }

    @Test
    fun `401 is an auth failure and is not retried in place`() {
        val error = LlmErrorClassifier.fromHttp(401, """{"error":"invalid api key"}""")
        assertTrue(error is LlmError.Auth)
        assertFalse("retrying a bad key is pointless", error.isRetryable)
        assertTrue("but another provider might work", error.shouldTryNextProvider)
    }

    @Test
    fun `403 with a key message is treated as auth`() {
        val error = LlmErrorClassifier.fromHttp(403, """{"error":"API key not valid"}""")
        assertTrue(error is LlmError.Auth)
    }

    @Test
    fun `400 is a bad request and stops the run`() {
        val error = LlmErrorClassifier.fromHttp(400, """{"error":"bad image"}""")
        assertTrue(error is LlmError.BadRequest)
        assertFalse(error.isRetryable)
        assertFalse(error.shouldTryNextProvider)
    }

    @Test
    fun `500 is a server error and is retryable`() {
        val error = LlmErrorClassifier.fromHttp(503, "upstream down")
        assertTrue(error is LlmError.Server)
        assertTrue(error.isRetryable)
        assertTrue(error.shouldTryNextProvider)
    }

    @Test
    fun `504 is treated as a network problem`() {
        assertTrue(LlmErrorClassifier.fromHttp(504, "timeout") is LlmError.Network)
    }

    @Test
    fun `an unrecognised status becomes unknown`() {
        assertTrue(LlmErrorClassifier.fromHttp(418, "teapot") is LlmError.Unknown)
    }

    @Test
    fun `an auth error message tells the user what to do`() {
        val error = LlmErrorClassifier.fromHttp(401, "nope")
        assertTrue(error.message!!, error.message!!.contains("Settings"))
    }
}

class ProviderConfigTest {

    @Test
    fun `the chat url is built from a plain base url`() {
        val config = ProviderConfigPreset("https://api.example.com/v1")
        assertEquals("https://api.example.com/v1/chat/completions", config.chatCompletionsUrl)
    }

    @Test
    fun `a base url that already ends in the path is not doubled`() {
        val config = ProviderConfigPreset("https://api.example.com/v1/chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", config.chatCompletionsUrl)
    }

    @Test
    fun `a trailing slash is tolerated`() {
        val config = ProviderConfigPreset("https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/chat/completions", config.chatCompletionsUrl)
    }

    private fun ProviderConfigPreset(base: String) = ProviderConfig(
        id = "t", name = "T", baseUrl = base, model = "m",
    )

    @Test
    fun `a config without a model is not usable`() {
        val config = ProviderConfig(id = "t", name = "T", baseUrl = "https://x/v1", model = "")
        assertFalse(config.isUsable)
    }

    @Test
    fun `a disabled config is not usable`() {
        val config = ProviderConfig(
            id = "t", name = "T", baseUrl = "https://x/v1", model = "m", enabled = false,
        )
        assertFalse(config.isUsable)
    }

    @Test
    fun `the api key is masked for display`() {
        val config = ProviderConfig(
            id = "t", name = "T", baseUrl = "https://x/v1", model = "m",
            apiKey = "sk-abcdefghijklmnop",
        )
        assertEquals("sk-a****mnop", config.maskedKey)
        assertFalse("the real key must not appear", config.maskedKey.contains("efghijkl"))
    }

    @Test
    fun `a blank key is shown as missing, not as empty`() {
        val config = ProviderConfig(id = "t", name = "T", baseUrl = "https://x", model = "m")
        assertEquals("(no key)", config.maskedKey)
    }

    @Test
    fun `a short key is fully masked`() {
        val config = ProviderConfig(
            id = "t", name = "T", baseUrl = "https://x", model = "m", apiKey = "short",
        )
        assertEquals("****", config.maskedKey)
    }

    @Test
    fun `the presets leave the model name empty on purpose`() {
        ProviderPresets.all.forEach { preset ->
            assertEquals(
                "${preset.name} must not ship a model name; names change too fast",
                "",
                preset.model,
            )
        }
    }

    @Test
    fun `the presets are ordered by priority`() {
        val priorities = ProviderPresets.all.map { it.priority }
        assertEquals(priorities.sorted(), priorities)
    }

    @Test
    fun `gemini and openrouter can see images, groq is marked text-only`() {
        assertTrue(ProviderPresets.gemini.supportsVision)
        assertTrue(ProviderPresets.openRouter.supportsVision)
        assertFalse("Groq's fast models are text-only", ProviderPresets.groq.supportsVision)
    }

    @Test
    fun `every preset has a distinct id`() {
        val ids = ProviderPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }
}
