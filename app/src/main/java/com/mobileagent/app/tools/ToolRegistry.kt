package com.mobileagent.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central tool registry for the agent.
 *
 * - Declares every callable tool to Gemini Live (compact schema DSL).
 * - Dispatches tool calls to their group implementations.
 *
 * IMPORTANT (verified live against the real API, see the live-test CI workflow):
 * the `google_search` server-side tool is NOT accepted by BidiGenerateContent
 * sessions on the current API — including it (alone or with functionDeclarations)
 * makes Google close the WebSocket immediately, which used to leave the agent
 * silently reconnecting forever (the "agent never responds" bug). Web research
 * stays available through the WebTools function set instead.
 */
object ToolRegistry {

    /** A single JSON-schema property. */
    data class P(
        val name: String,
        val type: String,
        val desc: String = "",
        val req: Boolean = false,
        val enum: List<String>? = null,
        val items: String? = null
    )

    /** A tool declaration. */
    data class T(val name: String, val desc: String, val params: List<P> = emptyList())

    fun p(name: String, type: String, desc: String = "", req: Boolean = false,
          enum: List<String>? = null, items: String? = null) = P(name, type, desc, req, enum, items)

    fun i(name: String) = P(name, "INTEGER", "$name coordinate (px)", req = true)

    fun t(name: String, desc: String, params: List<P> = emptyList()) = T(name, desc, params)

    /** Vararg overload: t("name", "desc", p(...), p(...)) without wrapping in listOf. */
    fun t(name: String, desc: String, vararg params: P) = T(name, desc, params.toList())

    // ---- kept legacy tools (executed by the battle-tested service handlers) ----

    private val legacyTools: List<T> = listOf(
        t("open_app", "Launch an app by name (e.g. WhatsApp, Spotify, Instagram) or a known web platform (opens in Chrome).",
            listOf(P("app", "STRING", "app name, package name, or web platform URL", req = true))),
        t("search_and_play_youtube", "Search YouTube and play the best matching song or video immediately.",
            listOf(P("query", "STRING", "song/video to search and play", req = true))),
        t("search_youtube", "Open YouTube with a search performed; results are listed, nothing is played yet.",
            listOf(P("query", "STRING", req = true))),
        t("youtube_accessibility_action", "Perform a YouTube-specific automation action (play, pause, fullscreen, subscribe).",
            listOf(P("action", "STRING", "the YouTube action to perform", req = true))),
        t("media_playback_control", "Control media playback in any app.",
            listOf(P("action", "STRING", "play | pause | next | previous | stop", req = true,
                enum = listOf("play", "pause", "next", "previous", "stop")))),
        t("send_whatsapp_message", "Open WhatsApp and send a message to a contact. Confirm risky sends first with ask_user.",
            listOf(P("recipient_name", "STRING", "contact or phone number", req = true),
                P("message", "STRING", req = true))),
        t("whatsapp_call", "Start a WhatsApp voice or video call to a contact.",
            listOf(P("recipient_name", "STRING", req = true),
                P("call_type", "STRING", "voice | video", enum = listOf("voice", "video")))),
        t("set_volume", "Set or adjust a volume level.",
            listOf(P("action", "STRING", "set | increase | decrease | mute | vibrate | unmute | dnd",
                enum = listOf("set", "increase", "decrease", "mute", "vibrate", "unmute", "dnd")),
                P("level_percent", "INTEGER", "0-100 when action=set"))),
        t("set_brightness", "Set or adjust screen brightness.",
            listOf(P("action", "STRING", "set | increase | decrease", enum = listOf("set", "increase", "decrease")),
                P("level_percent", "INTEGER", "0-100 when action=set"))),
        t("go_to_background", "Stop the active conversation and put the agent into standby wake-word mode.", listOf()),
        t("inspect_screen", "Read the foreground app and accessible UI elements summary before interacting.",
            listOf()),
        t("click_ui_element", "Click a UI element with best precision. When a screenshot is attached, pass x/y as " +
                "NORMALIZED 0-1000 coordinates read from the image (image_px * 1000 / image_dimension). The agent maps " +
                "them to the real screen and snaps to the nearest clickable element, so small estimation errors are " +
                "corrected automatically. Alternatively pass element_id from get_ui_tree. This is the most accurate click tool.",
            listOf(P("target_name", "STRING", "short name of what you are clicking", req = true),
                P("x", "INTEGER", "normalized 0-1000 horizontal position from a screenshot"),
                P("y", "INTEGER", "normalized 0-1000 vertical position from a screenshot"),
                P("element_id", "INTEGER", "node id from get_ui_tree (alternative to x/y)"),
                P("is_toggle", "BOOLEAN", "true when clicking a switch/checkbox/toggle"))),
        t("press_key", "Press a hardware-style key.",
            listOf(P("key", "STRING", "key name, e.g. enter, back, volume_up, volume_down, tab", req = true))),
        t("done", "Finish the current task. Always call this at the end with an honest report.",
            listOf(P("summary", "STRING", "what was done / final result", req = true),
                P("success", "BOOLEAN", "true only if the task truly completed")))
    )

    /** Every declared client tool. */
    fun allTools(): List<T> = legacyTools +
        PerceptionTools.tools() + GestureTools.tools() + NodeTools.tools() +
        GlobalActionTools.tools() + SystemTools.tools() + CommunicationTools.tools() +
        MediaFileTools.tools() + WebTools.tools() + AgentCoreTools.tools() +
        DeviceDiscoveryTools.tools()

    /** True when the registry itself executes this tool (vs. the service's legacy handlers). */
    fun handles(name: String): Boolean = when (name) {
        in legacyToolNames -> false
        in PerceptionTools.NAMES -> true
        in GestureTools.NAMES -> true
        in NodeTools.NAMES -> true
        in GlobalActionTools.NAMES -> true
        in SystemTools.NAMES -> true
        in CommunicationTools.NAMES -> true
        in MediaFileTools.NAMES -> true
        in WebTools.NAMES -> true
        in AgentCoreTools.NAMES -> true
        in DeviceDiscoveryTools.NAMES -> true
        else -> false
    }

    val legacyToolNames: Set<String> = legacyTools.map { it.name }.toSet()

    // ---- declarations for the Gemini setup message -------------------------

    fun functionDeclarationsJson(): JSONArray {
        val arr = JSONArray()
        for (tool in allTools()) {
            val props = JSONObject()
            val required = JSONArray()
            for (p in tool.params) {
                val prop = JSONObject().put("type", p.type.uppercase())
                if (p.desc.isNotBlank()) prop.put("description", p.desc)
                p.enum?.let { prop.put("enum", JSONArray(it)) }
                if (p.items != null) prop.put("items", JSONObject().put("type", p.items.uppercase()))
                props.put(p.name, prop)
                if (p.req) required.put(p.name)
            }
            val decl = JSONObject()
                .put("name", tool.name)
                .put("description", tool.desc)
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", props))
            if (required.length() > 0) decl.getJSONObject("parameters").put("required", required)
            arr.put(decl)
        }
        return arr
    }

    /**
     * The full `tools` array sent in the setup message.
     *
     * Verified against the real Gemini Live API: ONLY functionDeclarations are
     * accepted here. google_search grounding is deliberately NOT included —
     * it makes the server close the session at setup time.
     */
    fun setupToolsJson(): JSONArray = JSONArray()
        .put(JSONObject().put("functionDeclarations", functionDeclarationsJson()))

    // ---- dispatch -----------------------------------------------------------

    suspend fun dispatch(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        if (!handles(name)) {
            return ctx.fail(
                "Tool '$name' is not executable here. If it is one of your listed tools, call it outside batch_actions; " +
                    "otherwise pick a tool from your tool list."
            )
        }
        return try {
            when {
                name in GestureTools.NAMES -> GestureTools.run(name, args, ctx)
                name in NodeTools.NAMES -> NodeTools.run(name, args, ctx)
                name in PerceptionTools.NAMES -> PerceptionTools.run(name, args, ctx)
                name in GlobalActionTools.NAMES -> GlobalActionTools.run(name, args, ctx)
                name in SystemTools.NAMES -> SystemTools.run(name, args, ctx)
                name in CommunicationTools.NAMES -> CommunicationTools.run(name, args, ctx)
                name in MediaFileTools.NAMES -> MediaFileTools.run(name, args, ctx)
                name in WebTools.NAMES -> WebTools.run(name, args, ctx)
                name in AgentCoreTools.NAMES -> AgentCoreTools.run(name, args, ctx)
                name in DeviceDiscoveryTools.NAMES -> DeviceDiscoveryTools.run(name, args, ctx)
                else -> ctx.fail("Unknown tool '$name'.")
            }
        } catch (e: ToolException) {
            ctx.fail(e.message ?: "Tool '$name' refused to run.")
        } catch (e: Throwable) {
            ctx.fail("Tool '$name' failed: ${e.message ?: e::class.java.simpleName}")
        }
    }
}
