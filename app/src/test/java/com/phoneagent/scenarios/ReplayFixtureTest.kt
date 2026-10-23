package com.phoneagent.scenarios

import com.phoneagent.agent.StepOutcome
import com.phoneagent.brain.LlmResponse
import com.phoneagent.brain.ToolCallRequest
import com.phoneagent.perception.Bounds
import com.phoneagent.perception.SnapshotFailure
import com.phoneagent.perception.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays recorded runs against the shipped loop, with no model and no network.
 *
 * The eval workflow is where the system prompt and the loop rules get tuned, and it is the only
 * place that costs quota. These tests are what stops that tuning from being undone later: the
 * recorded transcript is fed back through the same orchestrator, and the run must still take the
 * same shape. A prompt change that makes the model stop calling `finish`, or a guard change that
 * starts blocking an ordinary tap, fails here in seconds.
 */
class ReplayFixtureTest {

    private fun node(recorded: RecordedNode) = UiNode(
        nodeId = recorded.mark ?: 0,
        parentId = null,
        depth = 1,
        className = recorded.className,
        text = recorded.text,
        contentDescription = recorded.description,
        viewId = null,
        bounds = Bounds(
            recorded.bounds.getOrElse(0) { 0 },
            recorded.bounds.getOrElse(1) { 0 },
            recorded.bounds.getOrElse(2) { 0 },
            recorded.bounds.getOrElse(3) { 0 },
        ),
        clickable = recorded.clickable,
        editable = recorded.editable,
        scrollable = recorded.scrollable,
        mark = recorded.mark,
    )

    private fun failure(name: String?): SnapshotFailure? = when (name) {
        null -> null
        "SECURE_SCREEN" -> SnapshotFailure.SECURE_SCREEN
        "BLANK_CAPTURE" -> SnapshotFailure.BLANK_CAPTURE
        "RATE_LIMITED" -> SnapshotFailure.RATE_LIMITED
        "NO_WINDOW" -> SnapshotFailure.NO_WINDOW
        else -> SnapshotFailure.UNKNOWN
    }

    /**
     * Replays one fixture.
     *
     * The orchestrator is driven step by step, and before each step the recorded screen is put
     * in front of it. That is what makes the replay deterministic: the screens come from the
     * fixture, so nothing depends on a device or on timing.
     */
    private fun replay(run: RecordedRun) {
        val harness = ScenarioHarness()

        // Every reply is queued before the first step. Queueing them one per iteration would
        // work only by accident, because the model hands back the oldest queued reply: a reply
        // added just before step N would be consumed at step N+1.
        run.steps.forEach { step ->
            val reply = step.reply
            harness.model.willReply(
                if (reply.tool != null) {
                    LlmResponse(
                        text = reply.text,
                        toolCalls = listOf(
                            ToolCallRequest(name = reply.tool, argumentsJson = reply.argumentsJson),
                        ),
                        providerName = "Fixture",
                    )
                } else {
                    LlmResponse(text = reply.text, providerName = "Fixture")
                },
            )
        }

        run.steps.forEachIndexed { index, step ->
            harness.screen(
                hash = step.screen.hash,
                packageName = step.screen.packageName,
                nodes = step.screen.nodes.map(::node),
                failure = failure(step.screen.failure),
            )

            val outcome = runBlocking { harness.orchestrator.step(run.goal, index) }

            assertEquals(
                "step ${index + 1} of \"${run.name}\" changed shape",
                step.outcome,
                RunRecorder.tag(outcome),
            )
        }
    }

    @Test
    fun `every fixture replays to the same shape`() {
        val fixtures = RunRecorder.loadAll()

        // A silent skip would let the directory be emptied without anyone noticing.
        assertTrue(
            "no fixtures found; the eval workflow should have recorded some",
            fixtures.isNotEmpty(),
        )

        fixtures.forEach { replay(it) }
    }

    @Test
    fun `a fixture survives a round trip through the file format`() {
        val original = RecordedRun(
            name = "round-trip",
            goal = "Open the app",
            steps = listOf(
                RecordedStep(
                    screen = RecordedScreen(
                        packageName = "com.example.app",
                        hash = "h1",
                        nodes = listOf(
                            RecordedNode(
                                mark = 1,
                                text = "Open",
                                clickable = true,
                                bounds = listOf(10, 20, 300, 120),
                            ),
                        ),
                    ),
                    reply = RecordedReply(tool = "tap", argumentsJson = """{"mark":1}"""),
                    outcome = "continue",
                ),
            ),
        )

        val decoded = RunRecorder.decode(RunRecorder.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `an outcome tag is stable for every kind of ending`() {
        assertEquals("continue", RunRecorder.tag(StepOutcome.Continue()))
        assertEquals("done", RunRecorder.tag(StepOutcome.Done("ok")))
        assertEquals("waiting_user", RunRecorder.tag(StepOutcome.WaitingUser("q")))
        assertEquals("failed", RunRecorder.tag(StepOutcome.Failed("no")))
        assertEquals("stopped", RunRecorder.tag(StepOutcome.Stopped("no")))
    }
}
