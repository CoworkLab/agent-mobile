package com.phoneagent.tools

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Holds every tool and runs them safely.
 *
 * Two things this class guarantees, both learned from watching agents fail:
 *  - A tool can never hang the loop. Every call is bounded by a timeout, and a timeout is
 *    reported as a normal failure so the model can try something else.
 *  - A tool can never crash the loop. Any exception, including a `CancellationException`
 *    caused by STOP, is turned into a result; the caller decides what to do with it.
 */
open class ToolRegistry(
    private val logger: Logger,
    tools: List<Tool>,
) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    open val all: List<Tool> get() = byName.values.toList()

    open fun get(name: String): Tool? = byName[name]

    open fun names(): Set<String> = byName.keys

    /** Only the tools that may appear inside `batch_actions`. */
    open fun batchableNames(): Set<String> =
        byName.values.filter { it.allowedInBatch }.map { it.name }.toSet()

    /**
     * The tool definitions sent to the model. `get_screen` and the memory tools are omitted
     * when they would waste the model's attention; the caller passes the list it wants.
     */
    open fun definitions(): List<Map<String, Any?>> = all.map { tool ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parametersSchema,
            ),
        )
    }

    /**
     * Runs one call.
     *
     * The timeout is per-tool: a tap should not take 30 seconds, while a wait for text may
     * legitimately take longer. Unknown tools are reported as a failure that lists the valid
     * names, because the model's most common mistake is inventing a tool name.
     */
    open suspend fun execute(
        call: ToolCall,
        context: ToolContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ToolResult {
        val tool = byName[call.name]
            ?: return ToolResult.fail(
                "There is no tool called \"${call.name}\". Available: " +
                    byName.keys.sorted().joinToString(", ") + ".",
            )

        val started = System.currentTimeMillis()
        return try {
            val result = withTimeout(timeoutMs) { tool.execute(call, context) }
            val elapsed = System.currentTimeMillis() - started
            logger.log(
                if (result.success) LogLevel.INFO else LogLevel.WARN,
                TAG,
                "${tool.name} ${if (result.success) "ok" else "failed"} in ${elapsed}ms: " +
                    result.message.take(120),
            )
            result
        } catch (timeout: TimeoutCancellationException) {
            logger.log(LogLevel.WARN, TAG, "${tool.name} timed out after ${timeoutMs}ms")
            ToolResult.fail(
                "${tool.name} took too long (${timeoutMs / 1000}s) and was stopped. " +
                    "Try a smaller step.",
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // STOP arrived while the tool was running. Rethrow so the loop unwinds, but log
            // it first so the log shows why the run ended mid-action.
            logger.log(LogLevel.INFO, TAG, "${tool.name} cancelled")
            throw cancellation
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "${tool.name} threw ${error::class.simpleName}: ${error.message}")
            ToolResult.fail("${tool.name} failed: ${error.message ?: error::class.simpleName}")
        }
    }

    companion object {
        const val TAG = "ToolRegistry"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val WAIT_TOOL_TIMEOUT_MS = 70_000L

        /** Long waits need the longer budget; everything else uses the default. */
        fun timeoutFor(toolName: String): Long =
            if (toolName == "wait_for") WAIT_TOOL_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
    }
}
