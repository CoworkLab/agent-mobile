package com.phoneagent.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts a single `{tool, args}` call from model text.
 *
 * This is the fallback path for providers and models without native tool calling, which in
 * practice means a lot of open-weight models served through OpenRouter and Groq. Models get
 * the JSON wrong in predictable ways, so each repair here exists because it was needed:
 *  - the JSON arrives inside a ```json fence
 *  - there is prose before or after the JSON
 *  - there is a trailing comma before a closing brace
 *  - the arguments are a nested object, or the whole thing is double-encoded as a string
 *
 * Every repair is conservative: the result is parsed as JSON before being accepted, so a
 * repair that produces nonsense fails here rather than becoming a bad tool call.
 */
object JsonToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class Parsed(val name: String, val argsJson: String, val repairs: List<String>)

    /** Names the model might use for the call, in the order they are tried. */
    private val nameKeys = listOf("tool", "name", "tool_name", "function", "action")
    private val argKeys = listOf("args", "arguments", "parameters", "input", "params")

    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null

        val repairs = mutableListOf<String>()
        var candidate = raw.trim()

        val unfenced = stripFences(candidate)
        if (unfenced != candidate) {
            repairs += "removed code fence"
            candidate = unfenced.trim()
        }

        // A payload that is itself a JSON string has to be unwrapped before the brace hunt,
        // because the braces inside it are escaped and would be read as string content.
        val unwrapped = unwrapStringLiteral(candidate)
        if (unwrapped != null) {
            repairs += "unwrapped double-encoded JSON"
            candidate = unwrapped.trim()
        }

        val extracted = extractJsonObject(candidate)
        if (extracted != candidate) {
            repairs += "trimmed surrounding text"
            candidate = extracted.trim()
        }

        var element = tryParse(candidate)
        if (element == null) {
            val repaired = repairTrailingCommas(candidate)
            if (repaired != candidate) {
                repairs += "repaired trailing commas"
                candidate = repaired
                element = tryParse(candidate)
            }
        }

        // A nested string literal can also appear once the outer object is unwrapped.
        if (element is JsonPrimitive && element.isString) {
            val inner = element.contentOrNull.orEmpty()
            val innerElement = tryParse(inner)
            if (innerElement != null) {
                repairs += "unwrapped double-encoded JSON"
                element = innerElement
            }
        }

        val obj = element as? JsonObject ?: return null

        val name = nameKeys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: return null

        val argsElement = argKeys.firstNotNullOfOrNull { key -> obj[key] }

        val argsJson = when (argsElement) {
            null -> "{}"
            is JsonObject -> argsElement.toString()
            is JsonArray -> argsElement.toString()
            is JsonPrimitive -> {
                // Arguments given as a JSON string rather than an object.
                val inner = argsElement.contentOrNull.orEmpty()
                if (inner.trim().startsWith("{")) {
                    repairs += "parsed arguments from a string"
                    inner
                } else {
                    "{}"
                }
            }
            else -> "{}"
        }

        return Parsed(name.trim(), argsJson, repairs)
    }

    /**
     * If the text is a quoted JSON string, returns its contents. This is the shape produced
     * by a model that encodes its answer twice, which happens with some gateways.
     */
    private fun unwrapStringLiteral(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"") || trimmed.length < 2) return null
        val parsed = tryParse(trimmed)
        return (parsed as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /**
     * Removes a Markdown fence. Handles both a fenced block and a fence that is only partly
     * closed, which happens when the model is cut off by a token limit.
     */
    fun stripFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text

        var body = trimmed.removePrefix("```")

        // The language tag is either followed by a newline or a space. Dropping only up to a
        // newline would leave "json " in front of a fence written on a single line.
        val firstBreak = body.indexOfFirst { it == '\n' || it == ' ' }
        val headerEnd = if (firstBreak >= 0) firstBreak + 1 else 0
        val header = body.substring(0, headerEnd).trim()
        val looksLikeLanguage = header.isNotEmpty() && header.all { it.isLetterOrDigit() || it in "+-#." }
        if (looksLikeLanguage) body = body.substring(headerEnd)

        // A closing fence may be missing when the model was cut off by a token limit.
        val close = body.lastIndexOf("```")
        if (close >= 0) body = body.substring(0, close)

        return body.trim()
    }

    /**
     * Returns the outermost `{...}` region. A brace inside a string literal is not counted,
     * so a message body containing `}` does not truncate the object.
     */
    fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) return text

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        // Unbalanced: the model was cut off. Return what there is so the caller can decide.
        return text.substring(start)
    }

    /** Removes a comma that sits directly before a closing brace or bracket. */
    fun repairTrailingCommas(text: String): String =
        Regex(""",\s*([}\]])""").replace(text) { match -> match.groupValues[1] }

    private fun tryParse(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()
}
