package com.phoneagent.agent

import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * The real confirmation gate.
 *
 * Three rules shape it, all from the design:
 *  - a timeout is a denial, because a dialog nobody answered must not turn into a yes;
 *  - the pending call is remembered, so the answer applies to exactly the action that was
 *    shown and a different action cannot slip through on the same approval;
 *  - the context is captured when the question is asked, so the tool runs against the screen
 *    the person was looking at rather than whatever is on screen when they finally answer.
 */
class AndroidConfirmationGate(
    private val logger: Logger,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onAsk: (ToolCall, String) -> Unit,
    private val contextProvider: (ScreenSnapshot) -> ToolContext,
) : ConfirmationGate {

    private var pendingCall: ToolCall? = null
    private var pendingReason: String? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var lastSnapshot: ScreenSnapshot? = null

    override fun request(call: ToolCall, reason: String) {
        pendingCall = call
        pendingReason = reason
        answer = CompletableDeferred()
        logger.log(LogLevel.INFO, TAG, "Asking to confirm ${call.name}: $reason")
        onAsk(call, reason)
    }

    override fun pending(): ToolCall? = pendingCall

    /** The question to show, or null when nothing is waiting. */
    fun pendingQuestion(): String? = pendingReason

    /**
     * Waits for the person's answer.
     *
     * Returns true to proceed and false to skip. A timeout returns false, and the reason is
     * logged, because "nobody answered" and "the person said no" look the same to the caller
     * but not to whoever reads the log afterwards.
     */
    suspend fun await(): Boolean {
        val deferred = answer ?: return false
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            logger.log(
                LogLevel.WARN,
                TAG,
                "No answer within ${timeoutMs}ms; treating it as a denial.",
            )
            false
        } finally {
            pendingCall = null
            pendingReason = null
            answer = null
        }
    }

    /** Called from the dialog or the notification action. */
    fun submitAnswer(approved: Boolean) {
        answer?.complete(approved)
    }

    override fun answer(approved: Boolean): ToolCall? {
        submitAnswer(approved)
        return pendingCall
    }

    override fun contextFor(snapshot: ScreenSnapshot): ToolContext {
        lastSnapshot = snapshot
        return contextProvider(snapshot)
    }

    companion object {
        const val TAG = "ConfirmationGate"

        /**
         * Thirty seconds. Long enough to notice a notification and decide, short enough that a
         * forgotten question does not hold a task open indefinitely.
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * The gate the loop uses while a confirmation is outstanding, and the answer that resumes it.
 *
 * This is what makes a paused loop resumable: the controller holds one of these, the UI
 * answers it, and the next step finds the decision already recorded.
 */
class PendingConfirmation {
    private var call: ToolCall? = null
    private var approved: Boolean? = null

    fun set(call: ToolCall) {
        this.call = call
        approved = null
    }

    fun resolve(approved: Boolean) {
        this.approved = approved
    }

    /** True when a decision is waiting to be used. */
    val hasAnswer: Boolean get() = call != null && approved != null

    /** Reads the answer once, clearing it. */
    fun consume(): Pair<ToolCall, Boolean>? {
        val c = call ?: return null
        val a = approved ?: return null
        call = null
        approved = null
        return c to a
    }

    val pendingCall: ToolCall? get() = call
}
