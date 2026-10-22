package com.phoneagent.tools

import kotlinx.coroutines.delay

/**
 * Waiting and terminal tools.
 *
 * `wait_for` polls the screen for a phrase instead of sleeping a fixed time, which is the
 * difference between a reliable step and a race. It gives up after its timeout and says so,
 * rather than failing silently.
 */
class WaitTools(
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    val all: List<Tool> = listOf(WaitTool(sleeper), WaitForTool(sleeper))

    private class WaitTool(private val sleeper: suspend (Long) -> Unit) : Tool {
        override val name = "wait"
        override val description = "Wait for a screen to load. At most 10 seconds."

        override val parametersSchema = schemaOf(
            mapOf("seconds" to numberProperty("How long to wait, maximum 10")),
            required = listOf("seconds"),
        )

        override val allowedInBatch = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val seconds = call.double("seconds") ?: return ToolResult.fail("seconds is required.")
            if (seconds <= 0) return ToolResult.fail("seconds must be greater than 0.")
            val capped = seconds.coerceAtMost(MAX_WAIT_SECONDS)
            sleeper((capped * 1000).toLong())
            val note = if (seconds > MAX_WAIT_SECONDS) {
                " (asked for ${seconds}s, capped at ${MAX_WAIT_SECONDS.toInt()}s)"
            } else {
                ""
            }
            return ToolResult.ok("Waited ${capped}s$note")
        }
    }

    private class WaitForTool(private val sleeper: suspend (Long) -> Unit) : Tool {
        override val name = "wait_for"
        override val description =
            "Wait until some text appears on screen. Better than a fixed wait when a " +
                "page is loading."

        override val parametersSchema = schemaOf(
            mapOf(
                "text" to stringProperty("The text to wait for"),
                "timeout_seconds" to numberProperty("Give up after this long, default 10"),
            ),
            required = listOf("text"),
        )

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val text = call.string("text")?.trim()
                ?: return ToolResult.fail("text is required.")
            if (text.isEmpty()) return ToolResult.fail("text is required.")

            val timeoutSeconds = (call.double("timeout_seconds") ?: DEFAULT_TIMEOUT)
                .coerceIn(1.0, MAX_TIMEOUT_SECONDS)
            val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()

            while (System.currentTimeMillis() < deadline) {
                val found = context.currentNodes().any { node ->
                    node.text?.contains(text, ignoreCase = true) == true ||
                        node.contentDescription?.contains(text, ignoreCase = true) == true
                }
                if (found) return ToolResult.ok("\"$text\" appeared", screenChanged = true)
                sleeper(POLL_INTERVAL_MS)
                context.refreshScreen()
            }

            return ToolResult.fail(
                "\"$text\" did not appear within ${timeoutSeconds.toInt()}s. " +
                    "Check the screen and try something else.",
            )
        }
    }

    companion object {
        const val MAX_WAIT_SECONDS = 10.0
        const val MAX_TIMEOUT_SECONDS = 60.0
        const val DEFAULT_TIMEOUT = 10.0
        const val POLL_INTERVAL_MS = 400L
    }
}
