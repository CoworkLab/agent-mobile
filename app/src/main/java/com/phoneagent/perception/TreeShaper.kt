package com.phoneagent.perception

/**
 * Turns a raw, noisy accessibility tree into the compact node list the model sees, and
 * assigns set-of-marks numbers to the nodes worth touching.
 *
 * Pure logic: the Android side flattens `AccessibilityNodeInfo` into [UiNode] values and
 * this decides what survives. Every rule here is unit tested.
 */
object TreeShaper {

    /** Nodes deeper than this are dropped; nothing actionable lives that deep. */
    const val MAX_DEPTH = 20

    /** Hard cap on nodes in a snapshot, to bound prompt size. */
    const val MAX_NODES = 400

    /** Below this area a node is treated as decoration and dropped. */
    const val MIN_AREA_PX = 12 * 12

    /**
     * Marks are only assigned to nodes that can actually be acted on, and only to leaves:
     * a clickable container whose child is also clickable is redundant, and marking both
     * confuses the model (two numbers for one button).
     */
    fun shape(
        raw: List<UiNode>,
        screenBounds: Bounds,
        maxNodes: Int = MAX_NODES,
    ): List<UiNode> {
        val visible = raw.filter { keep(it, screenBounds) }
        val trimmed = dropRedundantClickableAncestors(visible)
        return trimmed.take(maxNodes)
    }

    private fun keep(node: UiNode, screenBounds: Bounds): Boolean {
        if (node.depth > MAX_DEPTH) return false
        if (node.bounds.isEmpty) return false
        if (node.bounds.area < MIN_AREA_PX && !isActionable(node)) return false

        // Off-screen nodes: allow a small margin because lists keep children just outside.
        val margin = 8
        val offScreen = node.bounds.right < -margin ||
            node.bounds.bottom < -margin ||
            node.bounds.left > screenBounds.right + margin ||
            node.bounds.top > screenBounds.bottom + margin
        if (offScreen) return false

        // Nothing to say about it and it cannot be acted on.
        val hasText = !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.viewId.isNullOrBlank()
        if (!hasText && !isActionable(node) && !node.editable) return false

        return true
    }

    private fun isActionable(node: UiNode): Boolean =
        node.clickable || node.longClickable || node.scrollable || node.checkable

    /**
     * When both a parent and its only child are clickable, they almost always refer to the
     * same control. Keep the inner one (it has the label) and clear the outer flag.
     */
    private fun dropRedundantClickableAncestors(nodes: List<UiNode>): List<UiNode> {
        val byId = nodes.associateBy { it.nodeId }
        val hasClickableDescendant = HashSet<Int>()

        // Only a *clickable* descendant makes an ancestor redundant. Walking up from every
        // node would wrongly strip the flag from a clickable row that has a plain label child.
        nodes.filter { it.clickable }.forEach { node ->
            var parent = node.parentId?.let { byId[it] }
            while (parent != null) {
                hasClickableDescendant.add(parent.nodeId)
                parent = parent.parentId?.let { byId[it] }
            }
        }

        return nodes.map { node ->
            if (node.clickable && node.nodeId in hasClickableDescendant) {
                node.copy(clickable = false)
            } else {
                node
            }
        }
    }

    /**
     * Assigns marks 1..n to actionable nodes in reading order (top to bottom, then left to
     * right), which is how a person scans a screen. Non-actionable nodes keep `mark = null`.
     */
    fun assignMarks(nodes: List<UiNode>): List<UiNode> {
        val actionable = nodes
            .filter { it.clickable || it.longClickable || it.editable || it.scrollable || it.checkable }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        val marks = actionable.withIndex().associate { (index, node) -> node.nodeId to index + 1 }
        return nodes.map { node -> node.copy(mark = marks[node.nodeId]) }
    }

    /**
     * True when the tree is too poor to work from and a screenshot is needed: custom-drawn
     * UI, games, Flutter and editor timelines all look like this.
     *
     * The rule is about *actionable* content, not total nodes: a screen with a thousand
     * text nodes and one button is just a long article, and is fine in `TREE_ONLY`.
     */
    fun needsScreenshot(nodes: List<UiNode>, screenBounds: Bounds): Boolean {
        val actionable = nodes.count {
            it.clickable || it.longClickable || it.editable || it.checkable || it.scrollable
        }
        if (actionable == 0) return true

        // A single node covering most of the screen with no labelled children is the
        // signature of a custom-drawn surface.
        val screenArea = screenBounds.area.coerceAtLeast(1)
        val dominates = nodes.any { it.bounds.area > screenArea * 8 / 10 && it.text.isNullOrBlank() }
        val labelled = nodes.count { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
        return dominates && labelled <= 2
    }
}
