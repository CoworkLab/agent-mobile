package com.phoneagent.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.service.PhoneAgentAccessibilityService
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The real hands, over `AccessibilityService`.
 *
 * Two platform details drive the design:
 *  - `dispatchGesture` is asynchronous and its callback may never fire if the service dies,
 *    so every gesture is awaited as a cancellable coroutine. A gesture that is cancelled
 *    when STOP arrives is the correct behaviour, not a failure.
 *  - Text is set with `ACTION_SET_TEXT` rather than simulated key events, because key events
 *    cannot produce Devanagari or any non-Latin script.
 */
class AndroidActuator(
    private val logger: Logger,
    private val serviceProvider: () -> AccessibilityService? = {
        PhoneAgentAccessibilityService.get()
    },
) : Actuator {

    override suspend fun tap(x: Int, y: Int): Boolean = gesture { path ->
        path.moveTo(x.toFloat(), y.toFloat())
    }

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean = gesture(
        durationMs = durationMs,
    ) { path -> path.moveTo(x.toFloat(), y.toFloat()) }

    /**
     * A double tap is two short taps in one gesture description: dispatching two separate
     * gestures is too slow and the system treats them as unrelated single taps.
     */
    override suspend fun doubleTap(x: Int, y: Int): Boolean = gesture(strokes = {
        val first = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val second = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        listOf(
            GestureDescription.StrokeDescription(first, 0, 50),
            GestureDescription.StrokeDescription(second, 70, 50),
        )
    })

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = gesture(durationMs = durationMs) { path ->
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        path.lineTo(toX.toFloat(), toY.toFloat())
    }

    override suspend fun drag(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = gesture(durationMs = durationMs) { path ->
        path.moveTo(fromX.toFloat(), fromY.toFloat())
        path.lineTo(toX.toFloat(), toY.toFloat())
    }

    /**
     * A pinch is two strokes moving in opposite directions at the same time. Both must be in
     * one `GestureDescription`, otherwise they are two single-finger drags.
     */
    override suspend fun pinch(centerX: Int, centerY: Int, out: Boolean): Boolean {
        val span = PINCH_SPAN_PX
        val nearY = centerY
        val farY = centerY + span

        val (firstStartY, firstEndY) = if (out) nearY to centerY - span else centerY - span to nearY
        val (secondStartY, secondEndY) = if (out) farY to centerY + 2 * span else centerY + 2 * span to farY

        return gesture(strokes = {
            listOf(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(centerX.toFloat(), firstStartY.toFloat())
                        lineTo(centerX.toFloat(), firstEndY.toFloat())
                    },
                    0,
                    300,
                ),
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(centerX.toFloat(), secondStartY.toFloat())
                        lineTo(centerX.toFloat(), secondEndY.toFloat())
                    },
                    0,
                    300,
                ),
            )
        })
    }

    override suspend fun setText(nodeId: Int, text: String, clearFirst: Boolean): Boolean {
        val service = serviceProvider() ?: return false
        val node = findNode(service, nodeId) ?: run {
            logger.log(LogLevel.WARN, TAG, "setText: node $nodeId is gone")
            return false
        }

        return withContext(Dispatchers.Main) {
            if (clearFirst) {
                val cleared = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "",
                    )
                }
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, cleared) }
            }

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)

            if (!ok) {
                // Some editors refuse ACTION_SET_TEXT but accept a paste. Falling back to
                // clipboard keeps non-Latin text working where key events cannot.
                logger.log(LogLevel.DEBUG, TAG, "ACTION_SET_TEXT refused; trying paste")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } else {
                true
            }
        }
    }

    override suspend fun pressEnter(nodeId: Int?): Boolean {
        val service = serviceProvider() ?: return false
        val node = nodeId?.let { findNode(service, it) }

        return withContext(Dispatchers.Main) {
            // Prefer the node's own IME action: on a search box that is what submits it.
            // ACTION_IME_ENTER is a named action rather than a plain int, so it is matched
            // by identity against the node's action list before being performed.
            if (node != null) {
                val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                val hasImeEnter = node.actionList.any { it == imeEnter }
                if (hasImeEnter && node.performAction(imeEnter.id)) {
                    return@withContext true
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@withContext true
                }
            }
            // Last resort: the root node usually handles ENTER.
            service.rootInActiveWindow
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ?: false
        }
    }

    override suspend fun clearText(nodeId: Int): Boolean =
        setText(nodeId, text = "", clearFirst = true)

    override suspend fun globalAction(action: GlobalAction): Boolean {
        val service = serviceProvider() ?: return false
        val platformAction = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        }
        return withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(platformAction) }.getOrDefault(false)
        }
    }

    override suspend fun scroll(nodeId: Int?, direction: ScrollDirection): Boolean {
        val service = serviceProvider() ?: return false

        // A marked node scrolls that list; without a mark, the whole screen is swiped.
        if (nodeId != null) {
            val node = findNode(service, nodeId)
            if (node != null) {
                val action = when (direction) {
                    ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val ok = withContext(Dispatchers.Main) {
                    runCatching { node.performAction(action) }.getOrDefault(false)
                }
                if (ok) return true
                // Fall through to a gesture: some containers advertise scrolling but do not
                // implement the action, and a swipe is what the user would do anyway.
            }
        }

        val bounds = com.phoneagent.perception.NodeFlattener.screenBounds(service)
        val centerX = bounds.width / 2
        val centerY = bounds.height / 2
        val distance = (bounds.height * 0.4).toInt()

        return when (direction) {
            ScrollDirection.DOWN -> swipe(centerX, centerY + distance / 2, centerX, centerY - distance / 2, 300)
            ScrollDirection.UP -> swipe(centerX, centerY - distance / 2, centerX, centerY + distance / 2, 300)
            ScrollDirection.RIGHT -> swipe(centerX - distance / 2, centerY, centerX + distance / 2, centerY, 300)
            ScrollDirection.LEFT -> swipe(centerX + distance / 2, centerY, centerX - distance / 2, centerY, 300)
        }
    }

    /**
     * Finds a node by the id perception assigned it.
     *
     * Ids are only valid for one snapshot, so a node that has since left the screen simply
     * will not be found; the caller reports that rather than acting on the wrong element.
     */
    private fun findNode(service: AccessibilityService, nodeId: Int): AccessibilityNodeInfo? {
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.windows.forEach { window ->
                    runCatching { window.root }.getOrNull()?.let { add(it) }
                }
            }
        }
        if (roots.isEmpty()) return null

        var counter = 0
        val stack = ArrayDeque(roots.map { it to 0 })
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (depth > com.phoneagent.perception.TreeShaper.MAX_DEPTH) continue
            val currentId = counter++
            if (currentId == nodeId) return node
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                stack.addLast(child to depth + 1)
            }
        }
        return null
    }

    private suspend fun gesture(
        durationMs: Long = 60L,
        pathBuilder: (Path) -> Unit,
    ): Boolean = gesture(strokes = {
        val path = Path()
        pathBuilder(path)
        listOf(GestureDescription.StrokeDescription(path, 0, durationMs))
    })

    private suspend fun gesture(
        strokes: () -> List<GestureDescription.StrokeDescription>,
    ): Boolean {
        val service = serviceProvider() ?: return false

        val description = withContext(Dispatchers.Main) {
            runCatching {
                GestureDescription.Builder().apply {
                    strokes().forEach { addStroke(it) }
                }.build()
            }.getOrNull()
        } ?: return false

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gesture: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                val accepted = runCatching {
                    service.dispatchGesture(description, callback, null)
                }.getOrDefault(false)

                if (!accepted && continuation.isActive) {
                    continuation.resume(false)
                }

                continuation.invokeOnCancellation {
                    logger.log(LogLevel.DEBUG, TAG, "Gesture cancelled")
                }
            }
        }
    }

    private companion object {
        const val TAG = "Actuator"
        const val PINCH_SPAN_PX = 200
    }
}
