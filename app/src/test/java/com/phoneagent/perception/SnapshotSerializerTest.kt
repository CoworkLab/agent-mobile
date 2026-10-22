package com.phoneagent.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSerializerTest {

    private fun node(
        id: Int = 1,
        depth: Int = 0,
        className: String? = "android.widget.Button",
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        bounds: Bounds = Bounds(12, 340, 280, 400),
        clickable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        mark: Int? = null,
        checked: Boolean = false,
        checkable: Boolean = false,
    ) = UiNode(
        nodeId = id, parentId = null, depth = depth, className = className, text = text,
        contentDescription = desc, viewId = viewId, bounds = bounds, clickable = clickable,
        enabled = enabled, password = password, mark = mark, checked = checked, checkable = checkable,
    )

    private fun snapshot(nodes: List<UiNode>, pkg: String? = "com.example.app") = ScreenSnapshot(
        packageName = pkg,
        windowTitle = null,
        timestampMs = 0,
        nodes = nodes,
        screenHash = "abc",
    )

    @Test
    fun `shortens fully qualified class names`() {
        assertEquals("Button", SnapshotSerializer.shortClass("android.widget.Button"))
        assertEquals("RecyclerView", SnapshotSerializer.shortClass("androidx.recyclerview.widget.RecyclerView"))
        assertEquals("Node", SnapshotSerializer.shortClass(null))
        assertEquals("SurfaceView", SnapshotSerializer.shortClass("SurfaceView"))
    }

    @Test
    fun `renders a marked clickable button compactly`() {
        val text = SnapshotSerializer.toPromptText(
            snapshot(listOf(node(mark = 3, text = "Send", clickable = true))),
        )
        assertTrue(text, text.contains("[3] Button \"Send\""))
        assertTrue(text, text.contains("@12,340-280,400"))
        assertTrue(text, text.contains("tap"))
    }

    @Test
    fun `omits fields that are at their default`() {
        val text = SnapshotSerializer.toPromptText(snapshot(listOf(node(text = "Plain"))))
        assertFalse("no mark means no brackets", text.contains("["))
        assertFalse("no flag list when nothing is set", text.contains("tap"))
        assertFalse(text, text.contains("desc="))
    }

    @Test
    fun `includes content description and view id when present`() {
        val text = SnapshotSerializer.toPromptText(
            snapshot(listOf(node(desc = "Back", viewId = "com.app:id/back"))),
        )
        assertTrue(text, text.contains("desc=\"Back\""))
        assertTrue(text, text.contains("id=com.app:id/back"))
    }

    @Test
    fun `renders state flags in a stable order`() {
        val text = SnapshotSerializer.toPromptText(
            snapshot(
                listOf(
                    node(
                        clickable = true, checkable = true, checked = true,
                        enabled = false, password = true, mark = 1,
                    ),
                ),
            ),
        )
        assertTrue(text, text.contains("tap,checked,disabled,password"))
    }

    @Test
    fun `escapes quotes and newlines so one node stays on one line`() {
        val text = SnapshotSerializer.toPromptText(
            snapshot(listOf(node(text = "Say \"hi\"\nnow"))),
        )
        assertTrue(text, text.contains("\\\"hi\\\""))
        assertEquals("a node must not add lines", 2, text.lines().size)
    }

    @Test
    fun `indents nested nodes by depth`() {
        val text = SnapshotSerializer.toPromptText(
            snapshot(
                listOf(
                    node(id = 1, depth = 0, className = "android.widget.TextView", text = "root"),
                    node(id = 2, depth = 2, className = "android.widget.TextView", text = "child"),
                ),
            ),
        )
        val lines = text.lines()
        assertTrue(lines[1].startsWith("TextView \"root\""))
        assertTrue(lines[2].startsWith("    TextView \"child\""))
    }

    @Test
    fun `reports how many nodes were withheld by the cap`() {
        val many = (1..10).map { node(id = it, text = "row $it") }
        val text = SnapshotSerializer.toPromptText(snapshot(many), maxNodes = 4)
        assertTrue(text, text.contains("... 6 more nodes"))
    }

    @Test
    fun `surfaces a failure reason to the model`() {
        val withFailure = snapshot(emptyList()).copy(failure = SnapshotFailure.SECURE_SCREEN)
        val text = SnapshotSerializer.toPromptText(withFailure)
        assertTrue(text, text.contains("failure=SECURE_SCREEN"))
    }

    @Test
    fun `screen hash is stable for identical trees`() {
        val nodes = listOf(node(id = 1, text = "A"), node(id = 2, text = "B"))
        assertEquals(
            SnapshotSerializer.screenHash(nodes, "pkg", "win"),
            SnapshotSerializer.screenHash(nodes, "pkg", "win"),
        )
    }

    @Test
    fun `screen hash changes when text changes`() {
        val before = listOf(node(id = 1, text = "A"))
        val after = listOf(node(id = 1, text = "B"))
        assertNotEquals(
            SnapshotSerializer.screenHash(before, "pkg", "win"),
            SnapshotSerializer.screenHash(after, "pkg", "win"),
        )
    }

    @Test
    fun `screen hash changes when only a flag changes`() {
        val before = listOf(node(id = 1, text = "A", clickable = false))
        val after = listOf(node(id = 1, text = "A", clickable = true))
        assertNotEquals(
            SnapshotSerializer.screenHash(before, "pkg", "win"),
            SnapshotSerializer.screenHash(after, "pkg", "win"),
        )
    }

    @Test
    fun `screen hash changes when bounds move`() {
        val before = listOf(node(id = 1, text = "A", bounds = Bounds(0, 0, 10, 10)))
        val after = listOf(node(id = 1, text = "A", bounds = Bounds(0, 0, 10, 11)))
        assertNotEquals(
            SnapshotSerializer.screenHash(before, "pkg", "win"),
            SnapshotSerializer.screenHash(after, "pkg", "win"),
        )
    }

    @Test
    fun `screen hash changes with the package`() {
        val nodes = listOf(node(id = 1, text = "A"))
        assertNotEquals(
            SnapshotSerializer.screenHash(nodes, "com.a", "win"),
            SnapshotSerializer.screenHash(nodes, "com.b", "win"),
        )
    }

    @Test
    fun `screen hash ignores a timestamp-only change`() {
        val nodes = listOf(node(id = 1, text = "A"))
        val first = snapshot(nodes).copy(timestampMs = 1)
        val second = snapshot(nodes).copy(timestampMs = 999)
        assertEquals(
            SnapshotSerializer.screenHash(first.nodes, first.packageName, first.windowTitle),
            SnapshotSerializer.screenHash(second.nodes, second.packageName, second.windowTitle),
        )
    }

    @Test
    fun `image hash is null without a screenshot and stable with one`() {
        assertNull(SnapshotSerializer.imageHash(null))
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(
            SnapshotSerializer.imageHash(bytes),
            SnapshotSerializer.imageHash(bytes.copyOf()),
        )
        assertNotEquals(
            SnapshotSerializer.imageHash(bytes),
            SnapshotSerializer.imageHash(byteArrayOf(9, 9)),
        )
    }
}
