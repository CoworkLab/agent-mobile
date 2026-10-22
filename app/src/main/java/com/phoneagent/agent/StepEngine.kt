package com.phoneagent.agent

import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.safety.RiskLevel
import com.phoneagent.safety.RiskTarget
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.tools.ToolResult

/**
 * Runs one turn of `decide -> guard -> act -> verify`.
 *
 * This is the piece worth testing hard, because it is where the safety rules and the loop
 * meet. It is written as a pure function of its inputs: it reads the screen through a
 * callback and performs actions through the registry, so a JVM test can drive a whole
 * scenario with fakes and no device.
 *
 * The two rules that shape it:
 *  - A HIGH action never runs. It becomes a confirmation request, and the loop pauses. The
 *    agent does not get to decide that the user would have said yes.
 *  - A blocked app stops the run. Nothing is attempted, and the user is told why.
 */
class StepEngine(
    private val registry: ToolRegistry,
    private val guard: SafetyGuard,
    private val confirmations: ConfirmationGate,
    /**
     * Every decision is recorded here, including the ones that never ran. The refusals are
     * the interesting rows: they are what explains a run that stopped early.
     */
    private val audit: com.phoneagent.audit.AuditLog = com.phoneagent.audit.InMemoryAuditLog(),
    private val stepIndexProvider: () -> Int = { 0 },
) {

    /** What happened in this step. */
    sealed interface Outcome {
        /** The action ran. [note] goes into the next prompt. */
        data class Acted(
            val tool: String,
            val note: String,
            val screenChanged: Boolean,
            val success: Boolean,
        ) : Outcome

        /** The user must confirm before this runs. */
        data class NeedsConfirmation(
            val tool: String,
            val call: ToolCall,
            val reason: String,
            val risk: RiskLevel,
        ) : Outcome

        /** The user refused. [note] tells the model to try another way. */
        data class Refused(val tool: String, val note: String) : Outcome

        /** Nothing was attempted. [note] explains why, for the model. */
        data class Rejected(val tool: String, val note: String) : Outcome

        /** The agent finished the task. */
        data class Finished(val summary: String) : Outcome

        /** The agent needs the human. */
        data class Asked(val question: String) : Outcome

        /** The agent gave up. */
        data class Failed(val reason: String) : Outcome

        /** The plan was replaced. */
        data class PlanUpdated(val plan: AgentPlan, val note: String) : Outcome
    }

    suspend fun run(
        decision: DecisionParser.Decision,
        snapshot: ScreenSnapshot,
        call: suspend (ToolCall) -> ToolResult,
    ): Outcome {
        return when (decision) {
            is DecisionParser.Decision.Unusable -> Outcome.Rejected(
                tool = "(none)",
                note = decision.reason,
            )

            is DecisionParser.Decision.Finish ->
                Outcome.Finished(decision.summary)

            is DecisionParser.Decision.AskUser ->
                Outcome.Asked(decision.question)

            is DecisionParser.Decision.Fail ->
                Outcome.Failed(decision.reason)

            is DecisionParser.Decision.Act ->
                act(decision.tool, decision.args, decision.argumentsJson, snapshot, call)
        }
    }

    private suspend fun act(
        toolName: String,
        args: Map<String, Any?>,
        argumentsJson: String,
        snapshot: ScreenSnapshot,
        call: suspend (ToolCall) -> ToolResult,
    ): Outcome {
        val toolCall = ToolCall(toolName, args)

        // The loop's own tools are answered here, before the registry is consulted, because
        // they are how the agent talks about its own work rather than actions on the phone.
        // Looking them up first would wrongly reject them on a registry that does not carry them.
        if (toolName == "update_plan") {
            val steps = (args["steps"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val plan = AgentPlan.parse(steps.joinToString("\n"))
            if (plan.isEmpty) {
                return Outcome.Rejected(toolName, "The plan was empty, so it was not changed.")
            }
            return Outcome.PlanUpdated(plan, "Plan replaced with ${plan.steps.size} steps.")
        }

        // Terminal tools are cheap and safe, so they are run without a guard check: a guard
        // refusal on "finish" would be confusing, and they touch nothing.
        if (toolName in TERMINAL_TOOLS) {
            val result = terminalResult(toolName, args)
            return if (result.success) {
                when (toolName) {
                    "finish" -> Outcome.Finished(result.message)
                    "ask_user" -> Outcome.Asked(result.message)
                    else -> Outcome.Failed(result.message)
                }
            } else {
                Outcome.Rejected(toolName, result.message)
            }
        }

        // A real phone tool must exist before anything else is decided.
        val tool = registry.get(toolName)
            ?: return Outcome.Rejected(toolName, "There is no tool called \"$toolName\".")

        // Guard before acting. The verdict combines the tool's own risk with the risk of the
        // element it will touch, which is why the target node is resolved first.
        val target = resolveTarget(args, snapshot)
        val verdict = guard.assessToolAndTarget(toolName, tool.riskLevel, target)

        when (verdict.level) {
            RiskLevel.BLOCKED -> {
                record(toolName, tool.riskLevel, com.phoneagent.audit.AuditDecision.BLOCKED, verdict.reason, snapshot)
                return Outcome.Rejected(
                    toolName,
                    "That action is blocked: ${verdict.reason}. " +
                        "Tell the user it cannot be done and stop trying.",
                )
            }

            RiskLevel.HIGH -> {
                // The user must decide. The loop pauses here; the outcome is recorded when the
                // answer arrives, because "waiting" is not the same as "denied".
                confirmations.request(toolCall, verdict.reason)
                return Outcome.NeedsConfirmation(
                    tool = toolName,
                    call = toolCall,
                    reason = verdict.reason,
                    risk = verdict.level,
                )
            }

            RiskLevel.MEDIUM, RiskLevel.LOW -> Unit
        }

        val result = registry.execute(toolCall, currentContext(snapshot), registryTimeout(toolName))
        record(
            toolName,
            verdict.level,
            if (result.success) {
                com.phoneagent.audit.AuditDecision.ALLOWED
            } else {
                com.phoneagent.audit.AuditDecision.FAILED
            },
            result.message,
            snapshot,
        )
        return Outcome.Acted(
            tool = toolName,
            note = result.message,
            // A tool that does not say whether the screen changed is assumed to have changed
            // it. The verify step compares hashes independently, so guessing "unchanged" would
            // wrongly accuse a working action of being stuck.
            screenChanged = result.screenChanged ?: true,
            success = result.success,
        )
    }

    /**
     * Finds the element an action will touch, so the guard can read its label.
     *
     * A tap by coordinates has no label, so the target is the package alone. That is not a
     * gap: an unlabelled tap inside a restricted app is still refused, and a bare coordinate
     * tap carries no risky word to match.
     */
    private fun resolveTarget(args: Map<String, Any?>, snapshot: ScreenSnapshot): RiskTarget {
        val mark = (args["mark"] as? Number)?.toInt()
        val node = mark?.let { snapshot.nodeForMark(it) }

        return RiskTarget(
            text = node?.text,
            contentDescription = node?.contentDescription,
            viewId = node?.viewId,
            packageName = snapshot.packageName,
            isPasswordField = node?.password ?: false,
            isEditable = node?.editable ?: false,
        )
    }

    /**
     * Builds the result for a terminal tool from its arguments.
     *
     * Validating here rather than in the registry keeps the loop's vocabulary in one place and
     * means a registry that does not carry these tools still works.
     */
    private fun terminalResult(toolName: String, args: Map<String, Any?>): ToolResult = when (toolName) {
        "finish" -> (args["summary"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { ToolResult.ok(it) }
            ?: ToolResult.fail("Give a one-sentence summary of what was done.")

        "ask_user" -> (args["question"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { ToolResult.ok(it) }
            ?: ToolResult.fail("Give a question to ask the user.")

        "fail" -> (args["reason"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { ToolResult.ok(it) }
            ?: ToolResult.fail("Give a reason.")

        else -> ToolResult.fail("Unknown tool $toolName.")
    }

    /**
     * Runs a call the person has already approved.
     *
     * The guard is consulted again, because the screen may have moved while the question was
     * on screen. If it has become blocked, the refusal wins: an approval for one action on one
     * screen is not an approval for anything else.
     */
    suspend fun runApproved(toolName: String, args: Map<String, Any?>, snapshot: ScreenSnapshot): Outcome {
        val tool = registry.get(toolName)
            ?: return Outcome.Rejected(toolName, "There is no tool called \"$toolName\".")
        val toolCall = ToolCall(toolName, args)

        // The package check comes first. An approval for one action on one screen is not an
        // approval to act inside a restricted app, which may have become the current screen
        // while the question was open.
        guard.packageRefusalReason(snapshot.packageName)?.let { refusal ->
            record(toolName, tool.riskLevel, com.phoneagent.audit.AuditDecision.BLOCKED, refusal, snapshot)
            return Outcome.Rejected(toolName, "$refusal The approved action was not run.")
        }

        val target = resolveTarget(args, snapshot)
        val verdict = guard.assessToolAndTarget(toolName, tool.riskLevel, target)
        if (verdict.level == RiskLevel.BLOCKED) {
            record(toolName, tool.riskLevel, com.phoneagent.audit.AuditDecision.BLOCKED, verdict.reason, snapshot)
            return Outcome.Rejected(toolName, "That action is blocked: ${verdict.reason}.")
        }

        val result = registry.execute(toolCall, currentContext(snapshot), registryTimeout(toolName))
        record(
            toolName,
            verdict.level,
            com.phoneagent.audit.AuditDecision.APPROVED,
            result.message,
            snapshot,
        )
        return Outcome.Acted(
            tool = toolName,
            note = result.message,
            screenChanged = result.screenChanged ?: true,
            success = result.success,
        )
    }

    /** Records that the person said no, or did not answer. */
    suspend fun recordConfirmationOutcome(
        toolName: String,
        risk: RiskLevel,
        approved: Boolean,
        timedOut: Boolean,
        snapshot: ScreenSnapshot,
    ) {
        val decision = when {
            approved -> com.phoneagent.audit.AuditDecision.APPROVED
            timedOut -> com.phoneagent.audit.AuditDecision.TIMED_OUT
            else -> com.phoneagent.audit.AuditDecision.DENIED
        }
        record(toolName, risk, decision, "confirmation", snapshot)
    }

    /** The reason to refuse everything in a package, or null when it is allowed. */
    fun refusalReasonFor(packageName: String?): String? = guard.packageRefusalReason(packageName)

    /**
     * Writes one audit row.
     *
     * The detail is the tool's own message, which is already short and describes the element
     * rather than the whole screen. The log masks and truncates again on the way in.
     */
    private suspend fun record(
        toolName: String,
        risk: RiskLevel,
        decision: com.phoneagent.audit.AuditDecision,
        detail: String,
        snapshot: ScreenSnapshot,
    ) {
        audit.record(
            toolName = toolName,
            risk = risk,
            decision = decision,
            detail = detail,
            packageName = snapshot.packageName,
            stepIndex = stepIndexProvider(),
        )
    }

    private fun registryTimeout(toolName: String) = ToolRegistry.timeoutFor(toolName)

    /**
     * A context whose screen is the one the model saw. The tools re-read it themselves when
     * they need a fresh view, so passing the stale snapshot here is correct: it keeps the
     * guard and the action looking at the same screen.
     */
    private fun currentContext(snapshot: ScreenSnapshot): com.phoneagent.tools.ToolContext =
        confirmations.contextFor(snapshot)

    private companion object {
        val TERMINAL_TOOLS = setOf("finish", "ask_user", "fail")
    }
}

/**
 * Holds the pending confirmation and the context the tools run against.
 *
 * Kept as an interface so the loop can be tested without a device, and so the confirmation
 * survives the pause: when the user answers, the loop resumes with the same tool call.
 */
interface ConfirmationGate {
    fun request(call: ToolCall, reason: String)

    /** The call waiting for an answer, or null. */
    fun pending(): ToolCall?

    /** Called when the user answers. Returns true to proceed. */
    fun answer(approved: Boolean): ToolCall?

    fun contextFor(snapshot: ScreenSnapshot): com.phoneagent.tools.ToolContext
}
