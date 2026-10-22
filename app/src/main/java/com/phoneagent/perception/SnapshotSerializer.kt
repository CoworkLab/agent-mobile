package com.phoneagent.perception

import java.security.MessageDigest

/**
 * Compact, token-efficient serialization of a [ScreenSnapshot] for the model prompt.
 *
 * Design rules, all of which are covered by tests:
 *  - short keys (`c` for class, `t` for text, ...), because prompt tokens cost money;
 *  - only non-default fields are emitted, so a plain text node stays one short line;
 *  - the screenshot is never inlined here; the caller attaches it separately.
 */
object SnapshotSerializer {

    /**
     * Renders the tree as one line per node, indented by depth.
     *
     * Example:
     * `[3] Button "Send" @12,340-280,400 tap`
     */
    fun toPromptText(snapshot: ScreenSnapshot, maxNodes: Int = 120): String {
        val sb = StringBuilder()
        sb.append("pkg=").append(snapshot.packageName ?: "?")
        snapshot.windowTitle?.let { sb.append(" win=").append(it) }
        sb.append(" mode=").append(snapshot.mode.name)
        sb.append('\n')
        snapshot.failure?.let { sb.append("failure=").append(it.name).append('\n') }

        snapshot.nodes.take(maxNodes).forEach { node ->
            sb.append(renderNode(node)).append('\n')
        }
        if (snapshot.nodes.size > maxNodes) {
            sb.append("... ").append(snapshot.nodes.size - maxNodes).append(" more nodes\n")
        }
        return sb.toString().trimEnd()
    }

    private fun renderNode(node: UiNode): String = buildString {
        append("  ".repeat(node.depth.coerceAtMost(8)))
        node.mark?.let { append('[').append(it).append("] ") }
        append(shortClass(node.className))
        node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(escape(it)).append('"') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let {
            append(" desc=\"").append(escape(it)).append('"')
        }
        node.viewId?.let { append(" id=").append(it) }
        append(" @").append(node.bounds.left).append(',').append(node.bounds.top)
            .append('-').append(node.bounds.right).append(',').append(node.bounds.bottom)

        val flags = buildList {
            if (node.clickable) add("tap")
            if (node.longClickable) add("hold")
            if (node.scrollable) add("scroll")
            if (node.editable) add("type")
            if (node.checkable) add(if (node.checked) "checked" else "unchecked")
            if (node.focused) add("focused")
            if (node.selected) add("selected")
            if (!node.enabled) add("disabled")
            if (node.password) add("password")
        }
        if (flags.isNotEmpty()) append(' ').append(flags.joinToString(","))
    }

    /** `android.widget.Button` -> `Button`; keeps custom classes readable. */
    fun shortClass(className: String?): String {
        val name = className ?: return "Node"
        val simple = name.substringAfterLast('.')
        return if (simple.isBlank()) name else simple
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /**
     * Stable hash of everything that affects what the agent sees. Used to detect "nothing
     * changed" between steps, which is the primary stuck signal.
     */
    fun screenHash(nodes: List<UiNode>, packageName: String?, windowTitle: String?): String {
        val canonical = buildString {
            append(packageName ?: "").append('|').append(windowTitle ?: "").append('|')
            nodes.forEach { node ->
                append(node.depth).append(',')
                append(node.className ?: "").append(',')
                append(node.text ?: "").append(',')
                append(node.contentDescription ?: "").append(',')
                append(node.viewId ?: "").append(',')
                append(node.bounds.left).append(':').append(node.bounds.top).append(':')
                    .append(node.bounds.right).append(':').append(node.bounds.bottom).append(',')
                append(
                    listOf(
                        node.clickable, node.longClickable, node.scrollable, node.editable,
                        node.checkable, node.checked, node.focused, node.selected,
                        node.enabled, node.password,
                    ).joinToString("") { if (it) "1" else "0" },
                )
                append(';')
            }
        }
        return sha256(canonical).take(16)
    }

    /** Perceptual-ish hash of the screenshot bytes; the input is already downscaled JPEG. */
    fun imageHash(jpeg: ByteArray?): String? =
        jpeg?.let { sha256(it).take(16) }

    private fun sha256(value: String): String =
        sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
