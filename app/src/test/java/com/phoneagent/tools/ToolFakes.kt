package com.phoneagent.tools

import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import kotlinx.coroutines.delay

/**
 * Test doubles that behave like the real thing rather than recording calls.
 *
 * `FakeActuator` keeps a small model of the screen so that a tool which changes the screen
 * actually changes what the next tool sees. Without that, a test of "type then tap Send"
 * would pass even if the second step ran against a stale screen.
 */
class FakeActuator : Actuator {
    val taps = mutableListOf<Pair<Int, Int>>()
    val longPresses = mutableListOf<Triple<Int, Int, Long>>()
    val doubleTaps = mutableListOf<Pair<Int, Int>>()
    val swipes = mutableListOf<List<Int>>()
    val drags = mutableListOf<List<Int>>()
    val pinches = mutableListOf<Triple<Int, Int, Boolean>>()
    val textSets = mutableListOf<Triple<Int, String, Boolean>>()
    val enters = mutableListOf<Int?>()
    val clears = mutableListOf<Int>()
    val globalActions = mutableListOf<GlobalAction>()
    val scrolls = mutableListOf<Pair<Int?, ScrollDirection>>()

    var acceptGestures = true
    var acceptText = true
    var acceptGlobal = true
    var acceptScroll = true

    /** Text typed per node, so a test can assert the field really holds it. */
    val fieldText = mutableMapOf<Int, String>()

    override suspend fun tap(x: Int, y: Int): Boolean {
        taps += x to y
        return acceptGestures
    }

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
        longPresses += Triple(x, y, durationMs)
        return acceptGestures
    }

    override suspend fun doubleTap(x: Int, y: Int): Boolean {
        doubleTaps += x to y
        return acceptGestures
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        swipes += listOf(fromX, fromY, toX, toY, durationMs.toInt())
        return acceptGestures
    }

    override suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        drags += listOf(fromX, fromY, toX, toY, durationMs.toInt())
        return acceptGestures
    }

    override suspend fun pinch(centerX: Int, centerY: Int, out: Boolean): Boolean {
        pinches += Triple(centerX, centerY, out)
        return acceptGestures
    }

    override suspend fun setText(nodeId: Int, text: String, clearFirst: Boolean): Boolean {
        textSets += Triple(nodeId, text, clearFirst)
        if (acceptText) fieldText[nodeId] = text
        return acceptText
    }

    override suspend fun pressEnter(nodeId: Int?): Boolean {
        enters += nodeId
        return acceptGestures
    }

    override suspend fun clearText(nodeId: Int): Boolean {
        clears += nodeId
        fieldText[nodeId] = ""
        return acceptText
    }

    override suspend fun globalAction(action: GlobalAction): Boolean {
        globalActions += action
        return acceptGlobal
    }

    override suspend fun scroll(nodeId: Int?, direction: ScrollDirection): Boolean {
        scrolls += nodeId to direction
        return acceptScroll
    }
}

/** A scripted screen that a tool can read and, where relevant, change. */
class FakeToolContext(
    initial: ScreenSnapshot,
    override val actuator: Actuator = FakeActuator(),
    private val screenSize: Pair<Int, Int> = 1080 to 2400,
) : ToolContext {

    var snapshot: ScreenSnapshot = initial
    var refreshCount = 0
    var onRefresh: (() -> ScreenSnapshot)? = null

    override fun currentNodes(): List<UiNode> = snapshot.nodes

    override fun currentPackageName(): String? = snapshot.packageName

    override suspend fun refreshScreen(): ScreenSnapshot {
        refreshCount++
        onRefresh?.let { snapshot = it() }
        return snapshot
    }

    fun screen(): Pair<Int, Int> = screenSize
}

fun testNode(
    id: Int,
    mark: Int? = null,
    text: String? = null,
    desc: String? = null,
    viewId: String? = null,
    className: String = "android.widget.Button",
    bounds: Bounds = Bounds(100, id * 120, 500, id * 120 + 100),
    clickable: Boolean = false,
    editable: Boolean = false,
    password: Boolean = false,
    scrollable: Boolean = false,
): UiNode = UiNode(
    nodeId = id,
    parentId = null,
    depth = 1,
    className = className,
    text = text,
    contentDescription = desc,
    viewId = viewId,
    bounds = bounds,
    clickable = clickable,
    editable = editable,
    password = password,
    scrollable = scrollable,
    mark = mark,
)

fun testSnapshot(
    nodes: List<UiNode>,
    pkg: String? = "com.example.app",
    mode: PerceptionMode = PerceptionMode.TREE_ONLY,
    hash: String = "screen-hash",
): ScreenSnapshot = ScreenSnapshot(
    packageName = pkg,
    windowTitle = null,
    timestampMs = 0,
    nodes = nodes,
    screenHash = hash,
    mode = mode,
)

/** An app launcher that matches against a fixed list and honours a denylist. */
class FakeAppLauncher(
    val apps: List<Pair<String, String>>,
    private val denied: List<String> = emptyList(),
) : AppLauncher {

    val opened = mutableListOf<String>()

    override suspend fun launch(name: String): AppLauncher.Result {
        return when (val match = AppNameMatcher.match(name, apps)) {
            is AppLauncher.Result.Launched -> {
                if (denied.contains(match.packageName)) {
                    AppLauncher.Result.Denied(match.packageName)
                } else {
                    opened += match.packageName
                    match
                }
            }
            else -> match
        }
    }
}

/** Device facts with fixed answers. */
class FakeDeviceInfo(
    private val apps: List<Pair<String, String>> = emptyList(),
    private val notifications: List<NotificationInfo> = emptyList(),
    private val clipboard: String? = null,
) : DeviceInfo {
    override suspend fun launchableApps(): List<Pair<String, String>> = apps
    override suspend fun recentNotifications(limit: Int): List<NotificationInfo> =
        notifications.take(limit)
    override suspend fun clipboardText(): String? = clipboard
}
