package com.phoneagent.agent

import com.phoneagent.brain.LlmMessage
import com.phoneagent.brain.JsonToolCallParser
import kotlinx.serialization.json.contentOrNull
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.SnapshotSerializer

/**
 * Builds the conversation sent to the model each step.
 *
 * The order matters and is deliberate: the goal and the plan come first so the model is
 * reminded of the task before it sees the screen, then the recent history, then the current
 * observation last so it is the freshest thing in context. Anything the agent must not obey
 * from the screen is wrapped by the serializer, and the system prompt says so.
 */
class PromptBuilder(
    private val systemPrompt: String,
    private val limits: LoopLimits = LoopLimits.DEFAULT,
    private val injectionDetector: com.phoneagent.safety.InjectionDetector? = null,
) {

    data class Built(
        val messages: List<LlmMessage>,
        val estimatedChars: Int,
    )

    fun build(
        goal: String,
        plan: AgentPlan,
        history: List<HistoryCompactor.Entry>,
        snapshot: ScreenSnapshot,
        hints: List<String> = emptyList(),
        lastResult: String? = null,
        userAnswer: String? = null,
        stepIndex: Int = 0,
    ): Built {
        val messages = mutableListOf<LlmMessage>()

        messages += LlmMessage(
            role = LlmMessage.Role.USER,
            text = buildString {
                appendLine("GOAL: $goal")
                appendLine()
                appendLine("YOUR PLAN:")
                appendLine(plan.asPromptText())
                appendLine()
                appendLine("STEP: ${stepIndex + 1} of ${limits.maxSteps}")
            },
        )

        // Older steps as one-line summaries, newest with its screenshot.
        history.forEach { entry ->
            messages += LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "Step ${entry.stepIndex + 1}: ${entry.summary}",
            )
        }

        lastResult?.let {
            messages += LlmMessage(
                role = LlmMessage.Role.USER,
                text = "RESULT OF YOUR LAST ACTION: $it",
            )
        }

        userAnswer?.let {
            messages += LlmMessage(
                role = LlmMessage.Role.USER,
                text = "THE USER ANSWERED: $it",
            )
        }

        if (hints.isNotEmpty()) {
            messages += LlmMessage(
                role = LlmMessage.Role.USER,
                text = "HINTS:\n" + hints.joinToString("\n") { "- $it" },
            )
        }

        messages += LlmMessage(
            role = LlmMessage.Role.USER,
            // Everything read off the screen is wrapped as untrusted, because a screen can
            // contain text that pretends to be an instruction from the user. The system prompt
            // tells the model that anything inside these tags is data.
            text = "CURRENT SCREEN:\n" + wrapUntrusted(
                SnapshotSerializer.toPromptText(snapshot, maxNodes = NODES_IN_PROMPT),
            ),
            imageJpeg = snapshot.screenshotJpeg,
        )

        val withSystem = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, text = systemPrompt),
        ) + messages

        return Built(
            messages = withSystem,
            // The image cost is only counted where an image actually is. Charging it per
            // message would make every prompt look enormous and cause the trimmer to throw
            // away history it did not need to.
            estimatedChars = withSystem.sumOf { message ->
                (message.text?.length ?: 0) + if (message.hasImage) IMAGE_CHAR_COST else 0
            },
        )
    }

    /** Wraps untrusted text, or leaves it alone when no detector is configured. */
    private fun wrapUntrusted(text: String): String =
        injectionDetector?.wrapUntrusted(text) ?: text

    /**
     * How much of the prompt the screenshot costs.
     *
     * A 1080px JPEG is roughly 150 KB, which base64-encodes to about 200 000 characters.
     * Counting that keeps the size estimate honest, so the trimmer does not think a prompt
     * with an image is small.
     */
    private companion object {
        const val NODES_IN_PROMPT = 80
        const val IMAGE_CHAR_COST = 200_000
    }
}

/**
 * Turns the model's reply into something the loop can act on.
 *
 * A reply can be a tool call, a final answer, a question, or nothing usable. All four are
 * handled here, and the result is a value rather than an exception, so one odd reply cannot
 * end a run that a retry would have saved.
 */
object DecisionParser {

    sealed interface Decision {
        /** Run this tool. */
        data class Act(
            val tool: String,
            val args: Map<String, Any?>,
            val argumentsJson: String,
        ) : Decision

        /** The task is finished; the text is the user-facing summary. */
        data class Finish(val summary: String) : Decision

        /** The agent needs the human. */
        data class AskUser(val question: String) : Decision

        /** The goal cannot be done, with the reason. */
        data class Fail(val reason: String) : Decision

        /** The reply was unusable; the loop should tell the model and try again. */
        data class Unusable(val reason: String) : Decision
    }

    private val argumentJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(
        toolName: String?,
        argumentsJson: String?,
        text: String?,
        knownTools: Set<String>,
    ): Decision {
        // A tool call is taken at face value only if the name is one we have. An unknown name
        // is a model mistake worth correcting rather than an error worth failing on.
        if (!toolName.isNullOrBlank()) {
            if (toolName !in knownTools) {
                return Decision.Unusable(
                    "There is no tool called \"$toolName\". Use one of: " +
                        knownTools.sorted().joinToString(", ") + ".",
                )
            }
            val args = parseArguments(argumentsJson)
                ?: return Decision.Unusable(
                    "The arguments for $toolName were not valid JSON. Send them as an object.",
                )
            return Decision.Act(toolName, args, argumentsJson ?: "{}")
        }

        // No tool call: the text may still be a decision, written as JSON by the fallback path
        // or as plain prose by a chatty model.
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Decision.Unusable("The reply had no tool call and no text. Try again.")
        }

        val fromJson = JsonToolCallParserShim.parse(trimmed, knownTools)
        if (fromJson != null) return fromJson

        return Decision.Unusable(
            "You must reply with exactly one tool call. " +
                "For example: {\"tool\":\"tap\",\"args\":{\"mark\":1}}. " +
                "Your reply was: ${trimmed.take(200)}",
        )
    }

    /**
     * Reads the argument object. A model sometimes sends `"args": "{}"` as a string, which is
     * accepted here because rejecting it would be pedantic.
     */
    fun parseArguments(argumentsJson: String?): Map<String, Any?>? {
        if (argumentsJson.isNullOrBlank()) return emptyMap()

        val element = runCatching {
            argumentJson.parseToJsonElement(argumentsJson.trim())
        }.getOrNull()

        return when (element) {
            is kotlinx.serialization.json.JsonObject -> toMap(element)
            is kotlinx.serialization.json.JsonPrimitive -> {
                val inner = element.contentOrNull.orEmpty()
                val nested = runCatching {
                    argumentJson.parseToJsonElement(inner)
                }.getOrNull() as? kotlinx.serialization.json.JsonObject
                nested?.let { toMap(it) }
            }
            else -> null
        }
    }

    private fun toMap(obj: kotlinx.serialization.json.JsonObject): Map<String, Any?> =
        obj.entries.associate { (key, value) -> key to toValue(value) }

    private fun toValue(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonObject -> toMap(element)
        is kotlinx.serialization.json.JsonArray -> element.map { toValue(it) }
        is kotlinx.serialization.json.JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content == "null" -> null
            element.content.toLongOrNull() != null -> element.content.toLong()
            element.content.toDoubleOrNull() != null -> element.content.toDouble()
            else -> element.content
        }
        else -> null
    }

}

/**
 * Recognises the finish, ask_user and fail tools, and the JSON forms a model may use for them
 * when it has no native tool calling.
 */
private object JsonToolCallParserShim {
    fun parse(text: String, knownTools: Set<String>): DecisionParser.Decision? {
        val parsed = JsonToolCallParser.parse(text) ?: return null
        if (parsed.name !in knownTools) return null

        val args = DecisionParser.parseArguments(parsed.argsJson) ?: return null
        return DecisionParser.Decision.Act(parsed.name, args, parsed.argsJson)
    }
}
