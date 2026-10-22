package com.phoneagent.perception

/**
 * A node in the accessibility tree, reduced to the fields the model actually needs.
 *
 * Pure data, no Android types, so the serializer and the tree shaping are unit testable.
 */
data class UiNode(
    val nodeId: Int,
    val parentId: Int?,
    val depth: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val mark: Int? = null,
)

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom
}

/** Why a screen could not be described. Each maps to a message the user can understand. */
enum class SnapshotFailure {
    /** FLAG_SECURE or a similar block; the platform refuses capture. */
    SECURE_SCREEN,

    /** Capture returned a black or uniform image. */
    BLANK_CAPTURE,

    /** Rate limited by the platform; retry after the interval. */
    RATE_LIMITED,

    /** No window to read, e.g. during a transition. */
    NO_WINDOW,

    /** Anything else. */
    UNKNOWN,
}

/**
 * Everything known about the screen at one moment.
 *
 * [nodes] is empty when only a screenshot is available (custom-drawn UI), and [screenshot]
 * is null in `TREE_ONLY` mode. Both can be present in `TREE_PLUS_SCREENSHOT`.
 */
data class ScreenSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val timestampMs: Long,
    val nodes: List<UiNode>,
    val screenHash: String,
    val screenshotJpeg: ByteArray? = null,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val mode: PerceptionMode = PerceptionMode.TREE_ONLY,
    val failure: SnapshotFailure? = null,
) {
    val hasTree: Boolean get() = nodes.isNotEmpty()
    val hasScreenshot: Boolean get() = screenshotJpeg != null
    val isUsable: Boolean get() = failure == null && (hasTree || hasScreenshot)

    /** Marks that actually exist on this screen, in order. */
    val marks: List<Int> get() = nodes.mapNotNull { it.mark }

    fun nodeForMark(mark: Int): UiNode? = nodes.firstOrNull { it.mark == mark }

    // ByteArray makes the generated equals/hashCode identity-based; define them properly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenSnapshot) return false
        return packageName == other.packageName &&
            windowTitle == other.windowTitle &&
            timestampMs == other.timestampMs &&
            nodes == other.nodes &&
            screenHash == other.screenHash &&
            mode == other.mode &&
            failure == other.failure &&
            screenshotWidth == other.screenshotWidth &&
            screenshotHeight == other.screenshotHeight &&
            (screenshotJpeg?.contentEquals(other.screenshotJpeg ?: ByteArray(0))
                ?: (other.screenshotJpeg == null))
    }

    override fun hashCode(): Int {
        var result = packageName?.hashCode() ?: 0
        result = 31 * result + (windowTitle?.hashCode() ?: 0)
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + nodes.hashCode()
        result = 31 * result + screenHash.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + (failure?.hashCode() ?: 0)
        result = 31 * result + (screenshotJpeg?.contentHashCode() ?: 0)
        return result
    }
}

enum class PerceptionMode {
    /** Accessibility tree only. Cheapest; preferred when the tree is rich. */
    TREE_ONLY,

    /** Tree plus a marked screenshot. The default for custom-drawn UI. */
    TREE_PLUS_SCREENSHOT,

    /** Screenshot only, no usable tree. */
    SCREENSHOT_ONLY,
}
