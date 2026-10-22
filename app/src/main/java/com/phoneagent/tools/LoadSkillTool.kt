package com.phoneagent.tools

import com.phoneagent.memory.SkillLibrary
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Reads one skill in full.
 *
 * The prompt carries only the index, so this is how the model gets the detail. It is
 * deliberately the only way: a library of twenty skills would otherwise put twenty documents
 * in every prompt, which is expensive and makes the model's attention worse.
 *
 * This tool changes nothing on the screen, so it is safe to call as often as the model likes.
 */
class LoadSkillTool(
    private val library: SkillLibrary,
    private val logger: Logger,
) : Tool {

    override val name = "load_skill"

    override val description =
        "Read one skill in full. The list of skills shows only names; call this to read the " +
            "steps of the one that fits the current situation. This does not change the screen."

    override val parametersSchema = schemaOf(
        mapOf(
            "id" to stringProperty(
                "The skill id from the list, such as \"dismiss-popups-and-permission-dialogs\".",
            ),
        ),
        required = listOf("id"),
    )

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val id = (call.args["id"] as? String)?.trim().orEmpty()
        if (id.isBlank()) {
            return ToolResult.fail("Give the id of the skill to read.")
        }

        // Accept the name as well as the id, because the model may repeat what it saw.
        val resolved = library.get(id)
            ?: library.all().firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: return ToolResult.fail(
                "There is no skill called \"$id\". Use one of the ids from the list, or " +
                    "continue without a skill.",
            )

        // A draft is a refusal, not a successful read with an apology in the text. Saying
        // "success" here would let the model believe it had the procedure when it did not.
        if (!resolved.status.isUsable) {
            return ToolResult.fail(
                "\"${resolved.name}\" has not been approved by the user yet, so it cannot be used.",
            )
        }

        val text = library.read(resolved.id)
        logger.log(LogLevel.INFO, TAG, "Read skill ${resolved.id}")

        return ToolResult.ok(
            message = text,
            // Nothing on the screen changed, and saying otherwise would make the loop wait for
            // a change that is never going to come.
            screenChanged = false,
        )
    }

    private companion object {
        const val TAG = "LoadSkillTool"
    }
}
