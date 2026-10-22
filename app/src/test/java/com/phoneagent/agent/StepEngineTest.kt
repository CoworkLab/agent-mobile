package com.phoneagent.agent

import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepEngineTest {

    private val denylist = PackageDenylist(listOf("com.bank.app"))

    /**
     * Built from the shipped keyword file rather than a hand-written list, so a mistake in
     * `safety_keywords.json` fails here instead of passing a test that invented its own words.
     */
    private val guard: SafetyGuard = run {
        val json = java.io.File("src/main/assets/safety_keywords.json").readText()
        SafetyGuard(
            RiskClassifier(SafetyConfigLoader.parseKeywords(json), denylist),
            denylist,
        )
    }

    /** A tool that records what it was asked to do. */
    private class RecordingTool(
        override val name: String,
        override val riskLevel: com.phoneagent.safety.RiskLevel = com.phoneagent.safety.RiskLevel.LOW,
        private val result: ToolResult = ToolResult.ok("done", screenChanged = true),
    ) : Tool {
        override val description = "test"
        override val parametersSchema = schemaOf(emptyMap())
        var calls = 0
            private set

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            calls++
            return result
        }
    }

    private class RecordingGate(
        private val context: ToolContext,
    ) : ConfirmationGate {
        val requests = mutableListOf<Pair<ToolCall, String>>()
        private var pending: ToolCall? = null

        override fun request(call: ToolCall, reason: String) {
            pending = call
            requests += call to reason
        }

        override fun pending(): ToolCall? = pending

        override fun answer(approved: Boolean): ToolCall? {
            val answered = pending
            pending = null
            return if (approved) answered else null
        }

        override fun contextFor(snapshot: ScreenSnapshot): ToolContext = context
    }

    private fun context() = object : ToolContext {
        override fun currentNodes(): List<UiNode> = emptyList()
        override fun currentPackageName(): String? = "com.example.app"
        override suspend fun refreshScreen(): ScreenSnapshot = snapshot()
        override val actuator: com.phoneagent.tools.Actuator =
            com.phoneagent.tools.FakeActuator()
    }

    private fun snapshot(
        nodes: List<UiNode> = emptyList(),
        pkg: String = "com.example.app",
        mode: PerceptionMode = PerceptionMode.TREE_ONLY,
    ) = ScreenSnapshot(
        packageName = pkg,
        windowTitle = null,
        timestampMs = 0,
        nodes = nodes,
        screenHash = "hash",
        mode = mode,
    )

    private fun node(
        mark: Int,
        text: String? = null,
        clickable: Boolean = true,
        password: Boolean = false,
        editable: Boolean = false,
    ) = UiNode(
        nodeId = mark,
        parentId = null,
        depth = 1,
        className = "android.widget.Button",
        text = text,
        contentDescription = null,
        viewId = null,
        bounds = Bounds(0, mark * 100, 200, mark * 100 + 80),
        clickable = clickable,
        password = password,
        editable = editable,
        mark = mark,
    )

    private fun engine(
        tools: List<Tool>,
        gate: ConfirmationGate,
    ) = StepEngine(ToolRegistry(Logger(), tools), guard, gate)

    private fun act(name: String, vararg args: Pair<String, Any?>) =
        DecisionParser.Decision.Act(name, args.toMap(), "{}")

    @Test
    fun `a safe tap runs`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(
            act("tap", "mark" to 1),
            snapshot(listOf(node(1, text = "Search"))),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Acted)
        assertEquals(1, tap.calls)
    }

    @Test
    fun `a tap on a Send button is never executed and asks the user instead`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(
            act("tap", "mark" to 2),
            snapshot(listOf(node(2, text = "Send"))),
        ) { ToolResult.ok("ok") }

        assertTrue("expected a confirmation request, got $outcome", outcome is StepEngine.Outcome.NeedsConfirmation)
        assertEquals("nothing must have run", 0, tap.calls)
        assertEquals(1, gate.requests.size)
        assertNotNull("the pending call must be remembered", gate.pending())
    }

    @Test
    fun `the confirmation request carries the reason and the risk`() = runBlocking {
        val gate = RecordingGate(context())
        val engine = engine(listOf(RecordingTool("tap")), gate)

        val outcome = engine.run(
            act("tap", "mark" to 2),
            snapshot(listOf(node(2, text = "Pay now"))),
        ) { ToolResult.ok("ok") } as StepEngine.Outcome.NeedsConfirmation

        assertEquals(com.phoneagent.safety.RiskLevel.HIGH, outcome.risk)
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun `a Hindi Send button is treated exactly the same as an English one`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(
            act("tap", "mark" to 1),
            snapshot(listOf(node(1, text = "भेजो"))),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.NeedsConfirmation)
        assertEquals(0, tap.calls)
    }

    @Test
    fun `a blocked action is rejected without asking`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(
            act("tap", "mark" to 1),
            snapshot(listOf(node(1, text = "Factory reset"))),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Rejected)
        assertEquals("a blocked action must not ask the user either", 0, gate.requests.size)
        assertEquals(0, tap.calls)
    }

    @Test
    fun `nothing runs inside a restricted app`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(
            act("tap", "mark" to 1),
            snapshot(listOf(node(1, text = "Home")), pkg = "com.bank.app"),
        ) { ToolResult.ok("ok") }

        assertTrue("a denied package must refuse everything: $outcome", outcome is StepEngine.Outcome.Rejected)
        assertEquals(0, tap.calls)
    }

    @Test
    fun `typing into a password field asks the user`() = runBlocking {
        val typeText = RecordingTool("type_text")
        val gate = RecordingGate(context())
        val engine = engine(listOf(typeText), gate)

        val outcome = engine.run(
            act("type_text", "mark" to 1, "text" to "hunter2"),
            snapshot(listOf(node(1, password = true, editable = true, clickable = false))),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.NeedsConfirmation)
        assertEquals(0, typeText.calls)
    }

    @Test
    fun `a tool that is dangerous by itself asks the user even with no label`() = runBlocking {
        val dangerous = RecordingTool("delete_file", riskLevel = com.phoneagent.safety.RiskLevel.HIGH)
        val gate = RecordingGate(context())
        val engine = engine(listOf(dangerous), gate)

        val outcome = engine.run(act("delete_file", "path" to "/x"), snapshot()) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.NeedsConfirmation)
    }

    @Test
    fun `finish is handled without running the registry`() = runBlocking {
        val finish = RecordingTool("finish")
        val gate = RecordingGate(context())
        val engine = engine(listOf(finish), gate)

        val outcome = engine.run(
            DecisionParser.Decision.Finish("Sent the message."),
            snapshot(),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Finished)
        assertEquals("Sent the message.", (outcome as StepEngine.Outcome.Finished).summary)
    }

    @Test
    fun `ask_user pauses the loop`() = runBlocking {
        val gate = RecordingGate(context())
        val engine = engine(listOf(RecordingTool("ask_user")), gate)

        val outcome = engine.run(
            DecisionParser.Decision.AskUser("Which Amit?"),
            snapshot(),
        ) { ToolResult.ok("ok") }

        assertEquals("Which Amit?", (outcome as StepEngine.Outcome.Asked).question)
    }

    @Test
    fun `fail ends the run with the reason`() = runBlocking {
        val gate = RecordingGate(context())
        val engine = engine(listOf(RecordingTool("fail")), gate)

        val outcome = engine.run(
            DecisionParser.Decision.Fail("The app is not installed."),
            snapshot(),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Failed)
    }

    @Test
    fun `an unusable reply is rejected so the model can be corrected`() = runBlocking {
        val gate = RecordingGate(context())
        val engine = engine(listOf(RecordingTool("tap")), gate)

        val outcome = engine.run(
            DecisionParser.Decision.Unusable("You must reply with one tool call."),
            snapshot(),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Rejected)
        assertTrue((outcome as StepEngine.Outcome.Rejected).note.contains("one tool call"))
    }

    @Test
    fun `an unknown tool is rejected without touching the device`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap), gate)

        val outcome = engine.run(act("teleport"), snapshot()) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.Rejected)
        assertTrue((outcome as StepEngine.Outcome.Rejected).note.contains("teleport"))
        assertEquals(0, tap.calls)
    }

    @Test
    fun `update_plan replaces the plan and does not run a phone action`() = runBlocking {
        val tap = RecordingTool("tap")
        val gate = RecordingGate(context())
        val engine = engine(listOf(tap, RecordingTool("update_plan")), gate)

        val outcome = engine.run(
            act("update_plan", "steps" to listOf("Open the app", "Search", "Done")),
            snapshot(),
        ) { ToolResult.ok("ok") }

        assertTrue(outcome is StepEngine.Outcome.PlanUpdated)
        assertEquals(3, (outcome as StepEngine.Outcome.PlanUpdated).plan.steps.size)
        assertEquals(0, tap.calls)
    }

    @Test
    fun `an empty plan update is rejected`() = runBlocking {
        val gate = RecordingGate(context())
        val engine = engine(listOf(RecordingTool("update_plan")), gate)

        val outcome = engine.run(act("update_plan", "steps" to emptyList<String>()), snapshot()) {
            ToolResult.ok("ok")
        }

        assertTrue(outcome is StepEngine.Outcome.Rejected)
    }

    @Test
    fun `a failing tool is reported as a failure rather than a crash`() = runBlocking {
        val failing = RecordingTool("tap", result = ToolResult.fail("no such element"))
        val gate = RecordingGate(context())
        val engine = engine(listOf(failing), gate)

        val outcome = engine.run(act("tap", "mark" to 1), snapshot()) { ToolResult.ok("ok") }

        val acted = outcome as StepEngine.Outcome.Acted
        assertFalse(acted.success)
        assertEquals("no such element", acted.note)
    }

    @Test
    fun `the outcome note is the tool's message, so the model learns what happened`() = runBlocking {
        val tool = RecordingTool("tap", result = ToolResult.ok("Tapped element [1] (\"Search\")"))
        val gate = RecordingGate(context())
        val engine = engine(listOf(tool), gate)

        val outcome = engine.run(
            act("tap", "mark" to 1),
            snapshot(listOf(node(1, text = "Search"))),
        ) { ToolResult.ok("ok") } as StepEngine.Outcome.Acted

        assertTrue(outcome.note.contains("Search"))
    }
}

class PromptBuilderTest {

    private val system = "You are PhoneAgent. One tool call per step."

    private fun builder() = PromptBuilder(system, LoopLimits(maxSteps = 40))

    private fun snapshot(
        nodes: List<UiNode> = emptyList(),
        jpeg: ByteArray? = null,
    ) = ScreenSnapshot(
        packageName = "com.example.app",
        windowTitle = null,
        timestampMs = 0,
        nodes = nodes,
        screenHash = "h",
        screenshotJpeg = jpeg,
        mode = if (jpeg != null) PerceptionMode.TREE_PLUS_SCREENSHOT else PerceptionMode.TREE_ONLY,
    )

    private fun node(mark: Int, text: String) = UiNode(
        nodeId = mark,
        parentId = null,
        depth = 1,
        className = "android.widget.Button",
        text = text,
        contentDescription = null,
        viewId = null,
        bounds = Bounds(0, 0, 100, 50),
        mark = mark,
    )

    @Test
    fun `the system prompt is first`() {
        val built = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot())
        assertEquals(com.phoneagent.brain.LlmMessage.Role.SYSTEM, built.messages.first().role)
        assertEquals(system, built.messages.first().text)
    }

    @Test
    fun `the goal is stated in the prompt`() {
        val built = builder().build("Send hello to Amit", AgentPlan.parse("a"), emptyList(), snapshot())
        assertTrue(built.messages.any { it.text?.contains("Send hello to Amit") == true })
    }

    @Test
    fun `the plan is included`() {
        val built = builder().build("goal", AgentPlan.parse("Open app\nSearch"), emptyList(), snapshot())
        val joined = built.messages.mapNotNull { it.text }.joinToString("\n")
        assertTrue(joined.contains("Open app"))
    }

    @Test
    fun `the current screen is the last message, so it is freshest`() {
        val built = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot(listOf(node(1, "Search"))))
        val last = built.messages.last()
        assertTrue(last.text!!.contains("CURRENT SCREEN"))
        assertTrue(last.text!!.contains("Search"))
    }

    @Test
    fun `the screenshot travels with the screen message`() {
        val jpeg = ByteArray(50) { 7 }
        val built = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot(jpeg = jpeg))
        assertNotNull(built.messages.last().imageJpeg)
    }

    @Test
    fun `a screenshot is only attached to the current screen message`() {
        val history = listOf(
            HistoryCompactor.Entry(0, "did a thing", ByteArray(10), 10),
            HistoryCompactor.Entry(1, "did another", ByteArray(10), 10),
        )
        val built = builder().build("goal", AgentPlan.parse("a"), history, snapshot(jpeg = ByteArray(5)))

        assertEquals(
            "only the newest screen should carry an image",
            1,
            built.messages.count { it.imageJpeg != null },
        )
    }

    @Test
    fun `history becomes assistant messages`() {
        val history = listOf(HistoryCompactor.Entry(0, "opened the app"))
        val built = builder().build("goal", AgentPlan.parse("a"), history, snapshot())

        val summary = built.messages.firstOrNull {
            it.role == com.phoneagent.brain.LlmMessage.Role.ASSISTANT
        }
        assertTrue(summary!!.text!!.contains("opened the app"))
    }

    @Test
    fun `hints are included when present`() {
        val built = builder().build(
            "goal", AgentPlan.parse("a"), emptyList(), snapshot(),
            hints = listOf("Try scrolling."),
        )
        assertTrue(built.messages.any { it.text?.contains("Try scrolling.") == true })
    }

    @Test
    fun `no hints section appears when there are none`() {
        val built = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot())
        assertFalse(built.messages.any { it.text?.contains("HINTS:") == true })
    }

    @Test
    fun `the last action result is included so the model sees the effect`() {
        val built = builder().build(
            "goal", AgentPlan.parse("a"), emptyList(), snapshot(),
            lastResult = "Tapped element [1]",
        )
        assertTrue(built.messages.any { it.text?.contains("RESULT OF YOUR LAST ACTION") == true })
    }

    @Test
    fun `the user's answer is included after a question`() {
        val built = builder().build(
            "goal", AgentPlan.parse("a"), emptyList(), snapshot(),
            userAnswer = "Amit Sharma",
        )
        assertTrue(built.messages.any { it.text?.contains("Amit Sharma") == true })
    }

    @Test
    fun `the step number is shown`() {
        val built = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot(), stepIndex = 4)
        assertTrue(built.messages.any { it.text?.contains("STEP: 5 of 40") == true })
    }

    @Test
    fun `the size estimate counts the screenshot`() {
        val small = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot())
        val withImage = builder().build("goal", AgentPlan.parse("a"), emptyList(), snapshot(jpeg = ByteArray(1000)))

        assertTrue(
            "an image is not free, and the trimmer must know that",
            withImage.estimatedChars > small.estimatedChars + 100_000,
        )
    }
}

class DecisionParserTest {

    private val tools = setOf("tap", "type_text", "finish", "ask_user", "fail", "batch_actions")

    @Test
    fun `a native tool call is parsed`() {
        val decision = DecisionParser.parse("tap", """{"mark":3}""", null, tools)

        val act = decision as DecisionParser.Decision.Act
        assertEquals("tap", act.tool)
        assertEquals(3L, act.args["mark"])
    }

    @Test
    fun `an unknown tool name is unusable, and the valid names are listed`() {
        val decision = DecisionParser.parse("teleport", "{}", null, tools)

        val unusable = decision as DecisionParser.Decision.Unusable
        assertTrue(unusable.reason, unusable.reason.contains("teleport"))
        assertTrue(unusable.reason, unusable.reason.contains("tap"))
    }

    @Test
    fun `arguments given as a json string are accepted`() {
        val decision = DecisionParser.parse("tap", """"{\"mark\":1}"""", null, tools)

        val act = decision as DecisionParser.Decision.Act
        assertEquals(1L, act.args["mark"])
    }

    @Test
    fun `nested arguments are preserved`() {
        val decision = DecisionParser.parse(
            "batch_actions",
            """{"actions":[{"tool":"tap","args":{"mark":1}}]}""",
            null,
            tools,
        )

        val act = decision as DecisionParser.Decision.Act
        @Suppress("UNCHECKED_CAST")
        val actions = act.args["actions"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val args = actions[0]["args"] as Map<String, Any?>
        assertEquals(1L, args["mark"])
    }

    @Test
    fun `argument types are preserved`() {
        val decision = DecisionParser.parse(
            "type_text",
            """{"mark":2,"text":"hi","clear_first":false,"timeout":1.5}""",
            null,
            tools,
        )

        val act = decision as DecisionParser.Decision.Act
        assertEquals(2L, act.args["mark"])
        assertEquals("hi", act.args["text"])
        assertEquals(false, act.args["clear_first"])
        assertEquals(1.5, act.args["timeout"])
    }

    @Test
    fun `bad argument json is unusable rather than fatal`() {
        val decision = DecisionParser.parse("tap", "{not json", null, tools)
        assertTrue(decision is DecisionParser.Decision.Unusable)
    }

    @Test
    fun `a null argument string is an empty argument set`() {
        val decision = DecisionParser.parse("tap", null, null, tools) as DecisionParser.Decision.Act
        assertTrue(decision.args.isEmpty())
    }

    @Test
    fun `prose with no tool call is unusable, and the model is told the format`() {
        val decision = DecisionParser.parse(null, null, "I will tap the button.", tools)

        val unusable = decision as DecisionParser.Decision.Unusable
        assertTrue(unusable.reason, unusable.reason.contains("exactly one tool call"))
    }

    @Test
    fun `a fenced json tool call in prose is understood`() {
        val text = """
            Sure!
            ```json
            {"tool":"tap","args":{"mark":5}}
            ```
        """.trimIndent()

        val decision = DecisionParser.parse(null, null, text, tools)

        val act = decision as DecisionParser.Decision.Act
        assertEquals("tap", act.tool)
        assertEquals(5L, act.args["mark"])
    }

    @Test
    fun `an empty reply is unusable`() {
        assertTrue(DecisionParser.parse(null, null, "   ", tools) is DecisionParser.Decision.Unusable)
        assertTrue(DecisionParser.parse(null, null, null, tools) is DecisionParser.Decision.Unusable)
    }

    @Test
    fun `a fenced call to an unknown tool is not accepted`() {
        val decision = DecisionParser.parse(null, null, """{"tool":"teleport","args":{}}""", tools)
        assertTrue(decision is DecisionParser.Decision.Unusable)
    }

    @Test
    fun `finish can be requested through the json path`() {
        val decision = DecisionParser.parse(
            null,
            null,
            """{"tool":"finish","args":{"summary":"done"}}""",
            tools,
        )

        val act = decision as DecisionParser.Decision.Act
        assertEquals("finish", act.tool)
        assertEquals("done", act.args["summary"])
    }

    @Test
    fun `parseArguments rejects a json array`() {
        assertNull(DecisionParser.parseArguments("[1,2,3]"))
    }

    @Test
    fun `parseArguments treats blank input as empty`() {
        assertEquals(emptyMap<String, Any?>(), DecisionParser.parseArguments(""))
        assertEquals(emptyMap<String, Any?>(), DecisionParser.parseArguments(null))
    }
}
