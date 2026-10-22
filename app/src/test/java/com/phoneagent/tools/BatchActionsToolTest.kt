package com.phoneagent.tools

import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.safety.KeywordSets
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchActionsToolTest {

    private val guard = SafetyGuard(
        RiskClassifier(
            KeywordSets(
                high = listOf("send", "pay", "delete", "submit", "confirm"),
                medium = listOf("save", "settings"),
                blocked = listOf("factory reset"),
                injectionKeywords = emptyList(),
                paymentUriSchemes = emptyList(),
            ),
            PackageDenylist(listOf("com.bank.app")),
        ),
        PackageDenylist(listOf("com.bank.app")),
    )

    private fun tool(registry: ToolRegistry) = BatchActionsTool(
        validator = BatchValidator(guard),
        safetyGuard = guard,
        registry = registry,
        logger = Logger(),
    )

    /** A registry of the real batchable tools, so the batch runs real code paths. */
    private fun innerRegistry() = ToolRegistry(
        Logger(),
        TouchTools { 1080 to 2400 }.all + TextTools { 1080 to 2400 }.all,
    )

    private fun batch(vararg actions: BatchAction): ToolCall =
        ToolCall(
            "batch_actions",
            mapOf(
                "actions" to actions.map { action ->
                    mapOf("tool" to action.tool, "args" to action.args)
                },
            ),
        )

    private fun stableScreen() = testSnapshot(
        listOf(
            testNode(1, mark = 1, editable = true, viewId = "com.app:id/search",
                bounds = Bounds(0, 100, 900, 200)),
            testNode(2, mark = 2, clickable = true, text = "Go",
                bounds = Bounds(0, 300, 200, 400)),
        ),
    )

    @Test
    fun `a safe batch runs every action in order`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(stableScreen(), actuator)
        context.onRefresh = { stableScreen() }

        val result = tool(innerRegistry()).execute(
            batch(
                BatchAction("tap", mapOf("mark" to 2)),
                BatchAction("type_text", mapOf("mark" to 1, "text" to "hello")),
            ),
            context,
        )

        assertTrue(result.message, result.success)
        assertEquals(1, actuator.taps.size)
        assertEquals("hello", actuator.fieldText[1])
        assertTrue(result.message, result.message.contains("2 of 2 actions done"))
    }

    @Test
    fun `a batch containing a send tap is refused before anything runs`() = runBlocking {
        val actuator = FakeActuator()
        val screen = testSnapshot(
            listOf(
                testNode(1, mark = 1, editable = true, bounds = Bounds(0, 100, 900, 200)),
                testNode(2, mark = 2, clickable = true, text = "Send",
                    bounds = Bounds(0, 300, 200, 400)),
            ),
        )
        val context = FakeToolContext(screen, actuator)
        context.onRefresh = { screen }

        val result = tool(innerRegistry()).execute(
            batch(
                BatchAction("type_text", mapOf("mark" to 1, "text" to "hi")),
                BatchAction("tap", mapOf("mark" to 2)),
            ),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("separate step"))
        assertTrue("nothing at all should have run", actuator.taps.isEmpty())
        assertTrue("not even the safe first action", actuator.fieldText.isEmpty())
    }

    @Test
    fun `a batch on a screenshot-only screen is refused because marks are unreliable`() = runBlocking {
        val screen = testSnapshot(
            listOf(testNode(1, mark = 1, clickable = true)),
            mode = PerceptionMode.SCREENSHOT_ONLY,
        )
        val context = FakeToolContext(screen)
        context.onRefresh = { screen }

        val result = tool(innerRegistry()).execute(
            batch(BatchAction("tap", mapOf("mark" to 1))),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("no readable structure"))
    }

    @Test
    fun `an action that fails stops the batch and the rest are not attempted`() = runBlocking {
        val actuator = FakeActuator().apply { acceptGestures = false }
        val context = FakeToolContext(stableScreen(), actuator)
        context.onRefresh = { stableScreen() }

        val result = tool(innerRegistry()).execute(
            batch(
                BatchAction("tap", mapOf("mark" to 2)),
                BatchAction("type_text", mapOf("mark" to 1, "text" to "never")),
            ),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("stopped at action 1"))
        assertTrue("the second action must not run", actuator.fieldText.isEmpty())
    }

    @Test
    fun `a target that disappears mid-batch stops the batch`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(stableScreen(), actuator)

        // After the first action the element the second action needs is no longer marked.
        // The layout is otherwise identical, so this exercises the identity check rather
        // than the layout-change check.
        val withoutMark2 = testSnapshot(
            listOf(
                testNode(1, mark = 1, editable = true, viewId = "com.app:id/search",
                    bounds = Bounds(0, 100, 900, 200)),
                testNode(2, mark = null, clickable = true, text = "Go",
                    bounds = Bounds(0, 300, 200, 400)),
            ),
        )
        var refreshes = 0
        context.onRefresh = {
            refreshes++
            if (refreshes <= 2) stableScreen() else withoutMark2
        }

        val result = tool(innerRegistry()).execute(
            batch(
                BatchAction("tap", mapOf("mark" to 2)),
                BatchAction("type_text", mapOf("mark" to 2, "text" to "x")),
            ),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("[2] is no longer on screen"))
    }

    @Test
    fun `a batch is rejected when it has too many actions`() = runBlocking {
        val context = FakeToolContext(stableScreen())
        context.onRefresh = { stableScreen() }

        val result = tool(innerRegistry()).execute(
            batch(
                *(1..6).map { BatchAction("tap", mapOf("mark" to 2)) }.toTypedArray(),
            ),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("at most 5"))
    }

    @Test
    fun `a terminal tool inside a batch is refused`() = runBlocking {
        val context = FakeToolContext(stableScreen())
        context.onRefresh = { stableScreen() }

        val result = tool(innerRegistry()).execute(
            batch(BatchAction("finish", mapOf("summary" to "done"))),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("own step"))
    }

    @Test
    fun `a malformed action list is refused`() = runBlocking {
        val context = FakeToolContext(stableScreen())
        val result = tool(innerRegistry()).execute(
            ToolCall("batch_actions", mapOf("actions" to listOf(mapOf("nope" to 1)))),
            context,
        )
        assertFalse(result.success)
    }

    @Test
    fun `a missing actions argument is refused`() = runBlocking {
        val context = FakeToolContext(stableScreen())
        val result = tool(innerRegistry()).execute(ToolCall("batch_actions"), context)
        assertFalse(result.success)
    }

    @Test
    fun `batch_actions is not itself batchable`() {
        assertFalse(tool(innerRegistry()).allowedInBatch)
    }

    @Test
    fun `a batch inside a denied app is refused`() = runBlocking {
        val screen = testSnapshot(
            listOf(testNode(1, mark = 1, clickable = true)),
            pkg = "com.bank.app",
        )
        val context = FakeToolContext(screen)
        context.onRefresh = { screen }

        val result = tool(innerRegistry()).execute(
            batch(BatchAction("tap", mapOf("mark" to 1))),
            context,
        )

        assertFalse(result.success)
    }

    @Test
    fun `the result includes a fresh screen so the next decision sees reality`() = runBlocking {
        val context = FakeToolContext(stableScreen())
        context.onRefresh = { stableScreen() }

        val result = tool(innerRegistry()).execute(
            batch(BatchAction("tap", mapOf("mark" to 2))),
            context,
        )

        assertTrue(result.success)
        assertTrue(result.message, result.message.contains("The screen now"))
        assertTrue(result.message, result.message.contains("Go"))
    }

    @Test
    fun `a node identity hash changes when the node's label changes`() {
        val first = testNode(1, mark = 1, text = "Send", viewId = null)
        val second = testNode(1, mark = 1, text = "Sent", viewId = null)
        assertFalse(
            "a changed label means it is a different element",
            first.identityHash() == second.identityHash(),
        )
    }

    @Test
    fun `a node identity hash is stable when only bounds move`() {
        val first = testNode(1, mark = 1, text = "Go", viewId = "com.app:id/go",
            bounds = Bounds(0, 0, 100, 50))
        val second = testNode(1, mark = 1, text = "Go", viewId = "com.app:id/go",
            bounds = Bounds(0, 400, 100, 450))
        assertEquals(
            "a view id identifies the element even when the list scrolls",
            first.identityHash(),
            second.identityHash(),
        )
    }
}
