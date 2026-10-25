package com.mobileagent.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.mobileagent.app.notification.AgentNotificationListener
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen perception tools: read the UI tree, take screenshots, watch for changes,
 * read notifications, and use the clipboard.
 */
object PerceptionTools {

    val NAMES: Set<String> = setOf(
        "get_ui_tree", "find_element", "screenshot", "read_screen_text", "get_current_app",
        "wait_for_element", "wait_for_idle", "read_notifications", "notification_action",
        "get_clipboard", "set_clipboard"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "get_ui_tree",
            "Dump the current screen's accessibility tree (compact). Each node: id, text, desc, cls, bounds, " +
                "click/long/scroll/edit/checked flags. Always use this first to understand a screen.",
            ToolRegistry.p("only_interactive", "BOOLEAN", "only clickable/editable/scrollable nodes, default true"),
            ToolRegistry.p("max_nodes", "INTEGER", "max nodes returned, default 150")
        ),
        ToolRegistry.t(
            "find_element", "Find nodes on the current screen by text, content description, view id or class.",
            ToolRegistry.p("query", "STRING", "text/desc/id to search", req = true),
            ToolRegistry.p("by", "STRING", "text | desc | id | class | any, default any",
                enum = listOf("text", "desc", "id", "class", "any")),
            ToolRegistry.p("exact", "BOOLEAN", "exact match instead of contains, default false")
        ),
        ToolRegistry.t(
            "screenshot",
            "Capture the current screen (or a cropped region) and attach it visually for the next model turn. " +
                "Use when the UI tree is empty or unclear (games, WebView, images, canvas UIs). This also acts as " +
                "your OCR: read any text inside the image directly (signs, memes, scanned pages, video subtitles).",
            ToolRegistry.p("region", "OBJECT", "optional crop in px {left, top, right, bottom}")
        ),
        ToolRegistry.t(
            "read_screen_text", "Read all visible text on screen from the accessibility tree. Fast fallback when get_ui_tree is too long.",
            ToolRegistry.p("max_chars", "INTEGER", "default 4000")
        ),
        ToolRegistry.t("get_current_app", "Get the foreground app package name.", listOf<ToolRegistry.P>()),
        ToolRegistry.t(
            "wait_for_element", "Wait until an element appears on screen or timeout.",
            ToolRegistry.p("query", "STRING", "text/desc/id to wait for", req = true),
            ToolRegistry.p("timeout_ms", "INTEGER", "default 5000")
        ),
        ToolRegistry.t(
            "wait_for_idle", "Wait until the screen stops changing (after taps, navigation, loading animations).",
            ToolRegistry.p("timeout_ms", "INTEGER", "default 3000")
        ),
        ToolRegistry.t(
            "read_notifications", "Read active notifications captured by the agent's listener.",
            ToolRegistry.p("limit", "INTEGER", "default 20"),
            ToolRegistry.p("app", "STRING", "filter by package name")
        ),
        ToolRegistry.t(
            "notification_action", "Dismiss, open, reply to or trigger an action button on a notification.",
            ToolRegistry.p("notification_id", "STRING", "id from read_notifications", req = true),
            ToolRegistry.p("action", "STRING", "dismiss | open | reply | action_button", req = true,
                enum = listOf("dismiss", "open", "reply", "action_button")),
            ToolRegistry.p("reply_text", "STRING", "message text when action=reply"),
            ToolRegistry.p("button_label", "STRING", "action button label when action=action_button")
        ),
        ToolRegistry.t("get_clipboard", "Read clipboard text (subject to Android 10+ clipboard restrictions).", listOf<ToolRegistry.P>()),
        ToolRegistry.t("set_clipboard", "Copy text to the clipboard.", ToolRegistry.p("text", "STRING", req = true))
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        return when (name) {
            "get_ui_tree" -> {
                val root = ctx.requireRoot()
                val onlyInteractive = args.optBoolean("only_interactive", true)
                val max = args.optInt("max_nodes", 150).coerceIn(10, 400)
                val list = NodeQuery.walk(root)
                val arr = JSONArray()
                var interactiveCount = 0
                for ((i, n) in list.withIndex()) {
                    if (arr.length() >= max) break
                    if (onlyInteractive && !NodeQuery.interactive(n) && n.text.isNullOrBlank()) continue
                    arr.put(NodeQuery.nodeJson(n, i))
                    interactiveCount++
                }
                ctx.ok(
                    "package" to (root.packageName?.toString() ?: "unknown"),
                    "node_count" to interactiveCount,
                    "nodes" to arr
                )
            }
            "find_element" -> {
                val root = ctx.requireRoot()
                val hits = NodeQuery.find(
                    root, args.getString("query"),
                    args.optString("by", "any"), args.optBoolean("exact", false)
                ).take(25)
                val arr = JSONArray()
                hits.forEach { arr.put(NodeQuery.nodeJson(it.node, it.ordinal)) }
                ctx.ok("matches" to hits.size, "elements" to arr)
            }
            "screenshot" -> {
                val bytes = captureJpeg(ctx)
                    ?: throw ToolException(
                        "Screen capture unavailable: needs Screen Share granted or Android 11+ accessibility screenshot. " +
                            "Fall back to get_ui_tree."
                    )
                var bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val region = args.optJSONObject("region")
                if (region != null && bmp != null) {
                    val l = region.optInt("left", 0).coerceIn(0, bmp.width - 1)
                    val t = region.optInt("top", 0).coerceIn(0, bmp.height - 1)
                    val r = region.optInt("right", bmp.width).coerceIn(l + 1, bmp.width)
                    val b = region.optInt("bottom", bmp.height).coerceIn(t + 1, bmp.height)
                    val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                    if (cropped != bmp) bmp.recycle()
                    bmp = cropped
                }
                val out = ByteArrayOutputStream2()
                bmp?.compress(Bitmap.CompressFormat.JPEG, 72, out)
                bmp?.recycle()
                val sent = ctx.service.pushVisionFrame(out.toByteArray())
                ctx.ok(
                    "captured" to sent,
                    "note" to (if (sent) "Screenshot attached to this turn; look at the latest image." else "Capture failed; use get_ui_tree instead.")
                )
            }
            "read_screen_text" -> {
                val root = ctx.requireRoot()
                val max = args.optInt("max_chars", 4000).coerceIn(200, 20_000)
                val sb = StringBuilder()
                for (n in NodeQuery.walk(root)) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrBlank()) {
                        sb.appendLine(t)
                        if (sb.length > max) break
                    }
                }
                ctx.ok("text" to sb.toString().take(max), "package" to (root.packageName?.toString() ?: ""))
            }
            "get_current_app" -> {
                val root = ctx.root
                    ?: return ctx.ok("package" to "unknown", "note" to "No window available.")
                ctx.ok("package" to (root.packageName?.toString() ?: "unknown"))
            }
            "wait_for_element" -> {
                val query = args.getString("query")
                val timeout = args.optLong("timeout_ms", 5000L).coerceIn(500, 30_000)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeout) {
                    val root = ctx.root
                    if (root != null) {
                        NodeQuery.find(root, query).firstOrNull()?.let {
                            return ctx.ok("appeared" to true, "element" to NodeQuery.nodeJson(it.node, it.ordinal))
                        }
                    }
                    delay(250)
                }
                ctx.ok("appeared" to false, "message" to "Element '$query' did not appear within ${timeout}ms.")
            }
            "wait_for_idle" -> {
                val timeout = args.optLong("timeout_ms", 3000L).coerceIn(500, 15_000)
                val start = System.currentTimeMillis()
                var lastSignature = ""
                var stableSince = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeout) {
                    val sig = ctx.root?.let { r ->
                        NodeQuery.walk(r, maxNodes = 200).joinToString("|") { n ->
                            "${n.text}/${n.contentDescription}/${n.viewIdResourceName}"
                        }.hashCode().toString()
                    } ?: ""
                    if (sig != lastSignature) {
                        lastSignature = sig
                        stableSince = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - stableSince > 700) {
                        return ctx.ok("idle" to true, "stable_ms" to (System.currentTimeMillis() - stableSince))
                    }
                    delay(200)
                }
                ctx.ok("idle" to false, "message" to "Screen still changing after ${timeout}ms; proceeding anyway.")
            }
            "read_notifications" -> {
                if (!AgentNotificationListener.isBound) {
                    return ctx.fail(
                        "Notification listener is not enabled. Ask the user (ask_user) to enable 'Mobile Agent' " +
                            "under Settings > Notification access."
                    )
                }
                val limit = args.optInt("limit", 20).coerceIn(1, 50)
                val appFilter = args.optString("app", "").lowercase()
                val arr = JSONArray()
                for (n in AgentNotificationListener.snapshot()) {
                    if (arr.length() >= limit) break
                    if (appFilter.isNotBlank() && !n.packageName.lowercase().contains(appFilter)) continue
                    arr.put(n.toJson())
                }
                ctx.ok("count" to arr.length(), "notifications" to arr)
            }
            "notification_action" -> {
                val id = args.getString("notification_id")
                val action = args.getString("action")
                val n = AgentNotificationListener.byId(id)
                    ?: throw ToolException("Notification '$id' not found (may have been cleared). Call read_notifications again.")
                when (action) {
                    "dismiss" -> {
                        val did = AgentNotificationListener.dismiss(n)
                        ctx.ok("dismissed" to did)
                    }
                    "open" -> {
                        val did = AgentNotificationListener.open(n)
                        ctx.ok("opened" to did)
                    }
                    "reply" -> {
                        val replyText = args.optString("reply_text", "")
                        if (replyText.isBlank()) {
                            throw ToolException("action=reply needs 'reply_text'.")
                        }
                        val result = AgentNotificationListener.reply(n, replyText)
                        if (result.first) {
                            ctx.ok("replied" to true, "note" to "Reply delivered via the notification's remote-input action.")
                        } else {
                            ctx.fail(
                                result.second,
                                JSONObject().put("fix", "Open the app with action=open, then use set_text + click_node to send the message.")
                            )
                        }
                    }
                    "action_button" -> {
                        val label = args.optString("button_label", "")
                        val did = AgentNotificationListener.triggerAction(n, label)
                        ctx.ok("action_triggered" to did)
                    }
                    else -> ctx.fail("Unknown notification action '$action'.")
                }
            }
            "get_clipboard" -> {
                val clip = ctx.clipboardManager().primaryClip
                if (clip == null || clip.itemCount == 0) {
                    return ctx.fail(
                        "Clipboard is empty or blocked (Android 10+ hides clipboard from background apps). " +
                            "Use read_screen_text or ask_user instead."
                    )
                }
                val text = clip.getItemAt(0).coerceToText(ctx.context)?.toString().orEmpty()
                if (text.isEmpty()) return ctx.fail("Clipboard content is empty or not readable text.")
                ctx.ok("text" to text.take(4000))
            }
            "set_clipboard" -> {
                ctx.setClipboardText(args.getString("text"))
                ctx.ok("copied" to true)
            }
            else -> ctx.fail("Unknown perception tool '$name'.")
        }
    }

    /** Try MediaProjection first, then the Android 11+ accessibility screenshot. */
    private suspend fun captureJpeg(ctx: ToolContext): ByteArray? {
        val projection = com.mobileagent.app.vision.ScreenCaptureManager.getInstance()
        if (projection.hasRecentFrame()) {
            projection.captureSnapshotJpeg(quality = 70)?.let { return it }
        }
        return if (ctx.accessibility != null) {
            com.mobileagent.app.automation.accessibility.AgentAccessibilityService.captureScreenJpeg(quality = 70)
        } else null
    }
}

/** Minimal growable byte sink without extra deps. */
class ByteArrayOutputStream2(initial: Int = 32_768) : java.io.OutputStream() {
    private val buf = java.io.ByteArrayOutputStream(initial)
    override fun write(b: Int) = buf.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
    fun toByteArray(): ByteArray = buf.toByteArray()
}
