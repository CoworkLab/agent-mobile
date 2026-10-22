package com.phoneagent.agent

import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolResult
import com.phoneagent.tools.schemaOf
import com.phoneagent.tools.stringProperty

/**
 * The three tools that end a step in something other than "act again".
 *
 * They live in the agent package rather than the tools package because they are the loop's
 * vocabulary, not the phone's. `ToolRegistry` still owns them, so the model sees them in the
 * same list as `tap` and cannot tell the difference.
 */

/**
 * Declares the task done.
 *
 * The summary is required and is shown to the user, which forces the model to say what it
 * actually did rather than just signalling completion.
 */
class FinishTool : Tool {
    override val name = "finish"
    override val description =
        "Call this only when the goal is achieved. Check the screen first: do not call it " +
            "because you are stuck or because the task looks finished. The summary is shown " +
            "to the user, so write one short sentence in their language."

    override val parametersSchema = schemaOf(
        mapOf(
            "summary" to stringProperty("One short sentence describing what was done"),
        ),
        required = listOf("summary"),
    )

    override val isReadOnly = true

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val summary = call.string("summary")?.trim()
            ?: return ToolResult.fail("Give a one-sentence summary of what was done.")
        if (summary.isBlank()) return ToolResult.fail("The summary cannot be empty.")
        return ToolResult.ok(summary)
    }
}

/**
 * Pauses and asks the human.
 *
 * The question is capped in length and the tool says so, because a long question on a phone
 * notification is not readable. The brief's rule is a short question, so the cap enforces it
 * rather than relying on the prompt alone.
 */
class AskUserTool : Tool {
    override val name = "ask_user"
    override val description =
        "Ask the user one short question when the goal is ambiguous, when a choice has real " +
            "consequences, or when you cannot proceed without a fact only they know, such as " +
            "which contact to message. Do not use it to confirm an ordinary safe action."

    override val parametersSchema = schemaOf(
        mapOf(
            "question" to stringProperty("One short question, in the user's language"),
            "options" to mapOf(
                "type" to "array",
                "description" to "Optional short answers the user can pick from",
                "items" to mapOf("type" to "string"),
            ),
        ),
        required = listOf("question"),
    )

    override val isReadOnly = true

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val question = call.string("question")?.trim()
            ?: return ToolResult.fail("Give a question to ask the user.")
        if (question.isBlank()) return ToolResult.fail("The question cannot be empty.")
        if (question.length > MAX_QUESTION_CHARS) {
            return ToolResult.fail(
                "That question is too long for a phone notification. " +
                    "Keep it under $MAX_QUESTION_CHARS characters and ask the most " +
                    "important thing first.",
            )
        }
        return ToolResult.ok(question)
    }

    private companion object {
        const val MAX_QUESTION_CHARS = 140
    }
}

/**
 * Gives up, with the reason.
 *
 * This exists so the model has a legitimate way to stop when a task is impossible. Without
 * it, a blocked app or a missing feature pushes the model into retrying until a limit fires,
 * which wastes the user's quota and produces a worse explanation.
 */
class FailTool : Tool {
    override val name = "fail"
    override val description =
        "Give up on the goal and explain why, in one short sentence in the user's language. " +
            "Use this when the task cannot be done: the app is blocked, the feature does not " +
            "exist, or the user's account lacks access. Never use it because a step failed."

    override val parametersSchema = schemaOf(
        mapOf(
            "reason" to stringProperty("One short sentence explaining why it cannot be done"),
        ),
        required = listOf("reason"),
    )

    override val isReadOnly = true

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val reason = call.string("reason")?.trim()
            ?: return ToolResult.fail("Give a reason.")
        if (reason.isBlank()) return ToolResult.fail("The reason cannot be empty.")
        return ToolResult.ok(reason)
    }
}

/**
 * Writes and revises the plan.
 *
 * Available to the model rather than only used internally, because the model is the one that
 * learns the screen is not what it assumed, and it should be able to say so in a new plan.
 */
class UpdatePlanTool : Tool {
    override val name = "update_plan"
    override val description =
        "Replace your plan with a better one, 3 to 7 short steps, after something you " +
            "learned made the current plan wrong. Use this instead of repeating a failing " +
            "approach."

    override val parametersSchema = schemaOf(
        mapOf(
            "steps" to mapOf(
                "type" to "array",
                "description" to "3 to 7 short steps",
                "items" to mapOf("type" to "string"),
            ),
        ),
        required = listOf("steps"),
    )

    override val isReadOnly = true

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val steps = call.stringList("steps") ?: return ToolResult.fail("Give a list of steps.")
        val cleaned = steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ToolResult.fail("The plan cannot be empty.")
        if (cleaned.size > AgentPlan.MAX_PLAN_LINES) {
            return ToolResult.fail(
                "Keep the plan to ${AgentPlan.MAX_PLAN_LINES} steps or fewer.",
            )
        }
        return ToolResult.ok(
            "Plan replaced:\n" + cleaned.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
        )
    }
}
