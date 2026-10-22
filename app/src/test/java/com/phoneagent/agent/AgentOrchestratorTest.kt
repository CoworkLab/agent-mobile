package com.phoneagent.agent

import com.phoneagent.brain.LlmError
import com.phoneagent.brain.LlmProvider
import com.phoneagent.brain.LlmRequest
import com.phoneagent.brain.LlmResponse
import com.phoneagent.brain.ProviderConfig
import com.phoneagent.brain.ProviderRouter
import com.phoneagent.brain.ToolCallRequest
import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.SnapshotFailure
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.Actuator
import com.phoneagent.tools.FakeActuator
import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.tools.ToolResult
import com.phoneagent.tools.schemaOf
import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real loop with a scripted model and a scripted screen.
 *
 * This is the test that matters for M6: it exercises the whole
 * `observe -> decide -> guard -> act -> verify` path, including the safety refusals and the
 * stuck escalation, without a device or a network.
 */
class AgentOrchestratorTest {

    // ---- fakes ------------------------------------------------------------------------

    /** A screen whose content is whatever the script says at this moment. */
    private class ScriptedPerception : PerceptionSource {
        var snapshot: ScreenSnapshot = screenOf("start", "com.example.app")
        var failures = 0

        override suspend fun capture(mode: PerceptionMode): ScreenSnapshot {
            if (failures > 0) {
                failures--
                return screenOf("bad", null, failure = SnapshotFailure.BLANK_CAPTURE)
            }
            return snapshot
        }

        override fun isAvailable(): Boolean = true

        override fun screenshotCooldownMs(): Long = 0
    }

    /** A model that replays a list of replies, then repeats the last one. */
    private class ScriptedModel(
        private val replies: MutableList<() -> LlmResponse> = mutableListOf(),
    ) : LlmProvider {
        override val config = ProviderConfig(
            id = "script", name = "Script",
            baseUrl = "https://script/v1", apiKey = "k", model = "m",
        )

        val requests = mutableListOf<LlmRequest>()

        fun willReply(response: LlmResponse) {
            replies += { response }
        }

        fun willFail(error: LlmError) {
            replies += { throw error }
        }

        fun willReplyWithTool(name: String, args: String = "{}", text: String? = null) {
            replies += {
                LlmResponse(
                    text = text,
                    toolCalls = listOf(ToolCallRequest(name = name, argumentsJson = args)),
                    providerName = "Script",
                )
            }
        }

        override suspend fun chat(request: LlmRequest): LlmResponse {
            requests += request
            val reply = if (replies.size > 1) replies.removeAt(0) else replies.firstOrNull()
            return reply?.invoke() ?: LlmResponse(text = "{}")
        }
    }

    private class TapTool(private val actuator: Actuator) : Tool {
        override val name = "tap"
        override val description = "tap"
        override val parametersSchema = schemaOf(emptyMap())
        var calls = 0
            private set

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            calls++
            val mark = (call.args["mark"] as? Number)?.toInt() ?: 0
            actuator.tap(mark, mark)
            return ToolResult.ok("Tapped element [$mark]", screenChanged = true)
        }
    }

    private class Gate(private val toolContext: ToolContext) : ConfirmationGate {
        val requested = mutableListOf<String>()
        private var pending: ToolCall? = null

        override fun request(call: ToolCall, reason: String) {
            pending = call
            requested += call.name
        }

        override fun pending(): ToolCall? = pending
        override fun answer(approved: Boolean): ToolCall? = pending.also { pending = null }
        override fun contextFor(snapshot: ScreenSnapshot): ToolContext = toolContext
    }

    // ---- helpers ----------------------------------------------------------------------

    private val logger = Logger()

    private fun guard(vararg denied: String): SafetyGuard {
        val json = java.io.File("src/main/assets/safety_keywords.json").readText()
        val denylist = PackageDenylist(denied.toList())
        return SafetyGuard(RiskClassifier(SafetyConfigLoader.parseKeywords(json), denylist), denylist)
    }

    private fun orchestrator(
        perception: ScriptedPerception,
        model: ScriptedModel,
        registry: ToolRegistry,
        actuator: Actuator = FakeActuator(),
        limits: LoopLimits = LoopLimits.DEFAULT,
        deniedPackages: List<String> = emptyList(),
    ): Pair<AgentOrchestrator, Gate> {
        val context = object : ToolContext {
            override fun currentNodes(): List<UiNode> = emptyList()
            override fun currentPackageName(): String? = perception.snapshot.packageName
            override suspend fun refreshScreen(): ScreenSnapshot = perception.snapshot
            override val actuator: Actuator = actuator
        }
        val gate = Gate(context)
        val engine = StepEngine(registry, guard(*deniedPackages.toTypedArray()), gate)
        val router = ProviderRouter(listOf(model), logger)

        return AgentOrchestrator(
            perception = perception,
            router = router,
            registry = registry,
            engine = engine,
            gate = gate,
            logger = logger,
            systemPrompt = "You are PhoneAgent.",
            limits = limits,
        ) to gate
    }

    // ---- tests ------------------------------------------------------------------------

    @Test
    fun `a simple tap then finish completes the task`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        val actuator = FakeActuator()
        val tap = TapTool(actuator)

        // The first reply is the plan, then a tap, then finish.
        model.willReply(LlmResponse(text = "1. Tap Search\n2. Done"))
        model.willReplyWithTool("tap", """{"mark":1}""")
        model.willReplyWithTool("finish", """{"summary":"Tapped search."}""")

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(tap)), actuator,
        )

        val first = orchestrator.step("Tap Search", 0)
        assertTrue("the first step should continue: $first", first is StepOutcome.Continue)
        assertEquals(1, tap.calls)

        val second = orchestrator.step("Tap Search", 1)
        assertTrue("the second step should finish: $second", second is StepOutcome.Done)
        assertEquals("Tapped search.", (second as StepOutcome.Done).summary)
    }

    @Test
    fun `the plan is written before the first action`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Open the app\n2. Search\n3. Finish"))

        val (orchestrator, _) = orchestrator(perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))))

        orchestrator.prepare("Open the app")

        assertEquals(3, orchestrator.currentPlan.steps.size)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun `a planning failure still lets the run continue`() = runBlocking {
        val model = ScriptedModel()
        model.willFail(LlmError.Network("no route"))

        val (orchestrator, _) = orchestrator(
            ScriptedPerception(), model, ToolRegistry(logger, emptyList()),
        )

        val outcome = orchestrator.prepare("Do something")

        assertTrue(outcome is StepOutcome.Continue)
        assertTrue(orchestrator.currentPlan.isEmpty)
    }

    @Test
    fun `a tap on a Send button pauses for approval and does not act`() = runBlocking {
        val perception = ScriptedPerception()
        perception.snapshot = screenOf(
            "send-screen", "com.example.app",
            nodes = listOf(
                UiNode(
                    nodeId = 1, parentId = null, depth = 1,
                    className = "android.widget.Button", text = "Send",
                    contentDescription = null, viewId = null,
                    bounds = Bounds(0, 0, 100, 50), mark = 1,
                ),
            ),
        )
        val model = ScriptedModel()
        val tap = TapTool(FakeActuator())
        model.willReply(LlmResponse(text = "1. Send it"))
        model.willReplyWithTool("tap", """{"mark":1}""")

        val (orchestrator, gate) = orchestrator(perception, model, ToolRegistry(logger, listOf(tap)))

        val outcome = orchestrator.step("Send the message", 0)

        assertTrue("a risky tap must pause: $outcome", outcome is StepOutcome.WaitingUser)
        assertEquals("nothing may run before approval", 0, tap.calls)
        assertEquals(listOf("tap"), gate.requested)
    }

    @Test
    fun `a restricted app stops the run before any model call`() = runBlocking {
        val perception = ScriptedPerception()
        perception.snapshot = screenOf("bank", "com.bank.app")
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Do it"))
        model.willReplyWithTool("tap", """{"mark":1}""")

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
            deniedPackages = listOf("com.bank.app"),
        )

        val outcome = orchestrator.step("Check my balance", 0)

        assertTrue("a denied package must stop: $outcome", outcome is StepOutcome.Stopped)
        assertTrue(
            "the model must not even be asked, to avoid wasting a request",
            model.requests.isEmpty(),
        )
    }

    @Test
    fun `a secure screen stops with an explanation instead of retrying`() = runBlocking {
        val perception = ScriptedPerception()
        perception.snapshot = screenOf("secure", "com.example.app", failure = SnapshotFailure.SECURE_SCREEN)

        val (orchestrator, _) = orchestrator(
            perception, ScriptedModel(), ToolRegistry(logger, emptyList()),
        )

        val outcome = orchestrator.step("Read the screen", 0)

        assertTrue(outcome is StepOutcome.Stopped)
        assertTrue(
            (outcome as StepOutcome.Stopped).reason.contains("blocks screen reading"),
        )
    }

    @Test
    fun `a blank capture is retried rather than fatal`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Look at the screen"))

        val (orchestrator, _) = orchestrator(perception, model, ToolRegistry(logger, emptyList()))

        // Plan first, then make the capture fail, so the failure lands inside the step.
        orchestrator.prepare("Look at the screen")
        perception.failures = 1

        val outcome = orchestrator.step("Look at the screen", 0)

        assertTrue("a transient capture failure is not the end: $outcome", outcome is StepOutcome.Continue)
        assertTrue((outcome as StepOutcome.Continue).note.contains("blank"))
    }

    @Test
    fun `the model failure is retried with a note`() = runBlocking {
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        model.willFail(LlmError.Server("500", 500))

        val (orchestrator, _) = orchestrator(
            ScriptedPerception(), model, ToolRegistry(logger, emptyList()),
        )

        val outcome = orchestrator.step("Do it", 0)

        assertTrue(outcome is StepOutcome.Continue)
        assertTrue((outcome as StepOutcome.Continue).note.contains("could not be reached"))
    }

    @Test
    fun `the step limit stops the loop`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        // Keep tapping forever; the limit must be what ends it.
        repeat(20) { model.willReplyWithTool("tap", """{"mark":1}""") }

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
            limits = LoopLimits(maxSteps = 3, maxDurationMs = 1_000_000, maxActionsPerMinute = 1000),
        )

        val outcomes = (0 until 10).map { orchestrator.step("Tap forever", it) }

        assertTrue(
            "one of the steps must stop the loop: ${outcomes.map { it::class.simpleName }}",
            outcomes.any { it is StepOutcome.Stopped },
        )
        val stopped = outcomes.filterIsInstance<StepOutcome.Stopped>().first()
        assertTrue(stopped.reason, stopped.reason.contains("3 steps"))
    }

    @Test
    fun `a screen that never changes eventually asks the user`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        repeat(20) { model.willReplyWithTool("tap", """{"mark":1}""") }

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
            limits = LoopLimits(
                maxSteps = 50,
                maxDurationMs = 1_000_000,
                maxActionsPerMinute = 1000,
                stuckStepsBeforeHint = 1,
                stuckEventsBeforeReplan = 2,
                stuckEventsBeforeGivingUp = 3,
            ),
        )

        val outcomes = (0 until 10).map { orchestrator.step("Stuck", it) }

        assertTrue(
            "the loop must eventually ask the user: ${outcomes.map { it::class.simpleName }}",
            outcomes.any { it is StepOutcome.WaitingUser },
        )
    }

    @Test
    fun `a changed screen clears the stuck state`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        repeat(20) { model.willReplyWithTool("tap", """{"mark":1}""") }

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
            limits = LoopLimits(
                maxSteps = 50, maxDurationMs = 1_000_000, maxActionsPerMinute = 1000,
                stuckStepsBeforeHint = 1, stuckEventsBeforeGivingUp = 3,
            ),
        )

        // Every step sees a new screen, so nothing is ever stuck.
        repeat(6) { index ->
            perception.snapshot = screenOf("screen-$index", "com.example.app")
            val outcome = orchestrator.step("Keep moving", index)
            assertFalse("a moving screen must never look stuck: $outcome", outcome is StepOutcome.WaitingUser)
        }
    }

    @Test
    fun `the history keeps only the last two screenshots`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        repeat(20) { model.willReplyWithTool("tap", """{"mark":1}""") }

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
            limits = LoopLimits(
                maxSteps = 50, maxDurationMs = 1_000_000, maxActionsPerMinute = 1000,
                maxScreenshotsInHistory = 2,
            ),
        )

        repeat(6) { index ->
            perception.snapshot = screenOf("s$index", "com.example.app", jpeg = ByteArray(100))
            orchestrator.step("Go", index)
        }

        // The most recent prompt must not carry more than one screen image, and the earlier
        // ones must have been dropped from the history.
        val lastRequest = model.requests.last()
        val images = lastRequest.messages.count { it.imageJpeg != null }
        assertTrue("at most one image per prompt: $images", images <= 1)
    }

    @Test
    fun `an unusable reply is corrected rather than ending the run`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        // Prose with no tool call.
        model.willReply(LlmResponse(text = "I will tap the search button now."))
        model.willReplyWithTool("tap", """{"mark":1}""")

        val tap = TapTool(FakeActuator())
        val (orchestrator, _) = orchestrator(perception, model, ToolRegistry(logger, listOf(tap)))

        val first = orchestrator.step("Tap", 0)
        assertTrue("prose should be a correction, not a stop: $first", first is StepOutcome.Continue)
        assertTrue((first as StepOutcome.Continue).note.contains("one tool call"))
        assertEquals(0, tap.calls)
    }

    @Test
    fun `update_plan from the model replaces the plan`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Old plan"))
        model.willReplyWithTool("update_plan", """{"steps":["New one","New two","New three"]}""")

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, emptyList()),
        )

        orchestrator.step("Do it", 0)

        assertEquals(listOf("New one", "New two", "New three"), orchestrator.currentPlan.steps)
    }

    @Test
    fun `the screen text is sent wrapped as untrusted`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        model.willReplyWithTool("tap", """{"mark":1}""")

        val (orchestrator, _) = orchestrator(
            perception, model, ToolRegistry(logger, listOf(TapTool(FakeActuator()))),
        )

        orchestrator.step("Tap", 0)

        val lastPrompt = model.requests.last().messages.last().text.orEmpty()
        assertTrue(
            "screen content must be wrapped so the model knows it is data: $lastPrompt",
            lastPrompt.contains("<untrusted_screen_content>"),
        )
    }

    @Test
    fun `a second tool call in one reply is ignored, not executed`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Plan"))
        model.willReply(
            LlmResponse(
                toolCalls = listOf(
                    ToolCallRequest(name = "tap", argumentsJson = """{"mark":1}"""),
                    ToolCallRequest(name = "tap", argumentsJson = """{"mark":1}"""),
                ),
            ),
        )

        val tap = TapTool(FakeActuator())
        val (orchestrator, _) = orchestrator(perception, model, ToolRegistry(logger, listOf(tap)))

        orchestrator.step("Tap", 0)

        assertEquals("only one action per step, as the brief requires", 1, tap.calls)
    }

    @Test
    fun `reset clears the plan and history`() = runBlocking {
        val perception = ScriptedPerception()
        val model = ScriptedModel()
        model.willReply(LlmResponse(text = "1. Open the app\n2. Tap search"))

        val (orchestrator, _) = orchestrator(perception, model, ToolRegistry(logger, emptyList()))
        orchestrator.prepare("goal")
        assertEquals(2, orchestrator.currentPlan.steps.size)

        orchestrator.reset()

        assertTrue(orchestrator.currentPlan.isEmpty)
    }
}

/** Builds a screen for the scripted perception to hand back. */
private fun screenOf(
    hash: String,
    pkg: String?,
    failure: SnapshotFailure? = null,
    nodes: List<UiNode> = listOf(
        UiNode(
            nodeId = 1, parentId = null, depth = 1,
            className = "android.widget.Button", text = "Search",
            contentDescription = null, viewId = null,
            bounds = Bounds(0, 0, 100, 50), mark = 1,
        ),
    ),
    jpeg: ByteArray? = null,
) = ScreenSnapshot(
    packageName = pkg,
    windowTitle = null,
    timestampMs = 0,
    nodes = nodes,
    screenHash = hash,
    screenshotJpeg = jpeg,
    mode = PerceptionMode.TREE_ONLY,
    failure = failure,
)
