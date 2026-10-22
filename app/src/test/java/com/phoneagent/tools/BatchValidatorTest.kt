package com.phoneagent.tools

import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.KeywordSets
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchValidatorTest {

    private val keywords = KeywordSets(
        high = listOf("send", "pay", "delete", "submit", "confirm"),
        medium = listOf("save", "settings"),
        blocked = listOf("factory reset"),
        injectionKeywords = emptyList(),
        paymentUriSchemes = emptyList(),
    )
    private val guard = SafetyGuard(
        RiskClassifier(keywords, PackageDenylist(listOf("com.bank.app"))),
        PackageDenylist(listOf("com.bank.app")),
    )
    private val validator = BatchValidator(guard)

    private fun node(
        id: Int,
        mark: Int? = null,
        text: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        scrollable: Boolean = false,
        bounds: Bounds = Bounds(0, id * 100, 300, id * 100 + 80),
    ) = UiNode(
        nodeId = id, parentId = null, depth = 1, className = "android.widget.Button",
        text = text, contentDescription = null, viewId = null, bounds = bounds,
        clickable = clickable, editable = editable, password = password, scrollable = scrollable,
        mark = mark,
    )

    private fun snapshot(
        nodes: List<UiNode>,
        mode: PerceptionMode = PerceptionMode.TREE_ONLY,
        pkg: String = "com.example.chat",
    ) = ScreenSnapshot(
        packageName = pkg,
        windowTitle = null,
        timestampMs = 0,
        nodes = nodes,
        screenHash = "h",
        mode = mode,
    )

    private fun action(tool: String, vararg args: Pair<String, Any?>) =
        BatchAction(tool, args.toMap())

    @Test
    fun `a safe sequence is accepted`() {
        val screen = snapshot(
            listOf(
                node(1, mark = 1, text = "Search", clickable = true),
                node(2, mark = 2, editable = true),
            ),
        )
        val result = validator.validate(
            listOf(
                action("tap", "mark" to 1),
                action("type_text", "mark" to 2, "text" to "weather"),
                action("press_enter", "mark" to 2),
            ),
            screen,
        )
        assertTrue(result.reason, result.accepted)
        assertEquals(3, result.perAction.size)
    }

    @Test
    fun `a send button in the batch rejects the whole batch`() {
        val screen = snapshot(
            listOf(
                node(1, mark = 1, editable = true),
                node(2, mark = 2, text = "Send", clickable = true),
            ),
        )
        val result = validator.validate(
            listOf(
                action("type_text", "mark" to 1, "text" to "hello"),
                action("tap", "mark" to 2),
            ),
            screen,
        )
        assertFalse(result.accepted)
        assertTrue(result.reason, result.reason.contains("separate step"))
        assertEquals("the rejected action is identified", 2, result.perAction.last().index + 1)
    }

    @Test
    fun `a pay button in the batch rejects the whole batch`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "Pay", clickable = true)))
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertFalse(result.accepted)
    }

    @Test
    fun `a blocked phrase in the batch rejects the whole batch`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "Factory reset", clickable = true)))
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertFalse(result.accepted)
    }

    @Test
    fun `a password field in the batch rejects the whole batch`() {
        val screen = snapshot(listOf(node(1, mark = 1, editable = true, password = true)))
        val result = validator.validate(
            listOf(action("type_text", "mark" to 1, "text" to "hunter2")),
            screen,
        )
        assertFalse(result.accepted)
    }

    @Test
    fun `more than five actions is rejected`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "A", clickable = true)))
        val six = (1..6).map { action("tap", "mark" to 1) }
        val result = validator.validate(six, screen)
        assertFalse(result.accepted)
        assertTrue(result.reason, result.reason.contains("at most 5"))
    }

    @Test
    fun `an empty batch is rejected`() {
        val result = validator.validate(emptyList(), snapshot(emptyList()))
        assertFalse(result.accepted)
    }

    @Test
    fun `a tool that is not batch-safe is rejected`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "A", clickable = true)))
        listOf("launch_intent", "open_app", "back", "home", "recents", "finish", "fail", "ask_user")
            .forEach { tool ->
                val result = validator.validate(listOf(action(tool)), screen)
                assertFalse("$tool must not be batchable", result.accepted)
                assertTrue(result.reason, result.reason.contains("own step"))
            }
    }

    @Test
    fun `a nested batch is rejected`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "A", clickable = true)))
        val result = validator.validate(listOf(action("batch_actions")), screen)
        assertFalse(result.accepted)
    }

    @Test
    fun `a screenshot-only screen rejects batching because marks are unreliable`() {
        val screen = snapshot(
            listOf(node(1, mark = 1, text = "A", clickable = true)),
            mode = PerceptionMode.SCREENSHOT_ONLY,
        )
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertFalse(result.accepted)
        assertTrue(result.reason, result.reason.contains("no readable structure"))
    }

    @Test
    fun `a screen with no tree rejects batching`() {
        val screen = snapshot(emptyList())
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertFalse(result.accepted)
    }

    @Test
    fun `a long wait inside a batch is rejected`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "A", clickable = true)))
        val result = validator.validate(listOf(action("wait", "seconds" to 5)), screen)
        assertFalse(result.accepted)
        assertTrue(result.reason, result.reason.contains("at most 2s"))
    }

    @Test
    fun `a short wait inside a batch is accepted`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "A", clickable = true)))
        val result = validator.validate(listOf(action("wait", "seconds" to 1)), screen)
        assertTrue(result.reason, result.accepted)
    }

    @Test
    fun `a denied package rejects the batch even for a harmless tap`() {
        val screen = snapshot(
            listOf(node(1, mark = 1, text = "OK", clickable = true)),
            pkg = "com.bank.app",
        )
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertFalse(result.accepted)
    }

    @Test
    fun `medium-risk actions are allowed inside a batch but logged`() {
        val screen = snapshot(listOf(node(1, mark = 1, text = "Save", clickable = true)))
        val result = validator.validate(listOf(action("tap", "mark" to 1)), screen)
        assertTrue("medium is logged, not confirmed, so a batch may proceed", result.accepted)
    }
}

class LayoutHasherTest {

    private fun node(
        id: Int,
        text: String? = null,
        editable: Boolean = false,
        clickable: Boolean = true,
        bounds: Bounds = Bounds(0, id * 100, 300, id * 100 + 80),
        className: String? = "android.widget.Button",
    ) = UiNode(
        nodeId = id, parentId = null, depth = 1, className = className, text = text,
        contentDescription = null, viewId = null, bounds = bounds, clickable = clickable,
        editable = editable,
    )

    @Test
    fun `identical layouts hash the same`() {
        val a = listOf(node(1, text = "Search"), node(2, text = "Home"))
        val b = listOf(node(1, text = "Search"), node(2, text = "Home"))
        assertEquals(LayoutHasher.layoutHash(a), LayoutHasher.layoutHash(b))
    }

    @Test
    fun `typed text in an editable field does not change the hash`() {
        // This is the whole point: typing changes node text, and a batch must not abort
        // merely because the user's own input landed.
        val before = listOf(node(1, text = "", editable = true))
        val after = listOf(node(1, text = "hello world", editable = true))
        assertEquals(LayoutHasher.layoutHash(before), LayoutHasher.layoutHash(after))
    }

    @Test
    fun `typed text in a non-editable field does change the hash`() {
        val before = listOf(node(1, text = "0 items", editable = false))
        val after = listOf(node(1, text = "1 item", editable = false))
        assertNotEquals(LayoutHasher.layoutHash(before), LayoutHasher.layoutHash(after))
    }

    @Test
    fun `a moved node changes the hash`() {
        val before = listOf(node(1, bounds = Bounds(0, 0, 300, 80)))
        val after = listOf(node(1, bounds = Bounds(0, 200, 300, 280)))
        assertNotEquals(LayoutHasher.layoutHash(before), LayoutHasher.layoutHash(after))
    }

    @Test
    fun `a changed clickable flag changes the hash`() {
        val before = listOf(node(1, clickable = true))
        val after = listOf(node(1, clickable = false))
        assertNotEquals(LayoutHasher.layoutHash(before), LayoutHasher.layoutHash(after))
    }

    @Test
    fun `a changed class changes the hash`() {
        val before = listOf(node(1, className = "android.widget.Button"))
        val after = listOf(node(1, className = "android.widget.TextView"))
        assertNotEquals(LayoutHasher.layoutHash(before), LayoutHasher.layoutHash(after))
    }

    @Test
    fun `a small jitter within tolerance is not treated as movement`() {
        val before = node(1, bounds = Bounds(100, 100, 400, 180))
        val after = node(1, bounds = Bounds(104, 103, 404, 183))
        assertFalse(LayoutHasher.movedTooFar(before, after))
    }

    @Test
    fun `a real movement is detected`() {
        val before = node(1, bounds = Bounds(100, 100, 400, 180))
        val after = node(1, bounds = Bounds(100, 600, 400, 680))
        assertTrue(LayoutHasher.movedTooFar(before, after))
    }

    @Test
    fun `a resized node is detected even when it has not moved`() {
        val before = node(1, bounds = Bounds(0, 0, 300, 80))
        val after = node(1, bounds = Bounds(0, 0, 300, 200))
        assertTrue(LayoutHasher.movedTooFar(before, after))
    }
}
