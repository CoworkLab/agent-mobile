package com.phoneagent.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeShaperTest {

    private val screen = Bounds(0, 0, 1080, 2400)

    private fun node(
        id: Int,
        parent: Int? = null,
        depth: Int = 1,
        className: String? = "android.widget.Button",
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        bounds: Bounds = Bounds(0, 0, 200, 100),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
    ) = UiNode(
        nodeId = id, parentId = parent, depth = depth, className = className,
        text = text, contentDescription = desc, viewId = viewId, bounds = bounds,
        clickable = clickable, longClickable = longClickable, scrollable = scrollable,
        editable = editable, checkable = checkable, checked = checked,
    )

    @Test
    fun `drops zero-size nodes`() {
        val result = TreeShaper.shape(listOf(node(1, bounds = Bounds(10, 10, 10, 10))), screen)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `drops tiny decorative nodes but keeps tiny actionable ones`() {
        val decoration = node(1, bounds = Bounds(0, 0, 5, 5))
        val tinyButton = node(2, bounds = Bounds(0, 0, 5, 5), clickable = true)
        val result = TreeShaper.shape(listOf(decoration, tinyButton), screen)
        assertEquals(listOf(2), result.map { it.nodeId })
    }

    @Test
    fun `drops nodes entirely off screen`() {
        val offRight = node(1, bounds = Bounds(1200, 0, 1400, 100), text = "x")
        val offBottom = node(2, bounds = Bounds(0, 2500, 200, 2600), text = "y")
        val result = TreeShaper.shape(listOf(offRight, offBottom), screen)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `keeps a node just outside the screen edge because lists preload children`() {
        val justBelow = node(1, bounds = Bounds(0, 2405, 200, 2500), text = "next row")
        val result = TreeShaper.shape(listOf(justBelow), screen)
        assertEquals(1, result.size)
    }

    @Test
    fun `drops nodes with nothing to say and nothing to do`() {
        val silent = node(1, className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 300, 300))
        val result = TreeShaper.shape(listOf(silent), screen)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `keeps a plain text node`() {
        val label = node(1, className = "android.widget.TextView", text = "Settings")
        val result = TreeShaper.shape(listOf(label), screen)
        assertEquals(1, result.size)
    }

    @Test
    fun `drops nodes deeper than the depth cap`() {
        val deep = node(1, depth = TreeShaper.MAX_DEPTH + 1, text = "buried")
        assertTrue(TreeShaper.shape(listOf(deep), screen).isEmpty())
    }

    @Test
    fun `caps the number of nodes returned`() {
        val many = (1..50).map { node(it, text = "row $it") }
        val result = TreeShaper.shape(many, screen, maxNodes = 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `clears clickable on an ancestor when its descendant is clickable`() {
        val outer = node(1, clickable = true, bounds = Bounds(0, 0, 400, 200))
        val inner = node(2, parent = 1, depth = 2, text = "Send", clickable = true, bounds = Bounds(0, 0, 400, 200))
        val result = TreeShaper.shape(listOf(outer, inner), screen)
        val outerAfter = result.first { it.nodeId == 1 }
        val innerAfter = result.first { it.nodeId == 2 }
        assertFalse("ancestor should lose its click flag", outerAfter.clickable)
        assertTrue("the labelled child keeps it", innerAfter.clickable)
    }

    @Test
    fun `keeps clickable on an ancestor with no clickable descendant`() {
        val card = node(1, clickable = true, bounds = Bounds(0, 0, 400, 200))
        val label = node(2, parent = 1, depth = 2, text = "Item", className = "android.widget.TextView")
        val result = TreeShaper.shape(listOf(card, label), screen)
        assertTrue(result.first { it.nodeId == 1 }.clickable)
    }

    @Test
    fun `assigns marks in reading order to actionable nodes only`() {
        val topLeft = node(1, clickable = true, bounds = Bounds(0, 100, 100, 200))
        val topRight = node(2, clickable = true, bounds = Bounds(500, 100, 600, 200))
        val below = node(3, clickable = true, bounds = Bounds(0, 500, 100, 600))
        val label = node(4, text = "just a label", bounds = Bounds(0, 800, 100, 900))

        val marked = TreeShaper.assignMarks(listOf(topLeft, topRight, below, label))
        assertEquals(1, marked.first { it.nodeId == 1 }.mark)
        assertEquals(2, marked.first { it.nodeId == 2 }.mark)
        assertEquals(3, marked.first { it.nodeId == 3 }.mark)
        assertNull("a plain label gets no mark", marked.first { it.nodeId == 4 }.mark)
    }

    @Test
    fun `marks a scrollable container and an editable field`() {
        val list = node(1, scrollable = true, bounds = Bounds(0, 0, 1080, 2000), className = "androidx.recyclerview.widget.RecyclerView")
        val input = node(2, editable = true, bounds = Bounds(0, 2100, 1080, 2200), className = "android.widget.EditText")
        val marked = TreeShaper.assignMarks(listOf(list, input))
        assertEquals(1, marked.first { it.nodeId == 1 }.mark)
        assertEquals(2, marked.first { it.nodeId == 2 }.mark)
    }

    @Test
    fun `a screen with no actionable nodes needs a screenshot`() {
        val customDrawn = node(1, className = "com.game.SurfaceView", bounds = Bounds(0, 0, 1080, 2400))
        assertTrue(TreeShaper.needsScreenshot(listOf(customDrawn), screen))
    }

    @Test
    fun `a rich tree does not need a screenshot`() {
        val nodes = (1..10).map {
            node(it, clickable = true, text = "Button $it", bounds = Bounds(0, it * 100, 300, it * 100 + 80))
        }
        assertFalse(TreeShaper.needsScreenshot(nodes, screen))
    }

    @Test
    fun `a full-screen unlabelled surface needs a screenshot`() {
        val canvas = node(1, className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400))
        val oneTinyLabel = node(2, text = "Hi", bounds = Bounds(10, 10, 60, 40))
        assertTrue(TreeShaper.needsScreenshot(listOf(canvas, oneTinyLabel), screen))
    }

    @Test
    fun `a long article with one button is fine without a screenshot`() {
        val article = (1..40).map { node(it, className = "android.widget.TextView", text = "line $it") }
        val shareButton = node(100, clickable = true, text = "Share")
        assertFalse(TreeShaper.needsScreenshot(article + shareButton, screen))
    }
}
