package com.phoneagent.agent

/**
 * Orchestrator states. Ordering follows the brief's state machine.
 */
enum class AgentState {
    IDLE,
    PLANNING,
    OBSERVING,
    DECIDING,
    GUARDING,
    ACTING,
    VERIFYING,
    WAITING_USER,
    DONE,
    FAILED,
    CANCELLED,
    ;

    /** True once the task has stopped for any reason. */
    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED

    /** True while the agent is doing work and therefore cancellable. */
    val isBusy: Boolean
        get() = this == PLANNING || this == OBSERVING || this == DECIDING ||
            this == GUARDING || this == ACTING || this == VERIFYING
}

/** What a single step decided. The runner never throws; it returns one of these. */
sealed interface StepOutcome {
    /** Keep going; the loop will observe again. */
    data class Continue(val note: String = "") : StepOutcome

    /** The agent needs the human. The loop pauses until [AgentController.userAnswer]. */
    data class WaitingUser(val question: String) : StepOutcome

    /** Task finished successfully. */
    data class Done(val summary: String) : StepOutcome

    /** Task failed; [reason] is shown to the user in plain language. */
    data class Failed(val reason: String) : StepOutcome

    /** The agent decided to stop, e.g. a blocked app or an impossible goal. */
    data class Stopped(val reason: String) : StepOutcome
}

data class AgentStatus(
    val state: AgentState = AgentState.IDLE,
    val goal: String? = null,
    val stepIndex: Int = 0,
    val question: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val lastNote: String? = null,
)
