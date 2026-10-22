package com.phoneagent.tools

import com.phoneagent.perception.SnapshotSerializer

/**
 * Read-only tools. They never change the screen, so they need no approval and are always
 * safe to call. `get_screen` returns the compact text form, not the raw tree, to keep the
 * prompt small.
 */
class InfoTools(
    private val deviceInfo: DeviceInfo,
) {

    val all: List<Tool> = listOf(
        GetScreenTool(),
        FindElementTool(),
        GetCurrentAppTool(),
        ListAppsTool(deviceInfo),
        ReadNotificationsTool(deviceInfo),
        GetClipboardTool(deviceInfo),
    )

    private class GetScreenTool : Tool {
        override val name = "get_screen"
        override val description = "Re-read the current screen. Use it after something changed."

        override val parametersSchema = schemaOf(emptyMap())
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val snapshot = context.refreshScreen()
            return ToolResult.ok(
                message = SnapshotSerializer.toPromptText(snapshot),
                screenChanged = null,
                data = mapOf("screenHash" to snapshot.screenHash),
            )
        }
    }

    private class FindElementTool : Tool {
        override val name = "find_element"
        override val description =
            "Find elements whose text or description contains a phrase. " +
                "Use it when the screen is long and the target is not in the visible marks."

        override val parametersSchema = schemaOf(
            mapOf("text" to stringProperty("Text to look for, case-insensitive")),
            required = listOf("text"),
        )
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val needle = call.string("text")?.trim()
                ?: return ToolResult.fail("text is required.")
            if (needle.isEmpty()) return ToolResult.fail("text is required.")

            val matches = context.currentNodes().filter { node ->
                node.text?.contains(needle, ignoreCase = true) == true ||
                    node.contentDescription?.contains(needle, ignoreCase = true) == true ||
                    node.viewId?.contains(needle, ignoreCase = true) == true
            }

            if (matches.isEmpty()) {
                return ToolResult.ok("No element contains \"$needle\".")
            }

            val lines = matches.joinToString("\n") { node ->
                val mark = node.mark?.let { "[$it] " } ?: ""
                val label = node.text ?: node.contentDescription ?: node.viewId ?: "(unlabelled)"
                "$mark${SnapshotSerializer.shortClass(node.className)} \"$label\" at ${node.bounds}"
            }
            return ToolResult.ok(lines)
        }
    }

    private class GetCurrentAppTool : Tool {
        override val name = "get_current_app"
        override val description = "Which app is in the foreground."

        override val parametersSchema = schemaOf(emptyMap())
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val pkg = context.currentPackageName()
            return if (pkg == null) {
                ToolResult.ok("No app could be identified.")
            } else {
                ToolResult.ok(pkg)
            }
        }
    }

    private class ListAppsTool(private val deviceInfo: DeviceInfo) : Tool {
        override val name = "list_apps"
        override val description = "List the apps that can be opened, to find the right name."

        override val parametersSchema = schemaOf(
            mapOf("filter" to stringProperty("Optional text to filter app names")),
        )
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val filter = call.string("filter")
            val apps = deviceInfo.launchableApps()
                .filter { filter.isNullOrBlank() || it.second.contains(filter, ignoreCase = true) }
                .sortedBy { it.second }
            if (apps.isEmpty()) {
                return ToolResult.ok("No matching apps.")
            }
            return ToolResult.ok(apps.joinToString(", ") { it.second })
        }
    }

    private class ReadNotificationsTool(private val deviceInfo: DeviceInfo) : Tool {
        override val name = "read_notifications"
        override val description = "Read recent notifications, for codes and messages."

        override val parametersSchema = schemaOf(
            mapOf("limit" to intProperty("How many to read, default 10")),
        )
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val limit = (call.int("limit") ?: 10).coerceIn(1, 50)
            val notifications = deviceInfo.recentNotifications(limit)
            if (notifications.isEmpty()) {
                return ToolResult.ok("No notifications.")
            }
            return ToolResult.ok(
                notifications.joinToString("\n") { n -> "${n.packageName}: ${n.title} - ${n.text}" },
            )
        }
    }

    private class GetClipboardTool(private val deviceInfo: DeviceInfo) : Tool {
        override val name = "get_clipboard"
        override val description = "Read the clipboard, for text the user copied."

        override val parametersSchema = schemaOf(emptyMap())
        override val isReadOnly = true

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val text = deviceInfo.clipboardText()
            return ToolResult.ok(text ?: "The clipboard is empty.")
        }
    }
}

/** Device facts the read-only tools need. Implemented over `PackageManager` and the listener. */
interface DeviceInfo {
    suspend fun launchableApps(): List<Pair<String, String>>
    suspend fun recentNotifications(limit: Int): List<NotificationInfo>
    suspend fun clipboardText(): String?
}

data class NotificationInfo(val packageName: String, val title: String, val text: String)
