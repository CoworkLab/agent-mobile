package com.phoneagent.agent

/**
 * The loop's budget. Every limit is here so a test can set one to a small number and prove
 * the loop stops, rather than waiting for a real 10-minute timeout.
 */
data class LoopLimits(
    val maxSteps: Int = 40,
    val maxDurationMs: Long = 10 * 60 * 1000L,
    val maxConsecutiveToolErrors: Int = 4,
    val maxActionsPerMinute: Int = 30,
    /** Steps with no visible change before a hint is injected. */
    val stuckStepsBeforeHint: Int = 2,
    /** Hints before the plan is thrown away and remade. */
    val stuckEventsBeforeReplan: Int = 3,
    /** Stuck events before the agent gives up or asks the user. */
    val stuckEventsBeforeGivingUp: Int = 5,
    val maxScreenshotsInHistory: Int = 2,
    val maxPromptChars: Int = 60_000,
) {
    companion object {
        val DEFAULT = LoopLimits()
    }
}

/** Why the loop stopped. Every one of these is a normal ending, not an error. */
sealed interface StopReason {
    data object Finished : StopReason
    data class StepLimitReached(val steps: Int) : StopReason
    data class TimeLimitReached(val millis: Long) : StopReason
    data class ToolErrors(val consecutive: Int) : StopReason
    data class RateLimitExceeded(val actionsInWindow: Int) : StopReason
    data class TooManyStuckEvents(val events: Int) : StopReason
    data object Cancelled : StopReason
    data class Failed(val reason: String) : StopReason
    data class NeedsUser(val question: String) : StopReason

    /** A short sentence for the user, in plain language. */
    fun describe(): String = when (this) {
        Finished -> "Done."
        is StepLimitReached -> "I stopped after $steps steps to avoid looping forever."
        is TimeLimitReached ->
            "I stopped after ${millis / 60_000} minutes. The task may need to be split up."
        is ToolErrors -> "I stopped because the last $consecutive actions kept failing."
        is RateLimitExceeded -> "I stopped because I was acting too fast for safety."
        is TooManyStuckEvents -> "I stopped because the screen was not changing."
        Cancelled -> "Stopped."
        is Failed -> reason
        is NeedsUser -> question
    }
}

/**
 * Watches the budget and the action rate.
 *
 * The rate limiter uses a sliding window rather than a token bucket, because the rule the
 * user cares about is "no more than 30 actions in any minute", and a bucket can allow a
 * burst of 60 across two adjacent windows.
 */
class LoopBudget(
    private val limits: LoopLimits,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var startedAt = clock()
    private var stepCount = 0
    private var consecutiveToolErrors = 0
    private val actionTimes = ArrayDeque<Long>()

    fun start() {
        startedAt = clock()
        stepCount = 0
        consecutiveToolErrors = 0
        actionTimes.clear()
    }

    fun recordStep() {
        stepCount++
    }

    /** Returns the reason to stop, or null to carry on. */
    fun check(): StopReason? {
        if (stepCount >= limits.maxSteps) return StopReason.StepLimitReached(stepCount)

        val elapsed = clock() - startedAt
        if (elapsed >= limits.maxDurationMs) return StopReason.TimeLimitReached(elapsed)

        if (consecutiveToolErrors >= limits.maxConsecutiveToolErrors) {
            return StopReason.ToolErrors(consecutiveToolErrors)
        }
        return null
    }

    /**
     * Records an action about to be taken. Returns a reason to stop when the rate is too high.
     */
    fun recordAction(): StopReason? {
        val now = clock()
        actionTimes.addLast(now)
        while (actionTimes.isNotEmpty() && now - actionTimes.first() > RATE_WINDOW_MS) {
            actionTimes.removeFirst()
        }
        return if (actionTimes.size > limits.maxActionsPerMinute) {
            StopReason.RateLimitExceeded(actionTimes.size)
        } else {
            null
        }
    }

    fun recordToolResult(success: Boolean) {
        consecutiveToolErrors = if (success) 0 else consecutiveToolErrors + 1
    }

    val steps: Int get() = stepCount
    val elapsedMs: Long get() = clock() - startedAt
    val toolErrors: Int get() = consecutiveToolErrors

    companion object {
        const val RATE_WINDOW_MS = 60_000L
    }
}

/**
 * Detects that the screen is not changing and decides what to do about it.
 *
 * The escalation is the important part. Repeating the same hint is useless, so each level
 * asks for something different: look again, try scrolling, then try going back, then switch
 * to the screenshot view, and only then throw the plan away. Giving up is the last resort.
 */
class StuckDetector(private val limits: LoopLimits) {

    private var lastHash: String? = null
    private var unchangedCount = 0
    private var stuckEvents = 0
    private var hintIndex = 0

    data class Reaction(
        /** A note to add to the next prompt, or null when nothing needs saying. */
        val hint: String? = null,
        /** True when the plan should be discarded and remade. */
        val replan: Boolean = false,
        /** True when the loop should stop and ask the user, or fail. */
        val giveUp: Boolean = false,
        val stuckEvents: Int = 0,
    )

    fun reset() {
        lastHash = null
        unchangedCount = 0
        stuckEvents = 0
        hintIndex = 0
    }

    /** Call after each observation, with the screen hash the model saw. */
    fun observe(screenHash: String): Reaction {
        val previous = lastHash
        lastHash = screenHash

        if (previous == null) return Reaction()

        if (previous == screenHash) {
            unchangedCount++
        } else {
            // The screen moved, so the previous hints are no longer relevant.
            unchangedCount = 0
            hintIndex = 0
            return Reaction()
        }

        if (unchangedCount < limits.stuckStepsBeforeHint) return Reaction()

        stuckEvents++
        val hint = HINTS[hintIndex.coerceAtMost(HINTS.lastIndex)]
        hintIndex++

        return Reaction(
            hint = hint,
            replan = stuckEvents >= limits.stuckEventsBeforeReplan,
            giveUp = stuckEvents >= limits.stuckEventsBeforeGivingUp,
            stuckEvents = stuckEvents,
        )
    }

    val events: Int get() = stuckEvents

    private companion object {
        /**
         * Ordered from cheapest to most disruptive. The last entry is what forces a replan,
         * because a screenshot is the view that works when the tree is unhelpful.
         */
        val HINTS = listOf(
            "The screen has not changed. Read the marks again and pick a different element.",
            "The screen has not changed. Try scrolling to bring other elements into view.",
            "The screen has not changed. Try going back, or open the app again.",
            "The screen has not changed. Switch to the screenshot view to see what is really there.",
            "The screen still has not changed. Stop trying this approach.",
        )
    }
}

/**
 * Keeps the prompt small enough to send.
 *
 * The rule from the design: at most the last two screenshots survive, and older steps become
 * one-line summaries. A full history of twenty screenshots would cost more than the task is
 * worth and would push the important recent detail out of the model's attention.
 */
class HistoryCompactor(private val limits: LoopLimits) {

    data class Entry(
        val stepIndex: Int,
        val summary: String,
        val screenshotJpeg: ByteArray? = null,
        val screenshotBytes: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return stepIndex == other.stepIndex &&
                summary == other.summary &&
                screenshotBytes == other.screenshotBytes &&
                (screenshotJpeg?.contentEquals(other.screenshotJpeg ?: ByteArray(0)) ?: (other.screenshotJpeg == null))
        }

        override fun hashCode(): Int {
            var result = stepIndex
            result = 31 * result + summary.hashCode()
            result = 31 * result + (screenshotJpeg?.contentHashCode() ?: 0)
            result = 31 * result + screenshotBytes
            return result
        }
    }

    private val entries = mutableListOf<Entry>()

    fun add(entry: Entry) {
        entries += entry
    }

    /**
     * The history to send: recent entries keep their screenshot, older ones lose theirs and
     * are collapsed into one line each.
     */
    fun forPrompt(): List<Entry> {
        if (entries.isEmpty()) return emptyList()

        val keepFrom = (entries.size - limits.maxScreenshotsInHistory).coerceAtLeast(0)
        return entries.mapIndexed { index, entry ->
            if (index >= keepFrom) {
                entry
            } else {
                entry.copy(screenshotJpeg = null, summary = entry.summary.take(200))
            }
        }
    }

    /**
     * Trims the history until the estimated prompt fits. Whole oldest entries are dropped
     * first, because a partly-dropped entry is more confusing than an absent one.
     */
    fun trimToFit(estimatePromptChars: (List<Entry>) -> Int): List<Entry> {
        var working = entries.toList()
        while (working.size > 1 && estimatePromptChars(working) > limits.maxPromptChars) {
            working = working.drop(1)
        }
        return working
    }

    fun size(): Int = entries.size

    fun clear() {
        entries.clear()
    }

    /** Total screenshot bytes held, so a test can prove the cap is real. */
    fun screenshotBytes(): Int = entries.sumOf { it.screenshotBytes }
}

/**
 * The plan the agent wrote for itself.
 *
 * Parsed leniently: a model that ignores the format and writes prose still yields a plan,
 * because refusing to start over formatting would be a poor trade.
 */
data class AgentPlan(val steps: List<String>, val raw: String) {
    val isEmpty: Boolean get() = steps.isEmpty()

    /** The plan as the prompt should show it. */
    fun asPromptText(): String =
        if (steps.isEmpty()) "(no plan)" else steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

    companion object {
        fun parse(text: String?): AgentPlan {
            if (text.isNullOrBlank()) return AgentPlan(emptyList(), "")

            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    // Drop a leading bullet or number: "1. open WhatsApp", "- open WhatsApp".
                    line.replace(Regex("^\\s*(\\d+[.)]|[-*•])\\s*"), "").trim()
                }
                // Drop a line that is only a heading, such as "Plan:" or "Steps". A line that
                // merely starts with the word, such as "Plan the route", is a real step.
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.matches(Regex("(?i)(the\\s+)?(plan|steps?)\\s*:?"))
                }

            // A plan is meant to be short. A long list is prose that happened to have lines,
            // so it is capped rather than sent whole.
            return AgentPlan(lines.take(MAX_PLAN_LINES), text)
        }

        const val MAX_PLAN_LINES = 7
    }
}
