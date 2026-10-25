package com.mobileagent.app.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.mobileagent.app.AgentApp
import com.mobileagent.app.background.AgentConversationService
import com.mobileagent.app.automation.accessibility.AgentAccessibilityService
import org.json.JSONObject

/** Raised by tools when they cannot run; converted into a structured tool error response. */
class ToolException(message: String) : Exception(message)

fun Rect.shortString(): String = "[${left},${top} ${right}x${bottom}]"

fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

/**
 * Everything a tool needs at execution time. Created per dispatch by the conversation service.
 */
class ToolContext(
    val app: AgentApp,
    val service: AgentConversationService,
) {
    val context: Context get() = app.applicationContext

    val accessibility: AgentAccessibilityService? get() = AgentAccessibilityService.instance

    /** Accessibility service or a structured failure explaining how to fix it. */
    fun requireAccessibility(): AgentAccessibilityService =
        accessibility ?: throw ToolException(
            "Accessibility service is not enabled. Enable 'Mobile Agent' in Settings > Accessibility, " +
                "or call ask_user so the user can grant it."
        )

    val root: AccessibilityNodeInfo?
        get() = accessibility?.getActiveRootNode()

    fun requireRoot(): AccessibilityNodeInfo =
        root ?: throw ToolException(
            "No accessibility window is available right now. The screen may be locked or the app is still starting; wait and retry."
        )

    fun screenBounds(): Rect {
        val r = root?.boundsInScreen()
        if (r != null && r.width() > 0 && r.height() > 0) return r
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    fun clipboardManager(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun setClipboardText(text: String) {
        clipboardManager().setPrimaryClip(ClipData.newPlainText("text", text))
    }

    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun permissionError(toolName: String, permission: String): Nothing = throw ToolException(
        "$toolName requires the '$permission' permission which is not granted. " +
            "Call ask_user so the user can grant it in the app's System Permissions screen, then retry."
    )

    fun ok(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("success", true)
        for ((k, v) in pairs) if (v != null) put(k, v)
    }

    fun fail(message: String, extra: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("success", false)
        put("message", message)
        extra()
    }

    /** Overload for pre-built extra payloads (e.g. partial batch results). */
    fun fail(message: String, extra: JSONObject): JSONObject = JSONObject().apply {
        put("success", false)
        put("message", message)
        for (k in extra.keys()) put(k, extra.opt(k))
    }
}

/** Shared helpers for walking and matching the accessibility tree. */
object NodeQuery {

    data class Hit(val node: AccessibilityNodeInfo, val ordinal: Int)

    fun walk(root: AccessibilityNodeInfo, maxNodes: Int = 4000): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(maxNodes)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val n = queue.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (c.isVisibleToUser) queue.add(c)
            }
        }
        return out
    }

    fun editable(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || "EditText".equals(n.className?.toString()?.substringAfterLast('.'), true)

    fun interactive(n: AccessibilityNodeInfo): Boolean =
        n.isClickable || n.isLongClickable || n.isScrollable || editable(n) || n.isCheckable

    fun matches(n: AccessibilityNodeInfo, query: String, by: String = "any", exact: Boolean = false): Boolean {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return false
        fun eq(v: String?) = v?.lowercase()?.let { if (exact) it == q else it.contains(q) } == true
        return when (by) {
            "text" -> eq(n.text?.toString())
            "desc" -> eq(n.contentDescription?.toString())
            "id" -> eq(n.viewIdResourceName?.substringAfterLast('/')) || eq(n.viewIdResourceName)
            "class" -> eq(n.className?.toString())
            else -> eq(n.text?.toString()) || eq(n.contentDescription?.toString()) ||
                eq(n.viewIdResourceName?.substringAfterLast('/'))
        }
    }

    fun find(root: AccessibilityNodeInfo, query: String, by: String = "any", exact: Boolean = false): List<Hit> =
        walk(root).mapIndexed { i, n -> Hit(n, i) }.filter { matches(it.node, query, by, exact) }

    /** Resolve a node either by ordinal id ("n12" from get_ui_tree) or by a live text/desc query. */
    fun resolve(root: AccessibilityNodeInfo, nodeId: String?, query: String?): AccessibilityNodeInfo {
        if (!nodeId.isNullOrBlank()) {
            val idx = nodeId.removePrefix("n").toIntOrNull()
            if (idx != null && idx >= 0) {
                val list = walk(root)
                if (idx < list.size) return list[idx]
            }
            throw ToolException("node_id '$nodeId' is stale. Re-read the screen with get_ui_tree or use a query instead.")
        }
        if (!query.isNullOrBlank()) {
            find(root, query).firstOrNull()?.let { return it.node }
            throw ToolException(
                "No element matching '$query' on the current screen. Use get_ui_tree to inspect what is available, " +
                    "or scroll_until_found if it may be below the fold."
            )
        }
        throw ToolException("Provide either node_id or query.")
    }

    fun nodeJson(n: AccessibilityNodeInfo, ordinal: Int, compact: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("id", "n$ordinal")
        if (compact) {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { o.put("text", it.take(120)) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { o.put("desc", it.take(120)) }
            n.viewIdResourceName?.let { o.put("id_res", it.substringAfterLast('/')) }
            n.className?.toString()?.substringAfterLast('.')?.let { o.put("cls", it) }
            o.put("b", n.boundsInScreen().shortString())
            if (n.isClickable) o.put("click", true)
            if (n.isLongClickable) o.put("long", true)
            if (n.isScrollable) o.put("scroll", true)
            if (editable(n)) o.put("edit", true)
            if (n.isCheckable) o.put("checked", n.isChecked)
            if (n.isSelected) o.put("sel", true)
        } else {
            o.put("text", n.text?.toString() ?: "")
            o.put("desc", n.contentDescription?.toString() ?: "")
            o.put("cls", n.className?.toString() ?: "")
            o.put("bounds", n.boundsInScreen().shortString())
            o.put("clickable", n.isClickable)
            o.put("scrollable", n.isScrollable)
            o.put("editable", editable(n))
            o.put("checked", n.isChecked)
            o.put("focused", n.isFocused)
        }
        return o
    }

    fun center(n: AccessibilityNodeInfo): android.graphics.Point {
        val r = n.boundsInScreen()
        return android.graphics.Point(r.centerX(), r.centerY())
    }

    /** One-line human-readable description of a node for tool result messages. */
    fun nodeSummary(n: AccessibilityNodeInfo): String {
        val text = n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
        val desc = n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
        val cls = n.className?.toString()?.substringAfterLast('.')
        return listOfNotNull(
            text?.let { "text=\"$it\"" },
            desc?.let { "desc=\"$it\"" },
            cls?.let { "class=$it" }
        ).joinToString(" ").ifBlank { "node#${System.identityHashCode(n)}" }
    }
}
