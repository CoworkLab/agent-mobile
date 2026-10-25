package com.mobileagent.app.tools

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Node-level actions through the accessibility tree. More reliable than raw coordinates
 * because they target semantic UI elements and fall back to gestures only when needed.
 */
object NodeTools {

    val NAMES: Set<String> = setOf(
        "click_node", "long_click_node", "set_text", "type_text", "focus_node",
        "select_text", "text_action", "scroll_node", "node_action", "press_enter"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "click_node",
            "Click a node. Pass node_id (from get_ui_tree) OR query (text/desc, resolved live). " +
                "Prefer query inside batch_actions because node ids go stale after screen changes.",
            ToolRegistry.p("node_id", "STRING", "id from get_ui_tree, e.g. n12"),
            ToolRegistry.p("query", "STRING", "text/desc/id to find and click")
        ),
        ToolRegistry.t(
            "long_click_node", "Long click a node (context menus, item options). Pass node_id OR query.",
            ToolRegistry.p("node_id", "STRING", "id from get_ui_tree"),
            ToolRegistry.p("query", "STRING", "text/desc/id to find"),
            ToolRegistry.p("duration_ms", "INTEGER", "default 800")
        ),
        ToolRegistry.t(
            "set_text", "Set text in an input field (fastest, most reliable typing). Pass node_id OR query. Focuses the field if needed.",
            ToolRegistry.p("text", "STRING", "Text to set", req = true),
            ToolRegistry.p("node_id", "STRING", "editable node id"),
            ToolRegistry.p("query", "STRING", "text/hint/id of the field"),
            ToolRegistry.p("append", "BOOLEAN", "append instead of replace, default false")
        ),
        ToolRegistry.t(
            "type_text", "Type text into the currently focused field (use when no node handle is available).",
            ToolRegistry.p("text", "STRING", "Text to type", req = true),
            ToolRegistry.p("append", "BOOLEAN", "append instead of replace, default true")
        ),
        ToolRegistry.t("focus_node", "Give input focus to a node.", ToolRegistry.p("node_id", "STRING", "node id", req = true)),
        ToolRegistry.t(
            "select_text", "Select a text range inside a node.",
            ToolRegistry.p("node_id", "STRING", "node id", req = true),
            ToolRegistry.p("start", "INTEGER", "selection start (default 0)"),
            ToolRegistry.p("end", "INTEGER", "selection end (default full text)")
        ),
        ToolRegistry.t(
            "text_action", "Copy, cut, paste or select-all on a node.",
            ToolRegistry.p("node_id", "STRING", "node id", req = true),
            ToolRegistry.p("action", "STRING", "copy | cut | paste | select_all", req = true,
                enum = listOf("copy", "cut", "paste", "select_all"))
        ),
        ToolRegistry.t(
            "scroll_node", "Scroll a specific scrollable node (best way to scroll lists).",
            ToolRegistry.p("node_id", "STRING", "scrollable node id", req = true),
            ToolRegistry.p("direction", "STRING", "forward | backward | up | down | left | right", req = true,
                enum = listOf("forward", "backward", "up", "down", "left", "right"))
        ),
        ToolRegistry.t(
            "node_action", "Other accessibility actions on a node.",
            ToolRegistry.p("node_id", "STRING", "node id", req = true),
            ToolRegistry.p("action", "STRING", "expand | collapse | dismiss | show_on_screen | clear_focus | clear_selection",
                req = true, enum = listOf("expand", "collapse", "dismiss", "show_on_screen", "clear_focus", "clear_selection"))
        ),
        ToolRegistry.t(
            "press_enter", "Press IME enter/search/send/go/done/next on the focused field.",
            ToolRegistry.p("ime_action", "STRING", "enter | search | send | go | done | next",
                enum = listOf("enter", "search", "send", "go", "done", "next"))
        )
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val svc = ctx.requireAccessibility()
        val root = ctx.requireRoot()
        return when (name) {
            "click_node" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), args.optString("query"))
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    clickAncestor(node)
                if (!clicked) {
                    // Fallback: physical tap at the node centre.
                    val c = NodeQuery.center(node)
                    GestureTools.run("tap", JSONObject().put("x", c.x).put("y", c.y), ctx)
                    return ctx.ok("clicked" to true, "via" to "gesture_fallback", "target" to NodeQuery.nodeSummary(node))
                }
                delay(250)
                ctx.ok("clicked" to true, "via" to "node_action", "target" to NodeQuery.nodeSummary(node))
            }
            "long_click_node" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), args.optString("query"))
                val dur = args.optInt("duration_ms", 800)
                val did = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if (did) return ctx.ok("long_clicked" to true, "via" to "node_action", "target" to NodeQuery.nodeSummary(node))
                val c = NodeQuery.center(node)
                GestureTools.run(
                    "long_press",
                    JSONObject().put("x", c.x).put("y", c.y).put("duration_ms", dur),
                    ctx
                )
                ctx.ok("long_clicked" to true, "via" to "gesture_fallback", "target" to NodeQuery.nodeSummary(node))
            }
            "set_text" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), args.optString("query"))
                setNodeText(node, args.getString("text"), args.optBoolean("append", false))
                ctx.ok("text_set" to true, "target" to NodeQuery.nodeSummary(node))
            }
            "type_text" -> {
                val focused = findFocusedEditable(root)
                    ?: throw ToolException(
                        "No focused input field. Tap the field first with click_node, then call type_text, or use set_text with a query."
                    )
                setNodeText(focused, args.getString("text"), args.optBoolean("append", true))
                ctx.ok("typed" to true, "target" to NodeQuery.nodeSummary(focused))
            }
            "focus_node" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), null)
                val did = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                    clickAncestor(node, wantFocus = true)
                ctx.ok("focused" to did)
            }
            "select_text" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), null)
                val len = node.text?.length ?: 0
                val start = args.optInt("start", 0).coerceIn(0, len)
                val end = args.optInt("end", len).coerceIn(0, len)
                val args2 = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, minOf(start, end))
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, maxOf(start, end))
                }
                val did = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args2)
                ctx.ok("selected" to did, "range" to "$start..$end")
            }
            "text_action" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), null)
                val action = args.getString("action")
                val const = when (action) {
                    "copy" -> AccessibilityNodeInfo.ACTION_COPY
                    "cut" -> AccessibilityNodeInfo.ACTION_CUT
                    "paste" -> AccessibilityNodeInfo.ACTION_PASTE
                    "select_all" -> {
                        val len = node.text?.length ?: 0
                        node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            android.os.Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                            }
                        )
                        return ctx.ok("select_all" to true)
                    }
                    else -> throw ToolException("Unknown text_action '$action'.")
                }
                val did = node.performAction(const)
                ctx.ok("action" to action, "performed" to did)
            }
            "scroll_node" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), args.optString("query"))
                val dir = args.optString("direction", "forward")
                val action = when (dir) {
                    "forward", "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "backward", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val did = node.performAction(action)
                delay(350)
                ctx.ok("scrolled" to did, "direction" to dir)
            }
            "node_action" -> {
                val node = NodeQuery.resolve(root, args.optString("node_id"), null)
                val action = args.getString("action")
                val did = when (action) {
                    "expand" -> node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                    "collapse" -> node.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)
                    "dismiss" -> node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                    "show_on_screen" -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    "clear_focus" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    "clear_selection" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)
                    else -> throw ToolException("Unknown node_action '$action'.")
                }
                ctx.ok("action" to action, "performed" to did)
            }
            "press_enter" -> {
                val focused = findFocusedEditable(root)
                if (focused != null && Build.VERSION.SDK_INT >= 30 &&
                    focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                ) {
                    return ctx.ok("pressed" to true, "via" to "ime_action")
                }
                // Fallback: find a visible send/search/go button and click it.
                val candidates = listOf("send", "search", "go", "done", "submit", "post", "next")
                for (word in candidates) {
                    val hit = NodeQuery.find(root, word, by = "any", exact = false)
                        .firstOrNull { it.node.isClickable }
                    if (hit != null) {
                        hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return ctx.ok("pressed" to true, "via" to "button", "button" to word)
                    }
                }
                ctx.fail("Could not press enter: no IME action available and no send/search button found on screen.")
            }
            else -> ctx.fail("Unknown node tool '$name'.")
        }
    }

    suspend fun setNodeText(node: AccessibilityNodeInfo, text: String, append: Boolean): Boolean {
        if (!NodeQuery.editable(node)) {
            // Try the closest editable child/descendant.
            val editableChild = firstEditableDescendant(node)
                ?: throw ToolException("Target node is not an editable field.")
            return setNodeText(editableChild, text, append)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(80)
        val finalText = if (append) (node.text?.toString() ?: "") + text else text
        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun firstEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (NodeQuery.editable(c)) return c
            firstEditableDescendant(c)?.let { return it }
        }
        return null
    }

    fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        NodeQuery.walk(root).firstOrNull { it.isFocused && NodeQuery.editable(it) }

    private fun clickAncestor(node: AccessibilityNodeInfo, wantFocus: Boolean = false): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (wantFocus) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
            } else if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }
}
