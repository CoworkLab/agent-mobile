package com.mobileagent.app.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure-Kotlin builders for the Gemini Live API (BidiGenerateContent) wire protocol.
 *
 * Extracted from GeminiLiveWebSocket so that:
 *  1. The app and the automated tests send byte-identical payloads.
 *  2. Protocol regressions (e.g. the missing audio-transcription config that
 *     left the chat screen permanently empty) are caught by unit tests.
 *  3. A real handshake against Google's servers can run on the JVM in CI.
 *
 * Field names follow the current official raw-WebSocket documentation:
 *   {"setup": {"model": "models/gemini-3.8-live", "responseModalities": ["AUDIO"], ...}}
 *
 * Transcription (input + output) is ALWAYS requested — without
 * `outputAudioTranscription` the server never sends `serverContent.outputTranscription`,
 * and without `inputAudioTranscription` the user's spoken words never appear,
 * so typed-chat rendering and turn bookkeeping silently starve.
 */
object LiveProtocol {

    enum class ErrorKind {
        API_KEY_INVALID,
        REGION_BLOCKED,
        QUOTA_EXCEEDED,
        MODEL_REJECTED,
        VOICE_REJECTED,
        UNKNOWN
    }

    fun wsUrl(apiKey: String): String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

    /**
     * BidiGenerateContentSetup message.
     *
     * @param toolsJson serialized JSON array of tool declarations (from ToolRegistry),
     *                  embedded as a real array; null/blank omits the field.
     * @param includeSpeechConfig false retries a setup that was rejected because of the voice name.
     */
    fun buildSetupJson(
        model: String,
        voiceName: String?,
        systemPrompt: String,
        toolsJson: String? = null,
        includeSpeechConfig: Boolean = true,
        disableThinking: Boolean = false
    ): String {
        val setup = JsonObject().apply {
            addProperty("model", model)
            add("outputAudioTranscription", JsonObject())
            add("inputAudioTranscription", JsonObject())
            add(
                "systemInstruction",
                JsonObject().apply {
                    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", systemPrompt) }) })
                }
            )
            add(
                "generationConfig",
                JsonObject().apply {
                    add("responseModalities", JsonArray().apply { addProperty("AUDIO") })
                    if (includeSpeechConfig) {
                        add(
                            "speechConfig",
                            JsonObject().apply {
                                add(
                                    "voiceConfig",
                                    JsonObject().apply {
                                        add(
                                            "prebuiltVoiceConfig",
                                            JsonObject().apply { addProperty("voiceName", voiceName ?: "Puck") }
                                        )
                                    }
                                )
                            }
                        )
                    }
                    addProperty("temperature", 0.6)
                    if (disableThinking) {
                        add("thinkingConfig", JsonObject().apply { addProperty("thinkingBudget", 0) })
                    }
                }
            )
            if (!toolsJson.isNullOrBlank()) {
                val parsed = runCatching { JsonParser.parseString(toolsJson) }.getOrNull()
                if (parsed != null && parsed.isJsonArray) {
                    add("tools", parsed.asJsonArray)
                }
            }
        }
        return JsonObject().apply { add("setup", setup) }.toString()
    }

    /** clientContent turn used for typed chat input. */
    fun buildClientContentJson(text: String): String =
        JsonObject().apply {
            add(
                "clientContent",
                JsonObject().apply {
                    add(
                        "turns",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("role", "user")
                                    add(
                                        "parts",
                                        JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) }
                                    )
                                }
                            )
                        }
                    )
                    addProperty("turnComplete", true)
                }
            )
        }.toString()

    /**
     * toolResponse message. [responseJson] is the serialized response payload object
     * produced by the tool executor; embedded as a real object, never as a string.
     */
    fun buildToolResponseJson(callId: String, functionName: String, responseJson: String): String {
        val responseObj = runCatching { JsonParser.parseString(responseJson) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?: JsonObject().apply { addProperty("result", responseJson) }
        return JsonObject().apply {
            add(
                "toolResponse",
                JsonObject().apply {
                    add(
                        "functionResponses",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("id", callId)
                                    addProperty("name", functionName)
                                    add("response", responseObj)
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
    }

    /** Classifies a Gemini error (in-message `error` payload, close reason, or HTTP body). */
    fun classifyApiError(code: Int?, message: String?): ErrorKind {
        val m = (message ?: "").lowercase()
        if (m.contains("location") && (m.contains("not supported") || m.contains("unsupported"))) {
            return ErrorKind.REGION_BLOCKED
        }
        if (m.contains("api key not valid") || m.contains("api_key_invalid") ||
            (m.contains("api key") && m.contains("invalid"))
        ) {
            return ErrorKind.API_KEY_INVALID
        }
        if (code == 401 || code == 403) {
            return if (m.contains("permission") || m.isBlank()) ErrorKind.API_KEY_INVALID else ErrorKind.API_KEY_INVALID
        }
        if (m.contains("resource_exhausted") || m.contains("quota")) return ErrorKind.QUOTA_EXCEEDED
        if (m.contains("voice") && (m.contains("not") || m.contains("unknown") || m.contains("invalid"))) {
            return ErrorKind.VOICE_REJECTED
        }
        if (m.contains("not found") || m.contains("not_supported") || m.contains("not supported") ||
            m.contains("no model") || m.contains("is not available") ||
            (code == 404)
        ) {
            return ErrorKind.MODEL_REJECTED
        }
        if (m.contains("failed_precondition")) return ErrorKind.REGION_BLOCKED
        return ErrorKind.UNKNOWN
    }
}
