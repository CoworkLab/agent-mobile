package com.phoneagent.scenarios

import com.phoneagent.agent.StepOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A recorded run, replayed without a model.
 *
 * The eval workflow spends real quota on a real model against a real emulator. What it learns
 * is only useful if it stays learned, so a run that worked is saved here and replayed in
 * ordinary CI: same screens, same replies, no network. A regression in the prompt builder, the
 * decision parser, the guard or the step engine then shows up in seconds and for free.
 *
 * The format is deliberately a transcript rather than a script of assertions. It records what
 * the model saw and what it said; what the run was supposed to achieve is checked once, when
 * the fixture is recorded, and the replay checks that the same inputs still produce the same
 * decisions.
 */
@Serializable
data class RecordedRun(
    val name: String,
    val goal: String,
    /** One entry per model reply, in the order the model produced them. */
    val steps: List<RecordedStep>,
)

/** One turn: the screen the model saw, and what it replied. */
@Serializable
data class RecordedStep(
    val screen: RecordedScreen,
    val reply: RecordedReply,
    /**
     * The outcome the run produced, as a lowercase tag: `continue`, `done`, `waiting_user`,
     * `failed`, `stopped`. Recorded so the replay can assert the shape of the run without
     * re-deriving what "correct" means.
     */
    val outcome: String,
)

/** Just enough of a screen to rebuild it: the package and the nodes the model was shown. */
@Serializable
data class RecordedScreen(
    val packageName: String? = null,
    val hash: String = "",
    val nodes: List<RecordedNode> = emptyList(),
    /** Null when the capture succeeded; otherwise the failure name. */
    val failure: String? = null,
)

@Serializable
data class RecordedNode(
    val mark: Int? = null,
    val text: String? = null,
    val description: String? = null,
    val className: String = "android.widget.Button",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /** Left, top, right, bottom. */
    val bounds: List<Int> = listOf(0, 0, 0, 0),
)

/** The model's reply, in the two forms the loop understands. */
@Serializable
data class RecordedReply(
    val tool: String? = null,
    val argumentsJson: String = "{}",
    val text: String? = null,
)

/** Reads and writes the fixtures. */
object RunRecorder {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(run: RecordedRun): String = json.encodeToString(RecordedRun.serializer(), run)

    fun decode(source: String): RecordedRun = json.decodeFromString(RecordedRun.serializer(), source)

    /** Every fixture on the classpath, by name. */
    fun loadAll(): List<RecordedRun> {
        val dir = java.io.File("src/test/resources/fixtures")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { decode(it.readText()) }
            ?: emptyList()
    }

    /** Turns an outcome into the tag the fixture stores. */
    fun tag(outcome: StepOutcome): String = when (outcome) {
        is StepOutcome.Continue -> "continue"
        is StepOutcome.Done -> "done"
        is StepOutcome.WaitingUser -> "waiting_user"
        is StepOutcome.Failed -> "failed"
        is StepOutcome.Stopped -> "stopped"
    }
}
