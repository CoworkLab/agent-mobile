package com.phoneagent.tools

import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.safety.RiskLevel

/**
 * The touch tools: tap, long press, double tap, swipe, scroll, drag, pinch.
 *
 * All of them are LOW risk on their own. Their real risk comes from the node they touch, so
 * the guard looks at the target and a "Send" button makes the tap HIGH.
 */
class TouchTools(
    private val screenSize: () -> Pair<Int, Int>,
) {

    val all: List<Tool> = listOf(
        TapTool(screenSize),
        LongPressTool(screenSize),
        DoubleTapTool(screenSize),
        SwipeTool(screenSize),
        ScrollTool(screenSize),
        DragTool(screenSize),
        PinchTool(screenSize),
    )

    private abstract class BaseTouchTool(
        final override val name: String,
        final override val description: String,
        override val parametersSchema: Map<String, Any?>,
        protected val screenSize: () -> Pair<Int, Int>,
    ) : Tool {

        override val allowedInBatch: Boolean get() = name in BATCHABLE

        protected fun resolve(
            call: ToolCall,
            context: ToolContext,
        ): Result<NodeResolver.Resolved> {
            val (width, height) = screenSize()
            val resolved = NodeResolver.resolve(
                mark = call.int("mark"),
                x = call.int("x"),
                y = call.int("y"),
                nodes = context.currentNodes(),
                screenWidth = width,
                screenHeight = height,
            )
            return resolved
        }

        protected fun failureMessage(error: Throwable): String =
            error.message ?: "The action could not be performed."

        private companion object {
            val BATCHABLE = setOf("tap", "long_press", "swipe", "scroll", "press_enter")
        }
    }

    private class TapTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "tap",
        description = "Tap an element by its mark, or at an x,y position. " +
            "Prefer the mark shown on the screenshot.",
        parametersSchema = schemaOf(
            mapOf(
                "mark" to intProperty("Number of the element to tap, from the screenshot"),
                "x" to intProperty("X coordinate, when there is no mark"),
                "y" to intProperty("Y coordinate, when there is no mark"),
            ),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val target = resolve(call, context).getOrElse { return ToolResult.fail(failureMessage(it)) }
            val ok = context.actuator.tap(target.x, target.y)
            return if (ok) {
                ToolResult.ok("Tapped ${describe(target)}", screenChanged = null)
            } else {
                ToolResult.fail("The tap was not accepted by the system.")
            }
        }

        private fun describe(target: NodeResolver.Resolved): String {
            val node = target.node
            val label = node?.let { it.text ?: it.contentDescription ?: it.viewId }
            val mark = node?.mark
            return if (mark != null) {
                "element [$mark]" + (label?.let { " (\"$it\")" } ?: "")
            } else {
                "(${target.x}, ${target.y})"
            }
        }
    }

    private class LongPressTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "long_press",
        description = "Press and hold an element, for context menus and drag handles.",
        parametersSchema = schemaOf(
            mapOf(
                "mark" to intProperty("Number of the element to hold"),
                "x" to intProperty("X coordinate"),
                "y" to intProperty("Y coordinate"),
                "duration_ms" to intProperty("How long to hold, default 600"),
            ),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val target = resolve(call, context).getOrElse { return ToolResult.fail(failureMessage(it)) }
            val duration = (call.int("duration_ms") ?: 600).toLong().coerceIn(300, 5_000)
            val ok = context.actuator.longPress(target.x, target.y, duration)
            return if (ok) ToolResult.ok("Held for ${duration}ms") else ToolResult.fail("Hold failed.")
        }
    }

    private class DoubleTapTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "double_tap",
        description = "Tap twice quickly, to zoom or select a word.",
        parametersSchema = schemaOf(
            mapOf(
                "mark" to intProperty("Number of the element"),
                "x" to intProperty("X coordinate"),
                "y" to intProperty("Y coordinate"),
            ),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val target = resolve(call, context).getOrElse { return ToolResult.fail(failureMessage(it)) }
            val ok = context.actuator.doubleTap(target.x, target.y)
            return if (ok) ToolResult.ok("Double tapped") else ToolResult.fail("Double tap failed.")
        }
    }

    private class SwipeTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "swipe",
        description = "Swipe from one point to another, for carousels and dismissing cards.",
        parametersSchema = schemaOf(
            mapOf(
                "from_x" to intProperty("Start X"),
                "from_y" to intProperty("Start Y"),
                "to_x" to intProperty("End X"),
                "to_y" to intProperty("End Y"),
                "duration_ms" to intProperty("Duration, default 300"),
            ),
            required = listOf("from_x", "from_y", "to_x", "to_y"),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val (width, height) = screenSize()
            val fromX = call.int("from_x") ?: return ToolResult.fail("from_x is required.")
            val fromY = call.int("from_y") ?: return ToolResult.fail("from_y is required.")
            val toX = call.int("to_x") ?: return ToolResult.fail("to_x is required.")
            val toY = call.int("to_y") ?: return ToolResult.fail("to_y is required.")

            if (listOf(fromX, toX).any { it !in -8..width + 8 } ||
                listOf(fromY, toY).any { it !in -8..height + 8 }
            ) {
                return ToolResult.fail(
                    "Swipe points must be inside the screen (0,0-$width,$height).",
                )
            }

            val duration = (call.int("duration_ms") ?: 300).toLong().coerceIn(50, 5_000)
            val ok = context.actuator.swipe(fromX, fromY, toX, toY, duration)
            return if (ok) {
                ToolResult.ok("Swiped from ($fromX,$fromY) to ($toX,$toY)")
            } else {
                ToolResult.fail("Swipe failed.")
            }
        }
    }

    private class ScrollTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "scroll",
        description = "Scroll a list or page in a direction. Use a mark to scroll inside a " +
            "specific list, otherwise the whole screen scrolls.",
        parametersSchema = schemaOf(
            mapOf(
                "direction" to mapOf(
                    "type" to "string",
                    "enum" to listOf("up", "down", "left", "right"),
                    "description" to "Which way to move the content",
                ),
                "mark" to intProperty("Optional element to scroll inside"),
            ),
            required = listOf("direction"),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val direction = when (call.string("direction")?.lowercase()) {
                "up" -> ScrollDirection.UP
                "down" -> ScrollDirection.DOWN
                "left" -> ScrollDirection.LEFT
                "right" -> ScrollDirection.RIGHT
                else -> return ToolResult.fail(
                    "direction must be one of up, down, left or right.",
                )
            }

            val mark = call.int("mark")
            val nodeId = mark?.let { markValue ->
                context.currentNodes().firstOrNull { it.mark == markValue }?.nodeId
            }
            if (mark != null && nodeId == null) {
                return ToolResult.fail(NodeResolver.Failure.NoSuchMark(mark).message.orEmpty())
            }

            val ok = context.actuator.scroll(nodeId, direction)
            return if (ok) ToolResult.ok("Scrolled ${direction.name.lowercase()}") else ToolResult.fail("Scroll failed.")
        }
    }

    private class DragTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "drag",
        description = "Press, move and release, for sliders and trim handles. Use a longer " +
            "duration for fine control.",
        parametersSchema = schemaOf(
            mapOf(
                "from_x" to intProperty("Start X"),
                "from_y" to intProperty("Start Y"),
                "to_x" to intProperty("End X"),
                "to_y" to intProperty("End Y"),
                "duration_ms" to intProperty("Duration, default 800; longer is finer"),
            ),
            required = listOf("from_x", "from_y", "to_x", "to_y"),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val (width, height) = screenSize()
            val fromX = call.int("from_x") ?: return ToolResult.fail("from_x is required.")
            val fromY = call.int("from_y") ?: return ToolResult.fail("from_y is required.")
            val toX = call.int("to_x") ?: return ToolResult.fail("to_x is required.")
            val toY = call.int("to_y") ?: return ToolResult.fail("to_y is required.")

            if (listOf(fromX, toX).any { it !in -8..width + 8 } ||
                listOf(fromY, toY).any { it !in -8..height + 8 }
            ) {
                return ToolResult.fail(
                    "Drag points must be inside the screen (0,0-$width,$height).",
                )
            }

            val duration = (call.int("duration_ms") ?: 800).toLong().coerceIn(200, 10_000)
            val ok = context.actuator.drag(fromX, fromY, toX, toY, duration)
            return if (ok) {
                ToolResult.ok("Dragged from ($fromX,$fromY) to ($toX,$toY) over ${duration}ms")
            } else {
                ToolResult.fail("Drag failed.")
            }
        }
    }

    private class PinchTool(screenSize: () -> Pair<Int, Int>) : BaseTouchTool(
        name = "pinch",
        description = "Zoom in or out with two fingers.",
        parametersSchema = schemaOf(
            mapOf(
                "direction" to mapOf(
                    "type" to "string",
                    "enum" to listOf("in", "out"),
                    "description" to "in to zoom in, out to zoom out",
                ),
                "x" to intProperty("Centre X, defaults to screen centre"),
                "y" to intProperty("Centre Y, defaults to screen centre"),
            ),
            required = listOf("direction"),
        ),
        screenSize = screenSize,
    ) {
        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val out = when (call.string("direction")?.lowercase()) {
                "in" -> false
                "out" -> true
                else -> return ToolResult.fail("direction must be in or out.")
            }
            val (width, height) = screenSize()
            val centerX = call.int("x") ?: width / 2
            val centerY = call.int("y") ?: height / 2
            val ok = context.actuator.pinch(centerX, centerY, out)
            return if (ok) {
                ToolResult.ok(if (out) "Zoomed out" else "Zoomed in")
            } else {
                ToolResult.fail("Zoom failed.")
            }
        }
    }
}
