package com.phoneagent.tools

import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.RiskAssessment
import com.phoneagent.safety.RiskLevel
import com.phoneagent.safety.SafetyGuard

/** One entry of a `batch_actions` call. */
data class BatchAction(val tool: String, val args: Map<String, Any?>)

/** Per-action outcome, so the model can see exactly which step stopped the batch. */
data class BatchStepResult(
    val index: Int,
    val tool: String,
    val outcome: Outcome,
    val message: String,
) {
    enum class Outcome { DONE, FAILED, SKIPPED }
}

data class BatchValidation(
    val accepted: Boolean,
    val reason: String,
    val perAction: List<StepCheck> = emptyList(),
)

data class StepCheck(val index: Int, val tool: String, val assessment: RiskAssessment)

/**
 * Validates a `batch_actions` call before anything runs.
 *
 * The safety rule that shapes this class: **risky actions never run inside a batch**. If any
 * action would need confirmation, the whole batch is refused and the model is told to do
 * that action as a separate step. Refusing the batch rather than the single action matters,
 * because a half-executed batch leaves the screen in a state the model never predicted.
 */
class BatchValidator(
    private val safetyGuard: SafetyGuard,
    private val maxActions: Int = MAX_ACTIONS,
) {

    /**
     * Tools allowed inside a batch: simple, predictable, and never terminal. Deliberately a
     * allow-list, so a new tool cannot join a batch by accident.
     */
    private val allowedTools = setOf(
        "tap",
        "long_press",
        "type_text",
        "clear_text",
        "press_enter",
        "scroll",
        "swipe",
        "wait",
    )

    fun validate(
        actions: List<BatchAction>,
        snapshot: ScreenSnapshot,
    ): BatchValidation {
        if (actions.isEmpty()) {
            return BatchValidation(false, "The batch is empty.")
        }
        if (actions.size > maxActions) {
            return BatchValidation(
                false,
                "A batch can hold at most $maxActions actions; this one has ${actions.size}.",
            )
        }

        // Marks are only trustworthy when the tree is real. On a custom-drawn screen the
        // numbers in the screenshot do not map to nodes, so a batch would tap blind.
        if (snapshot.mode == PerceptionMode.SCREENSHOT_ONLY) {
            return BatchValidation(
                false,
                "This screen has no readable structure, so batch actions are unsafe here. " +
                    "Do them one at a time.",
            )
        }
        if (!snapshot.hasTree) {
            return BatchValidation(
                false,
                "There is no readable screen structure, so batch actions are unsafe here.",
            )
        }

        val checks = mutableListOf<StepCheck>()

        actions.forEachIndexed { index, action ->
            val tool = action.tool

            if (tool !in allowedTools) {
                return BatchValidation(
                    accepted = false,
                    reason = "\"$tool\" cannot be used inside a batch. " +
                        "Do it as its own step.",
                    perAction = checks,
                )
            }
            if (tool == "wait") {
                val seconds = (action.args["seconds"] as? Number)?.toDouble() ?: 0.0
                if (seconds > MAX_BATCH_WAIT_SECONDS) {
                    return BatchValidation(
                        accepted = false,
                        reason = "A batch can wait at most ${MAX_BATCH_WAIT_SECONDS.toInt()}s.",
                        perAction = checks,
                    )
                }
            }

            val target = targetFor(action, snapshot)
            val toolRisk = if (tool == "type_text") RiskLevel.MEDIUM else RiskLevel.LOW
            val assessment = safetyGuard.assessToolAndTarget(tool, toolRisk, target)
            checks += StepCheck(index, tool, assessment)

            if (assessment.isBlocked || assessment.requiresUserApproval) {
                return BatchValidation(
                    accepted = false,
                    reason = "Do the risky action as a separate step. " +
                        "Action ${index + 1} (\"$tool\") ${assessment.reason.lowercase()}",
                    perAction = checks,
                )
            }
        }

        return BatchValidation(true, "Batch accepted", checks)
    }

    private fun targetFor(action: BatchAction, snapshot: ScreenSnapshot): com.phoneagent.safety.RiskTarget? {
        val mark = (action.args["mark"] as? Number)?.toInt()
        val node = mark?.let { snapshot.nodeForMark(it) }
        return com.phoneagent.safety.RiskTarget(
            text = node?.text,
            contentDescription = node?.contentDescription,
            viewId = node?.viewId,
            packageName = snapshot.packageName,
            isPasswordField = node?.password == true,
            isEditable = node?.editable == true,
        )
    }

    companion object {
        const val MAX_ACTIONS = 5
        const val MAX_BATCH_WAIT_SECONDS = 2.0
    }
}

/**
 * A `layoutHash` over structure only: node classes, bounds and the clickable/scrollable flags.
 *
 * The text of editable fields is deliberately excluded, because typing changes it and that
 * would look like the layout shifted. Two nodes with the same structure but different typed
 * text must hash the same, which is what makes "did the screen move under me?" a meaningful
 * question while a batch is running.
 */
object LayoutHasher {

    fun layoutHash(nodes: List<UiNode>): String {
        val canonical = buildString {
            nodes.forEach { node ->
                append(node.className ?: "").append('|')
                append(node.bounds.left).append(',').append(node.bounds.top).append(',')
                    .append(node.bounds.right).append(',').append(node.bounds.bottom).append('|')
                if (node.clickable) append("c")
                if (node.scrollable) append("s")
                if (node.longClickable) append("l")
                if (!node.editable) {
                    append(node.text ?: "")
                }
                append(';')
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Whether a node is still where it was. A small tolerance absorbs sub-pixel layout
     * jitter and the shift caused by a keyboard appearing at the bottom of the screen.
     */
    fun movedTooFar(before: UiNode, after: UiNode, tolerancePx: Int = 24): Boolean {
        val dx = kotlin.math.abs(before.bounds.left - after.bounds.left)
        val dy = kotlin.math.abs(before.bounds.top - after.bounds.top)
        val dw = kotlin.math.abs(before.bounds.width - after.bounds.width)
        val dh = kotlin.math.abs(before.bounds.height - after.bounds.height)
        return dx > tolerancePx || dy > tolerancePx || dw > tolerancePx || dh > tolerancePx
    }
}
