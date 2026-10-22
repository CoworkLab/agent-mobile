package com.phoneagent.agent

import com.phoneagent.util.LogLevel
import com.phoneagent.util.SecretMasker
import com.phoneagent.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Runs the observe -> decide -> act loop for one goal at a time.
 *
 * The brain is injected as [stepRunner], so the loop itself is fully testable on the JVM
 * and M6 can swap in the real LLM planner without touching this class.
 *
 * Cancellation is cooperative and checked between steps, which is what makes STOP take
 * effect within one step rather than mid-gesture.
 */
class AgentController(
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val maxSteps: Int = 60,
    private val stepRunner: suspend (goal: String, stepIndex: Int, observation: String) -> StepOutcome,
    private val observer: suspend () -> String,
) {
    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var pendingAnswer: CompletableDeferred<String>? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /** Starts a task. Ignored when a task is already running. */
    fun start(goal: String) {
        if (isRunning) {
            logger.w("AgentController", "Ignoring start; a task is already running")
            return
        }
        _status.value = AgentStatus(state = AgentState.PLANNING, goal = goal)
        logger.i("AgentController", "start goal=${redact(goal)}")

        job = scope.launch {
            try {
                runLoop(goal)
            } catch (c: CancellationException) {
                _status.value = _status.value.copy(state = AgentState.CANCELLED, summary = "Stopped.")
                logger.i("AgentController", "Task cancelled")
                throw c
            } catch (t: Throwable) {
                logger.e("AgentController", "Task crashed", t)
                _status.value = _status.value.copy(
                    state = AgentState.FAILED,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun runLoop(goal: String) {
        var step = 0
        while (step < maxSteps) {
            setState(AgentState.OBSERVING, stepIndex = step)
            val observation = observer()
            logger.d("AgentController", "observed: ${redact(observation)}")

            setState(AgentState.DECIDING, stepIndex = step)
            when (val outcome = stepRunner(goal, step, observation)) {
                is StepOutcome.Continue -> {
                    _status.value = _status.value.copy(lastNote = outcome.note)
                    step++
                    // Cooperative cancellation point: without this a fast, non-suspending
                    // step runner would starve the dispatcher and delay STOP.
                    yield()
                }

                is StepOutcome.WaitingUser -> {
                    _status.value = _status.value.copy(
                        state = AgentState.WAITING_USER,
                        question = outcome.question,
                    )
                    val answer = awaitAnswer()
                    if (answer == null) {
                        _status.value = _status.value.copy(
                            state = AgentState.CANCELLED,
                            summary = "Stopped.",
                        )
                        return
                    }
                    _status.value = _status.value.copy(question = null)
                    step++
                }

                is StepOutcome.Done -> {
                    _status.value = _status.value.copy(
                        state = AgentState.DONE,
                        summary = outcome.summary,
                        stepIndex = step,
                    )
                    logger.i("AgentController", "Task done in $step steps")
                    return
                }

                is StepOutcome.Failed -> {
                    _status.value = _status.value.copy(
                        state = AgentState.FAILED,
                        error = outcome.reason,
                        stepIndex = step,
                    )
                    logger.w("AgentController", "Task failed: ${redact(outcome.reason)}")
                    return
                }

                is StepOutcome.Stopped -> {
                    _status.value = _status.value.copy(
                        state = AgentState.CANCELLED,
                        summary = outcome.reason,
                        stepIndex = step,
                    )
                    logger.i("AgentController", "Task stopped: ${redact(outcome.reason)}")
                    return
                }
            }
        }

        logger.w("AgentController", "Step limit reached ($maxSteps)")
        _status.value = _status.value.copy(
            state = AgentState.FAILED,
            error = "I stopped after $maxSteps steps without finishing.",
            stepIndex = maxSteps,
        )
    }

    private suspend fun awaitAnswer(): String? {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        return try {
            deferred.await()
        } catch (c: CancellationException) {
            null
        } finally {
            pendingAnswer = null
        }
    }

    /** Supplies an answer to a [StepOutcome.WaitingUser] question. No-op when not waiting. */
    fun userAnswer(text: String) {
        pendingAnswer?.complete(text)
    }

    /** Cancels within one step. Safe to call at any time, including when idle. */
    fun stop() {
        pendingAnswer?.cancel()
        pendingAnswer = null
        val hadJob = job?.isActive == true
        job?.cancel()
        job = null
        if (hadJob) {
            logger.i("AgentController", "STOP requested")
            _status.value = _status.value.copy(state = AgentState.CANCELLED, summary = "Stopped.")
        } else {
            logger.d("AgentController", "STOP requested while idle")
        }
    }

    /** Clears a terminal state so the UI can return to idle. */
    fun acknowledge() {
        if (_status.value.state.isTerminal) {
            _status.value = AgentStatus()
        }
    }

    private fun setState(state: AgentState, stepIndex: Int = _status.value.stepIndex) {
        _status.value = _status.value.copy(state = state, stepIndex = stepIndex)
    }
}

/** Masks secrets in free text before it reaches the log buffer. */
private fun redact(text: String): String = SecretMasker.mask(text)
