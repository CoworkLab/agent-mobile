package com.phoneagent.tools

import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.SnapshotSerializer
import com.phoneagent.safety.RiskLevel
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Runs several simple actions in one model step.
 *
 * The design constraint that matters: a batch is **all or nothing**. The whole batch is
 * validated against the screen the model saw before anything runs, and if any action would
 * need confirmation the entire batch is refused. Executing the safe half of a batch would
 * leave the screen in a state the model never predicted, which is worse than doing nothing.
 *
 * While the batch runs, every action is checked again against the live screen. If a target
 * has moved, or the layout changed, the batch stops and reports which action stopped it.
 */
class BatchActionsTool(
    private val validator: BatchValidator,
    private val safetyGuard: SafetyGuard,
    private val registry: ToolRegistry,
    private val logger: Logger,
) : Tool {

    override val name = "batch_actions"
    override val description =
        "Run up to 5 simple actions in one step: tap, long_press, type_text, clear_text, " +
            "press_enter, scroll, swipe, wait. Use it only for predictable sequences on a " +
            "stable screen, such as filling a form. It is refused if any action is risky, " +
            "or if the screen has no readable structure. It never presses Send, Pay or Delete."

    override val parametersSchema = schemaOf(
        mapOf(
            "actions" to mapOf(
                "type" to "array",
                "description" to "The actions to run, in order. Maximum 5.",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "tool" to mapOf(
                            "type" to "string",
                            "description" to "One of: " +
                                "tap, long_press, type_text, clear_text, press_enter, " +
                                "scroll, swipe, wait",
                        ),
                        "args" to mapOf(
                            "type" to "object",
                            "description" to "The arguments for that tool, as usual",
                        ),
                    ),
                    "required" to listOf("tool", "args"),
                ),
            ),
        ),
        required = listOf("actions"),
    )

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val raw = call.mapList("actions")
            ?: return ToolResult.fail("actions is required and must be a list.")

        val actions = raw.mapNotNull { entry ->
            val tool = entry["tool"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val args = entry["args"] as? Map<String, Any?> ?: emptyMap()
            BatchAction(tool, args)
        }

        if (actions.size != raw.size) {
            return ToolResult.fail(
                "Every action needs a tool name and an args object.",
            )
        }

        // Validate against the screen the model was looking at, before touching anything.
        val snapshot = context.refreshScreen()
        val validation = validator.validate(actions, snapshot)
        if (!validation.accepted) {
            logger.log(LogLevel.WARN, TAG, "Batch refused: ${validation.reason}")
            return ToolResult.fail(validation.reason)
        }

        var baselineLayoutHash = LayoutHasher.layoutHash(snapshot.nodes)
        val results = mutableListOf<BatchStepResult>()

        actions.forEachIndexed { index, action ->
            // Re-check against the live screen right before running: the screen may have
            // moved between validation and this action.
            val live = context.refreshScreen()
            val liveValidation = validator.validate(listOf(action), live)
            if (!liveValidation.accepted) {
                results += BatchStepResult(
                    index = index,
                    tool = action.tool,
                    outcome = BatchStepResult.Outcome.SKIPPED,
                    message = "Stopped before this action: ${liveValidation.reason}",
                )
                return batchResult(results, live, stopped = index)
            }

            val identityCheck = checkTargetIdentity(action, live)
            if (identityCheck != null) {
                results += BatchStepResult(
                    index = index,
                    tool = action.tool,
                    outcome = BatchStepResult.Outcome.SKIPPED,
                    message = identityCheck,
                )
                return batchResult(results, live, stopped = index)
            }

            val stepResult = registry.execute(
                ToolCall(action.tool, action.args),
                context,
                timeoutMs = ToolRegistry.timeoutFor(action.tool),
            )

            results += BatchStepResult(
                index = index,
                tool = action.tool,
                outcome = if (stepResult.success) {
                    BatchStepResult.Outcome.DONE
                } else {
                    BatchStepResult.Outcome.FAILED
                },
                message = stepResult.message,
            )

            if (!stepResult.success) {
                return batchResult(results, context.refreshScreen(), stopped = index)
            }

            // A layout that changed by more than a keyboard appearing means the screen moved
            // under the batch; the remaining actions were planned against the old screen.
            val after = context.refreshScreen()
            val afterHash = LayoutHasher.layoutHash(after.nodes)
            if (afterHash != baselineLayoutHash && !isKeyboardOnlyChange(snapshot, after)) {
                results += BatchStepResult(
                    index = index,
                    tool = action.tool,
                    outcome = BatchStepResult.Outcome.DONE,
                    message = "Layout changed after this action, so the batch stopped here.",
                )
                return batchResult(results, after, stopped = index + 1)
            }
            baselineLayoutHash = afterHash
        }

        return batchResult(results, context.refreshScreen(), stopped = null)
    }

    /**
     * Confirms the node a mark pointed at is still there and still in the same place.
     *
     * A mark is only meaningful against the screen it came from. If the node is gone, or has
     * moved more than a small tolerance, the action would hit the wrong thing.
     */
    private fun checkTargetIdentity(action: BatchAction, live: ScreenSnapshot): String? {
        val mark = (action.args["mark"] as? Number)?.toInt() ?: return null
        val expectedHash = action.args["node_hash"] as? String
        val node = live.nodeForMark(mark)

        if (node == null) {
            return "Element [$mark] is no longer on screen, so the batch stopped."
        }
        if (expectedHash != null && node.identityHash() != expectedHash) {
            return "Element [$mark] changed, so the batch stopped rather than tapping blind."
        }
        return null
    }

    /**
     * A keyboard appearing or disappearing moves the lower half of the screen but does not
     * invalidate a batch. This recognises that case by checking whether the change is confined
     * to the bottom of the screen.
     */
    private fun isKeyboardOnlyChange(before: ScreenSnapshot, after: ScreenSnapshot): Boolean {
        if (before.nodes.isEmpty() || after.nodes.isEmpty()) return false
        val beforeBottom = before.nodes.maxOfOrNull { it.bounds.bottom } ?: 0
        val afterBottom = after.nodes.maxOfOrNull { it.bounds.bottom } ?: 0
        val screenHeight = maxOf(beforeBottom, afterBottom)
        if (screenHeight == 0) return false

        // If the set of classes and the count are the same, and only vertical positions in
        // the lower part changed, treat it as a keyboard show or hide.
        val beforeClasses = before.nodes.map { it.className ?: "" }.sorted()
        val afterClasses = after.nodes.map { it.className ?: "" }.sorted()
        if (beforeClasses != afterClasses) return false

        val moved = before.nodes.zip(after.nodes).count { (b, a) ->
            kotlin.math.abs(b.bounds.top - a.bounds.top) > 24
        }
        val movedInLowerHalf = before.nodes.zip(after.nodes).count { (b, a) ->
            kotlin.math.abs(b.bounds.top - a.bounds.top) > 24 && b.bounds.top > screenHeight / 2
        }
        return moved > 0 && moved == movedInLowerHalf
    }

    private fun batchResult(
        steps: List<BatchStepResult>,
        snapshot: ScreenSnapshot,
        stopped: Int?,
    ): ToolResult {
        val done = steps.count { it.outcome == BatchStepResult.Outcome.DONE }
        val failed = steps.count { it.outcome == BatchStepResult.Outcome.FAILED }

        val summary = buildString {
            append("$done of ${steps.size} actions done")
            if (failed > 0) append(", $failed failed")
            if (stopped != null) append(", stopped at action ${stopped + 1}")
            append(".\n")
            steps.forEach { step ->
                val marker = when (step.outcome) {
                    BatchStepResult.Outcome.DONE -> "ok"
                    BatchStepResult.Outcome.FAILED -> "failed"
                    BatchStepResult.Outcome.SKIPPED -> "skipped"
                }
                append("  ${step.index + 1}. ${step.tool} [$marker] ${step.message}\n")
            }
            append("\nThe screen now:\n")
            append(SnapshotSerializer.toPromptText(snapshot, maxNodes = 40))
        }

        // Any early stop is a failure, even when earlier actions succeeded. The batch was a
        // single planned unit; a half-finished one means the screen is not where the model
        // expected, so it must re-observe and re-plan rather than carry on.
        return if (stopped != null || failed > 0 || done != steps.size) {
            ToolResult.fail(summary)
        } else {
            ToolResult.ok(summary, screenChanged = true)
        }
    }

    companion object {
        const val TAG = "BatchActions"
    }
}

/**
 * A stable identity for a node, used to confirm a mark still points at the same thing.
 *
 * View IDs survive a re-read; text and bounds do not, so they are only a fallback for nodes
 * without an ID.
 */
fun com.phoneagent.perception.UiNode.identityHash(): String {
    val basis = viewId ?: listOfNotNull(className, text, contentDescription).joinToString("|")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$nodeId|$basis".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
}
