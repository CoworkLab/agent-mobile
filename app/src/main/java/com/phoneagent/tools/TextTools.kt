package com.phoneagent.tools

import com.phoneagent.safety.RiskLevel

/**
 * The text tools.
 *
 * Typing is MEDIUM because it changes state. Hindi and other non-Latin text must work, which
 * is why the actuator sets the text directly on the node instead of simulating key presses;
 * key events cannot produce Devanagari.
 */
class TextTools(
    private val screenSize: () -> Pair<Int, Int>,
) {

    val all: List<Tool> = listOf(
        TypeTextTool(screenSize),
        ClearTextTool(screenSize),
        PressEnterTool(screenSize),
    )

    private class TypeTextTool(private val screenSize: () -> Pair<Int, Int>) : Tool {
        override val name = "type_text"
        override val description =
            "Type text into a field. Works with Hindi and other non-Latin scripts. " +
                "Set clear_first to replace what is already there."

        override val parametersSchema = schemaOf(
            mapOf(
                "mark" to intProperty("Number of the text field"),
                "x" to intProperty("X coordinate of the field, when there is no mark"),
                "y" to intProperty("Y coordinate of the field, when there is no mark"),
                "text" to stringProperty("The text to type"),
                "clear_first" to boolProperty("Replace existing text, default true"),
                "press_enter" to boolProperty("Press enter after typing, default false"),
            ),
            required = listOf("text"),
        )

        override val riskLevel = RiskLevel.MEDIUM
        override val allowedInBatch = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val text = call.string("text")
                ?: return ToolResult.fail("text is required.")

            val target = resolveField(call, context)
                .getOrElse { return ToolResult.fail(it.message ?: "No text field found.") }

            val node = target.node
                ?: return ToolResult.fail(
                    "type_text needs a mark for the text field, because a bare position " +
                        "cannot receive text.",
                )

            val clearFirst = call.bool("clear_first") ?: true
            val ok = context.actuator.setText(node.nodeId, text, clearFirst)
            if (!ok) {
                return ToolResult.fail(
                    "The field did not accept the text. Tap it first so the keyboard appears.",
                )
            }

            if (call.bool("press_enter") == true) {
                context.actuator.pressEnter(node.nodeId)
            }

            val preview = if (text.length > 40) text.take(40) + "..." else text
            return ToolResult.ok(
                "Typed \"$preview\" into ${node.viewId ?: "the field"}",
                screenChanged = null,
            )
        }

        private fun resolveField(
            call: ToolCall,
            context: ToolContext,
        ): Result<NodeResolver.Resolved> {
            val (width, height) = screenSize()
            return NodeResolver.resolve(
                mark = call.int("mark"),
                x = call.int("x"),
                y = call.int("y"),
                nodes = context.currentNodes(),
                screenWidth = width,
                screenHeight = height,
            )
        }
    }

    private class ClearTextTool(@Suppress("unused") private val screenSize: () -> Pair<Int, Int>) : Tool {
        override val name = "clear_text"
        override val description = "Empty a text field."

        override val parametersSchema = schemaOf(
            mapOf("mark" to intProperty("Number of the text field")),
            required = listOf("mark"),
        )

        override val riskLevel = RiskLevel.MEDIUM
        override val allowedInBatch = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val mark = call.int("mark") ?: return ToolResult.fail("mark is required.")
            val node = context.currentNodes().firstOrNull { it.mark == mark }
                ?: return ToolResult.fail(NodeResolver.Failure.NoSuchMark(mark).message.orEmpty())
            if (!node.editable) {
                return ToolResult.fail("Element [$mark] is not a text field.")
            }
            val ok = context.actuator.clearText(node.nodeId)
            return if (ok) ToolResult.ok("Cleared the field") else ToolResult.fail("Could not clear it.")
        }
    }

    private class PressEnterTool(@Suppress("unused") private val screenSize: () -> Pair<Int, Int>) : Tool {
        override val name = "press_enter"
        override val description = "Press the enter or search key, to submit a search box."

        override val parametersSchema = schemaOf(
            mapOf("mark" to intProperty("Optional field to press enter in")),
        )

        override val allowedInBatch = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val nodeId = call.int("mark")?.let { mark ->
                context.currentNodes().firstOrNull { it.mark == mark }?.nodeId
                    ?: return ToolResult.fail(NodeResolver.Failure.NoSuchMark(mark).message.orEmpty())
            }
            val ok = context.actuator.pressEnter(nodeId)
            return if (ok) ToolResult.ok("Pressed enter") else ToolResult.fail("Enter failed.")
        }
    }
}
