package com.mobileagent.app

import com.google.gson.JsonParser
import com.mobileagent.app.data.model.GeminiConstants
import com.mobileagent.app.network.LiveProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol regression tests for the Gemini Live API wire format.
 *
 * These exist because two silent killers shipped before:
 *  1. The setup message omitted `outputAudioTranscription`/`inputAudioTranscription`,
 *     so the chat screen never received any assistant text.
 *  2. The default model ID ("gemini-3.1-flash-live-preview") was not a real model,
 *     so every Live session was rejected at setup — killing voice AND chat.
 */
class LiveProtocolTest {

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `setup carries model, transcription and modality config`() {
        val json = parse(
            LiveProtocol.buildSetupJson(
                model = GeminiConstants.DEFAULT_MODEL,
                voiceName = "Puck",
                systemPrompt = "You are a helpful agent.",
                toolsJson = null
            )
        )
        val setup = json.getAsJsonObject("setup")
        assertEquals(GeminiConstants.DEFAULT_MODEL, setup.get("model").asString)

        // Transcription configs MUST be present — without them the chat never gets text.
        assertTrue("outputAudioTranscription missing", setup.has("outputAudioTranscription"))
        assertTrue("inputAudioTranscription missing", setup.has("inputAudioTranscription"))
        assertTrue(setup.getAsJsonObject("outputAudioTranscription").entrySet().isEmpty())

        val gen = setup.getAsJsonObject("generationConfig")
        assertEquals("AUDIO", gen.getAsJsonArray("responseModalities")[0].asString)
        val voice = gen.getAsJsonObject("speechConfig")
            .getAsJsonObject("voiceConfig")
            .getAsJsonObject("prebuiltVoiceConfig")
        assertEquals("Puck", voice.get("voiceName").asString)

        val sysInstr = setup.getAsJsonObject("systemInstruction")
            .getAsJsonArray("parts")[0].asJsonObject
        assertEquals("You are a helpful agent.", sysInstr.get("text").asString)
    }

    @Test
    fun `tools json is embedded as a real array not a string`() {
        val tools = """[{"name":"open_app","description":"opens apps"}]"""
        val setup = parse(
            LiveProtocol.buildSetupJson(
                model = GeminiConstants.DEFAULT_MODEL,
                voiceName = "Puck",
                systemPrompt = "p",
                toolsJson = tools
            )
        ).getAsJsonObject("setup")
        val toolsArr = setup.getAsJsonArray("tools")
        assertEquals(1, toolsArr.size())
        assertEquals("open_app", toolsArr[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `malformed tools json is omitted instead of crashing setup`() {
        val setup = parse(
            LiveProtocol.buildSetupJson(
                model = GeminiConstants.DEFAULT_MODEL,
                voiceName = "Puck",
                systemPrompt = "p",
                toolsJson = "not-json{"
            )
        ).getAsJsonObject("setup")
        assertFalse(setup.has("tools"))
    }

    @Test
    fun `speech config can be omitted for voiceless retry`() {
        val setup = parse(
            LiveProtocol.buildSetupJson(
                model = GeminiConstants.DEFAULT_MODEL,
                voiceName = "Puck",
                systemPrompt = "p",
                includeSpeechConfig = false
            )
        ).getAsJsonObject("setup")
        assertFalse(setup.getAsJsonObject("generationConfig").has("speechConfig"))
    }

    @Test
    fun `thinking disabled only when requested`() {
        val with = parse(
            LiveProtocol.buildSetupJson(GeminiConstants.DEFAULT_MODEL, null, "p", disableThinking = true)
        ).getAsJsonObject("setup").getAsJsonObject("generationConfig")
        assertEquals(0, with.getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)

        val without = parse(
            LiveProtocol.buildSetupJson(GeminiConstants.DEFAULT_MODEL, null, "p", disableThinking = false)
        ).getAsJsonObject("setup").getAsJsonObject("generationConfig")
        assertFalse(without.has("thinkingConfig"))
    }

    @Test
    fun `client content uses the clientContent turn protocol`() {
        val json = parse(LiveProtocol.buildClientContentJson("hello agent"))
        val cc = json.getAsJsonObject("clientContent")
        assertTrue(cc.get("turnComplete").asBoolean)
        val turn = cc.getAsJsonArray("turns")[0].asJsonObject
        assertEquals("user", turn.get("role").asString)
        assertEquals("hello agent", turn.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `tool response embeds the payload object`() {
        val json = parse(
            LiveProtocol.buildToolResponseJson("call-1", "open_app", """{"status":"success"}""")
        )
        val fr = json.getAsJsonObject("toolResponse").getAsJsonArray("functionResponses")[0].asJsonObject
        assertEquals("call-1", fr.get("id").asString)
        assertEquals("open_app", fr.get("name").asString)
        assertEquals("success", fr.getAsJsonObject("response").get("status").asString)
    }

    @Test
    fun `non-object tool response falls back to wrapped result`() {
        val json = parse(
            LiveProtocol.buildToolResponseJson("c", "f", "plain text result")
        )
        val response = json.getAsJsonObject("toolResponse")
            .getAsJsonArray("functionResponses")[0].asJsonObject
            .getAsJsonObject("response")
        assertEquals("plain text result", response.get("result").asString)
    }

    @Test
    fun `error classification matches google api error shapes`() {
        assertEquals(
            LiveProtocol.ErrorKind.REGION_BLOCKED,
            LiveProtocol.classifyApiError(400, "User location is not supported for the API use.")
        )
        assertEquals(
            LiveProtocol.ErrorKind.API_KEY_INVALID,
            LiveProtocol.classifyApiError(400, "API key not valid. Please pass a valid API key.")
        )
        assertEquals(
            LiveProtocol.ErrorKind.API_KEY_INVALID,
            LiveProtocol.classifyApiError(401, "")
        )
        assertEquals(
            LiveProtocol.ErrorKind.QUOTA_EXCEEDED,
            LiveProtocol.classifyApiError(429, "RESOURCE_EXHAUSTED: quota exceeded")
        )
        // Voice errors must be detected before generic "not supported" model matching.
        assertEquals(
            LiveProtocol.ErrorKind.VOICE_REJECTED,
            LiveProtocol.classifyApiError(400, "Voice name Foobar is not supported for this model.")
        )
        assertEquals(
            LiveProtocol.ErrorKind.MODEL_REJECTED,
            LiveProtocol.classifyApiError(404, "Model models/gemini-3.1-flash-live-preview not found.")
        )
        assertEquals(
            LiveProtocol.ErrorKind.UNKNOWN,
            LiveProtocol.classifyApiError(500, "Internal error.")
        )
    }

    @Test
    fun `websocket url carries the key parameter`() {
        val url = LiveProtocol.wsUrl("KEY123")
        assertTrue(url.startsWith(GeminiConstants.WS_BASE_URL))
        assertTrue(url.endsWith("?key=KEY123"))
        assertNull(url.substringAfter("://").takeIf { it.contains("@") })
    }

    @Test
    fun `model catalogue matches the Part 7 provider contract`() {
        val models = GeminiConstants.SUPPORTED_MODELS
        assertTrue("model catalogue must not be empty", models.isNotEmpty())
        assertEquals("model catalogue entries must be unique", models.size, models.toSet().size)
        models.forEach { id ->
            assertTrue("model id must be fully qualified: $id", id.startsWith("models/"))
        }
        // Part 7's exact default: the known-working preview model.
        assertEquals(GeminiConstants.MODEL_3_1_FLASH, GeminiConstants.DEFAULT_MODEL)
        assertTrue(GeminiConstants.MODEL_3_8_LIVE in models)
        assertTrue(GeminiConstants.MODEL_2_5_NATIVE_AUDIO in models)
        // The dead legacy ID must never be offered again.
        assertFalse(GeminiConstants.LEGACY_MODEL_3_1_FLASH_AGENT in models)
    }
}
