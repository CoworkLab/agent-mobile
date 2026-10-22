package com.phoneagent.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the `/chat/completions` request body.
 *
 * Kept pure and separate from the HTTP call so the exact JSON can be asserted in a JVM test.
 * The OpenAI shape is used because Gemini, OpenRouter and Groq all accept it, which is what
 * makes one provider implementation enough.
 *
 * Images go in as `data:` URIs, which every one of these providers accepts and which avoids
 * a separate upload step.
 */
object ChatCompletionsBody {

    private val json = Json { encodeDefaults = true }

    fun build(
        request: LlmRequest,
        model: String,
        supportsTools: Boolean,
    ): String {
        val messages = buildJsonArray {
            add(systemMessage(request.systemPrompt))

            request.messages.forEach { message ->
                when (message.role) {
                    LlmMessage.Role.SYSTEM -> add(systemMessage(message.text.orEmpty()))
                    LlmMessage.Role.USER -> add(userMessage(message))
                    LlmMessage.Role.ASSISTANT -> add(assistantMessage(message))
                    LlmMessage.Role.TOOL -> add(toolMessage(message))
                }
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }

            if (supportsTools && request.tools.isNotEmpty()) {
                putJsonArray("tools") { request.tools.forEach { add(toJson(it)) } }
                put("tool_choice", "auto")
            }

            // The fallback path asks for one JSON object and nothing else. It is only used
            // when the provider has no native tool calling, because native is more reliable.
            if (!supportsTools && request.tools.isNotEmpty()) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun systemMessage(text: String) = buildJsonObject {
        put("role", "system")
        put("content", text)
    }

    /**
     * A user message is plain text when there is no image, and a content-parts array when
     * there is. Some providers reject the array form for text-only messages.
     */
    private fun userMessage(message: LlmMessage): JsonObject {
        val image = message.imageJpeg
        val text = message.text.orEmpty()

        if (image == null) {
            return buildJsonObject {
                put("role", "user")
                put("content", text)
            }
        }

        return buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                if (text.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,${encode(image)}")
                        }
                    },
                )
            }
        }
    }

    private fun assistantMessage(message: LlmMessage): JsonObject = buildJsonObject {
        put("role", "assistant")
        message.text?.let { put("content", it) }
        if (message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id ?: call.name)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun toolMessage(message: LlmMessage): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", message.toolCallId ?: message.toolName.orEmpty())
        put("content", message.text.orEmpty())
    }

    /**
     * Converts plain Kotlin values to JSON.
     *
     * The tool schemas come from the registry as `Map<String, Any?>`, and kotlinx.serialization
     * has no serializer for `Any`, so the conversion is done by hand. Doing it here also means
     * a schema with a nested map or list is encoded correctly rather than being dropped.
     */
    fun toJson(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlinx.serialization.json.JsonElement -> value
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, entry) -> put(key.toString(), toJson(entry)) }
        }
        is Iterable<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Standard base64, padded, with no line breaks.
     *
     * Written out rather than using `android.util.Base64` so it works in a JVM unit test,
     * where the Android class is only a stub.
     */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0

            out.append(BASE64_ALPHABET[b0 shr 2])
            out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(
                if (index + 1 < bytes.size) {
                    BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)]
                } else {
                    '='
                },
            )
            out.append(if (index + 2 < bytes.size) BASE64_ALPHABET[b2 and 0x3F] else '=')
            index += 3
        }
        return out.toString()
    }

    /**
     * Reads the first choice's message out of a response body.
     *
     * Native tool calls are preferred. When the provider did not return any and the caller
     * asked for the JSON fallback, the text is parsed for a `{tool,args}` object instead.
     */
    fun parseResponse(
        body: String,
        providerName: String,
        model: String,
        allowJsonFallback: Boolean,
    ): LlmResponse {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: throw LlmError.Unknown("The provider sent a response that is not JSON.")

        // Some providers report an error inside a 200 response.
        val error = root["error"]
        if (error != null && error !is JsonPrimitive) {
            throw LlmError.Unknown("Provider error: ${error.toString().take(300)}")
        }

        val usage = parseUsage(root["usage"])

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw LlmError.Unknown("The provider sent no choices.")

        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val message = choice["message"] as? JsonObject

        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val nativeCalls = parseNativeToolCalls(message)

        if (nativeCalls.isNotEmpty()) {
            return LlmResponse(
                text = text,
                toolCalls = nativeCalls,
                usage = usage,
                providerName = providerName,
                model = model,
                finishReason = finishReason,
            )
        }

        if (allowJsonFallback && text != null) {
            val parsed = JsonToolCallParser.parse(text)
            if (parsed != null) {
                return LlmResponse(
                    text = null,
                    toolCalls = listOf(
                        ToolCallRequest(
                            id = "json_fallback",
                            name = parsed.name,
                            argumentsJson = parsed.argsJson,
                        ),
                    ),
                    usage = usage,
                    providerName = providerName,
                    model = model,
                    finishReason = finishReason,
                    wasJsonFallback = true,
                )
            }
        }

        return LlmResponse(
            text = text,
            usage = usage,
            providerName = providerName,
            model = model,
            finishReason = finishReason,
        )
    }

    private fun parseNativeToolCalls(message: JsonObject?): List<ToolCallRequest> {
        val calls = message?.get("tool_calls") as? JsonArray ?: return emptyList()
        return calls.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val function = obj["function"] as? JsonObject ?: return@mapNotNull null
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
            ToolCallRequest(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull,
                name = name,
                argumentsJson = arguments,
            )
        }
    }

    private fun parseUsage(element: kotlinx.serialization.json.JsonElement?): TokenUsage {
        val obj = element as? JsonObject ?: return TokenUsage()
        fun int(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val prompt = int("prompt_tokens")
        val completion = int("completion_tokens")
        val total = int("total_tokens").takeIf { it > 0 } ?: (prompt + completion)
        return TokenUsage(prompt, completion, total)
    }
}
