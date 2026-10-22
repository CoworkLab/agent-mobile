package com.phoneagent.agent

import com.phoneagent.audit.AuditDecision
import com.phoneagent.audit.AuditLog
import com.phoneagent.audit.InMemoryAuditLog
import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.FakeActuator
import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.tools.ToolResult
import com.phoneagent.tools.schemaOf
import com.phoneagent.util.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidConfirmationGateTest {

    private val logger = Logger()

    private fun context() = object : ToolContext {
        override fun currentNodes(): List<UiNode> = emptyList()
        override fun currentPackageName(): String? = "com.example.app"
        override suspend fun refreshScreen(): ScreenSnapshot = snapshot()
        override val actuator = FakeActuator()
    }

    private fun snapshot() = ScreenSnapshot(
        packageName = "com.example.app",
        windowTitle = null,
        timestampMs = 0,
        nodes = emptyList(),
        screenHash = "h",
        mode = PerceptionMode.TREE_ONLY,
    )

    private fun gate(
        timeoutMs: Long = 1000,
        asked: MutableList<String> = mutableListOf(),
    ) = AndroidConfirmationGate(
        logger = logger,
        timeoutMs = timeoutMs,
        onAsk = { call, _ -> asked += call.name },
        contextProvider = { context() },
    )

    @Test
    fun `the question is announced when it is asked`() = runBlocking {
        val asked = mutableListOf<String>()
        val gate = gate(asked = asked)

        gate.request(ToolCall("tap", mapOf("mark" to 1)), "This sends a message.")

        assertEquals(listOf("tap"), asked)
        assertEquals("This sends a message.", gate.pendingQuestion())
    }

    @Test
    fun `the pending call is remembered so the answer applies to it`() = runBlocking {
        val gate = gate()
        val call = ToolCall("tap", mapOf("mark" to 3))

        gate.request(call, "risky")

        assertEquals(call, gate.pending())
    }

    @Test
    fun `an approval resolves as true`() = runBlocking {
        val gate = gate()
        gate.request(ToolCall("tap", emptyMap()), "risky")

        val waiting = async { gate.await() }
        delay(50)
        gate.submitAnswer(true)

        assertTrue(waiting.await())
    }

    @Test
    fun `a denial resolves as false`() = runBlocking {
        val gate = gate()
        gate.request(ToolCall("tap", emptyMap()), "risky")

        val waiting = async { gate.await() }
        delay(50)
        gate.submitAnswer(false)

        assertFalse(waiting.await())
    }

    @Test
    fun `no answer within the timeout is a denial`() = runBlocking {
        val gate = gate(timeoutMs = 100)

        gate.request(ToolCall("tap", emptyMap()), "risky")

        assertFalse("a timeout must never become a yes", gate.await())
    }

    @Test
    fun `waiting with nothing pending is a denial rather than a hang`() = runBlocking {
        assertFalse(gate().await())
    }

    @Test
    fun `the pending call is cleared after an answer`() = runBlocking {
        val gate = gate()
        gate.request(ToolCall("tap", emptyMap()), "risky")

        val waiting = async { gate.await() }
        delay(50)
        gate.submitAnswer(true)
        waiting.await()

        assertNull(gate.pending())
        assertNull(gate.pendingQuestion())
    }

    @Test
    fun `the context is the screen that was current when the question was asked`() {
        val gate = gate()
        val context = gate.contextFor(snapshot())

        assertEquals("com.example.app", context.currentPackageName())
    }

    @Test
    fun `a second question replaces the first rather than mixing the answers`() = runBlocking {
        val gate = gate()
        gate.request(ToolCall("tap", emptyMap()), "first")
        gate.request(ToolCall("back", emptyMap()), "second")

        assertEquals("back", gate.pending()!!.name)
    }
}

class PendingConfirmationTest {

    @Test
    fun `a fresh confirmation has no answer`() {
        val pending = PendingConfirmation()
        pending.set(ToolCall("tap", emptyMap()))

        assertFalse(pending.hasAnswer)
        assertNull(pending.consume())
    }

    @Test
    fun `an answer is readable exactly once`() {
        val pending = PendingConfirmation()
        pending.set(ToolCall("tap", mapOf("mark" to 2)))
        pending.resolve(true)

        assertTrue(pending.hasAnswer)
        val (call, approved) = pending.consume()!!
        assertEquals("tap", call.name)
        assertTrue(approved)

        assertNull("the answer must not be reusable", pending.consume())
    }

    @Test
    fun `a denial is recorded as false`() {
        val pending = PendingConfirmation()
        pending.set(ToolCall("tap", emptyMap()))
        pending.resolve(false)

        assertFalse(pending.consume()!!.second)
    }
}

/**
 * The approval path end to end: a risky action pauses, and an approval runs the exact call
 * that was shown rather than letting the model choose again.
 */
class ApprovalResumeTest {

    private val logger = Logger()

    private fun guard(denied: List<String> = emptyList()): SafetyGuard {
        val json = java.io.File("src/main/assets/safety_keywords.json").readText()
        val denylist = PackageDenylist(denied)
        return SafetyGuard(RiskClassifier(SafetyConfigLoader.parseKeywords(json), denylist), denylist)
    }

    private class CountingTool : Tool {
        override val name = "tap"
        override val description = "tap"
        override val parametersSchema = schemaOf(emptyMap())
        var calls = 0
            private set
        var lastMark: Int? = null
            private set

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            calls++
            lastMark = (call.args["mark"] as? Number)?.toInt()
            return ToolResult.ok("Tapped element [${lastMark}]", screenChanged = true)
        }
    }

    private class Gate(private val context: ToolContext) : ConfirmationGate {
        var requested: ToolCall? = null
        override fun request(call: ToolCall, reason: String) {
            requested = call
        }

        override fun pending(): ToolCall? = requested
        override fun answer(approved: Boolean): ToolCall? = requested
        override fun contextFor(snapshot: ScreenSnapshot): ToolContext = context
    }

    private fun snapshot(text: String, pkg: String = "com.example.app") = ScreenSnapshot(
        packageName = pkg,
        windowTitle = null,
        timestampMs = 0,
        nodes = listOf(
            UiNode(
                nodeId = 1, parentId = null, depth = 1,
                className = "android.widget.Button", text = text,
                contentDescription = null, viewId = null,
                bounds = Bounds(0, 0, 100, 50), mark = 1,
            ),
        ),
        screenHash = "h",
        mode = PerceptionMode.TREE_ONLY,
    )

    private fun context() = object : ToolContext {
        override fun currentNodes(): List<UiNode> = emptyList()
        override fun currentPackageName(): String? = "com.example.app"
        override suspend fun refreshScreen(): ScreenSnapshot = snapshot("Send")
        override val actuator = FakeActuator()
    }

    private fun engine(
        tool: Tool,
        gate: ConfirmationGate,
        audit: AuditLog = InMemoryAuditLog(),
        denied: List<String> = emptyList(),
    ) = StepEngine(ToolRegistry(logger, listOf(tool)), guard(denied), gate, audit)

    private fun act(name: String, vararg args: Pair<String, Any?>) =
        DecisionParser.Decision.Act(name, args.toMap(), "{}")

    @Test
    fun `a risky action does not run and its call is remembered`() = runBlocking {
        val tool = CountingTool()
        val gate = Gate(context())
        val engine = engine(tool, gate)

        val outcome = engine.run(act("tap", "mark" to 1), snapshot("Send")) { ToolResult.ok("x") }

        assertTrue(outcome is StepEngine.Outcome.NeedsConfirmation)
        assertEquals(0, tool.calls)
        assertEquals(1, (gate.requested!!.args["mark"] as Number).toInt())
    }

    @Test
    fun `an approved call runs the same mark that was approved`() = runBlocking {
        val tool = CountingTool()
        val gate = Gate(context())
        val engine = engine(tool, gate)

        engine.run(act("tap", "mark" to 1), snapshot("Send")) { ToolResult.ok("x") }
        val approved = gate.requested!!

        val outcome = engine.runApproved(approved.name, approved.args, snapshot("Send"))

        assertTrue(outcome is StepEngine.Outcome.Acted)
        assertEquals(1, tool.calls)
        assertEquals("the approved mark, not a new one", 1, tool.lastMark)
    }

    @Test
    fun `an approved action is recorded as approved, not as allowed`() = runBlocking {
        val audit = InMemoryAuditLog()
        val tool = CountingTool()
        val gate = Gate(context())
        val engine = engine(tool, gate, audit)

        engine.run(act("tap", "mark" to 1), snapshot("Send")) { ToolResult.ok("x") }
        engine.runApproved("tap", mapOf("mark" to 1), snapshot("Send"))

        val event = audit.recent().first()
        assertEquals(AuditDecision.APPROVED, event.decision)
    }

    @Test
    fun `an action that becomes blocked while the question is open is refused`() = runBlocking {
        val tool = CountingTool()
        val gate = Gate(context())
        val engine = engine(tool, gate, denied = listOf("com.bank.app"))

        engine.run(act("tap", "mark" to 1), snapshot("Send")) { ToolResult.ok("x") }

        // The screen moved to a blocked app while the dialog was up.
        val outcome = engine.runApproved("tap", mapOf("mark" to 1), snapshot("Home", pkg = "com.bank.app"))

        assertTrue("a blocked screen overrides an approval: $outcome", outcome is StepEngine.Outcome.Rejected)
        assertEquals(0, tool.calls)
    }

    @Test
    fun `a denial is recorded with the right decision`() = runBlocking {
        val audit = InMemoryAuditLog()
        val gate = Gate(context())
        val engine = engine(CountingTool(), gate, audit)

        engine.recordConfirmationOutcome(
            "tap", com.phoneagent.safety.RiskLevel.HIGH,
            approved = false, timedOut = false, snapshot = snapshot("Send"),
        )

        assertEquals(AuditDecision.DENIED, audit.recent().first().decision)
    }

    @Test
    fun `a timeout is recorded differently from a denial`() = runBlocking {
        val audit = InMemoryAuditLog()
        val gate = Gate(context())
        val engine = engine(CountingTool(), gate, audit)

        engine.recordConfirmationOutcome(
            "tap", com.phoneagent.safety.RiskLevel.HIGH,
            approved = false, timedOut = true, snapshot = snapshot("Send"),
        )

        assertEquals(AuditDecision.TIMED_OUT, audit.recent().first().decision)
    }

    @Test
    fun `an allowed action is recorded`() = runBlocking {
        val audit = InMemoryAuditLog()
        val tool = CountingTool()
        val gate = Gate(context())
        val engine = engine(tool, gate, audit)

        engine.run(act("tap", "mark" to 1), snapshot("Search")) { ToolResult.ok("x") }

        val event = audit.recent().first()
        assertEquals(AuditDecision.ALLOWED, event.decision)
        assertEquals("com.example.app", event.packageName)
    }

    @Test
    fun `a blocked action is recorded as blocked`() = runBlocking {
        val audit = InMemoryAuditLog()
        val gate = Gate(context())
        val engine = engine(CountingTool(), gate, audit)

        engine.run(act("tap", "mark" to 1), snapshot("Factory reset")) { ToolResult.ok("x") }

        assertEquals(AuditDecision.BLOCKED, audit.recent().first().decision)
    }

    @Test
    fun `a failing action is recorded as failed, not as allowed`() = runBlocking {
        val audit = InMemoryAuditLog()
        val gate = Gate(context())
        val failing = object : Tool {
            override val name = "tap"
            override val description = "tap"
            override val parametersSchema = schemaOf(emptyMap())
            override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult =
                ToolResult.fail("no such element")
        }
        val engine = StepEngine(ToolRegistry(logger, listOf(failing)), guard(), gate, audit)

        engine.run(act("tap", "mark" to 1), snapshot("Search")) { ToolResult.ok("x") }

        assertEquals(AuditDecision.FAILED, audit.recent().first().decision)
    }

    @Test
    fun `asking for approval is not itself recorded as a denial`() = runBlocking {
        val audit = InMemoryAuditLog()
        val gate = Gate(context())
        val engine = engine(CountingTool(), gate, audit)

        engine.run(act("tap", "mark" to 1), snapshot("Send")) { ToolResult.ok("x") }

        assertTrue(
            "the outcome is unknown while the question is open",
            audit.recent().isEmpty(),
        )
    }
}
