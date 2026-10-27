package com.mobileagent.app.tools

import android.content.Context
import android.content.Intent
import com.mobileagent.app.schedule.ScheduleStore
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Agent-core meta tools: batch execution, user questions, durable memory,
 * task planning and scheduled tasks.
 */
object AgentCoreTools {

    val NAMES: Set<String> = setOf(
        "batch_actions", "wait", "ask_user", "memory_save", "memory_recall",
        "memory_forget", "update_plan", "schedule_task", "list_scheduled_tasks",
        "cancel_scheduled_task"
    )

    /** Tools that must never run inside batch_actions (wait IS allowed - it is safe and useful between steps). */
    private val FORBIDDEN_IN_BATCH = setOf("batch_actions", "ask_user", "done")

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "batch_actions",
            "Run several simple, predictable actions in ONE step to save time. Use only when you already know " +
                "what the screen will look like. Actions run in order; the batch stops at the first failure and " +
                "reports what happened so far. Inside a batch target elements by query, never by node_id from an " +
                "earlier screen. Not allowed inside a batch: batch_actions, ask_user, done (wait IS allowed). " +
                "Do NOT use it for unfamiliar screens, risky actions, or when the next step depends on reading the screen.",
            ToolRegistry.p(
                "actions", "ARRAY",
                "Ordered list, max 8. Each: {tool, args, expect?}. expect: {text_visible?, element?, app?, timeout_ms?}",
                req = true, items = "OBJECT"
            ),
            ToolRegistry.p("stop_on_error", "BOOLEAN", "default true"),
            ToolRegistry.p("observe_after", "STRING", "none | ui_tree | screenshot, default ui_tree",
                enum = listOf("none", "ui_tree", "screenshot"))
        ),
        ToolRegistry.t("wait", "Pause for some seconds before the next action.",
            ToolRegistry.p("seconds", "NUMBER", req = true)),
        ToolRegistry.t(
            "ask_user",
            "Ask the user a question and wait for their answer. Use for ambiguity, missing info, CAPTCHA/OTP/biometric " +
                "handover, choosing between options, and for confirming risky or irreversible actions (sending messages, " +
                "calls, deleting, installing, payments) BEFORE doing them.",
            ToolRegistry.p("question", "STRING", req = true),
            ToolRegistry.p("options", "ARRAY", "quick choices as strings", items = "STRING")
        ),
        ToolRegistry.t(
            "memory_save", "Save a durable fact or preference about the user (how to address them, likes, routines, contacts).",
            ToolRegistry.p("key", "STRING", "short label, e.g. preferred_address", req = true),
            ToolRegistry.p("value", "STRING", req = true),
            ToolRegistry.p("category", "STRING", "Personal | Preferences | Contacts | Routines | Notes, default Preferences")
        ),
        ToolRegistry.t("memory_recall", "Search the durable memory store for saved facts: names, preferences, contacts, routines and taught patterns. Check here first for how to address the user.",
            ToolRegistry.p("query", "STRING", req = true),
            ToolRegistry.p("limit", "INTEGER", "default 5")),
        ToolRegistry.t("memory_forget", "Delete matching saved memories.",
            ToolRegistry.p("keyword", "STRING", req = true)),
        ToolRegistry.t(
            "skill_save", "Save a reusable multi-step SKILL so future sessions follow it automatically when a " +
                "trigger phrase matches. Use it when the user teaches a way of working ('aage se aise karna...', " +
                "'next time like this...') or when you discovered a genuinely reusable flow. Steps are plain " +
                "instructions naming the tools to use, in order.",
            ToolRegistry.p("name", "STRING", "short snake_case identifier, e.g. close_app_from_recents", req = true),
            ToolRegistry.p("triggers", "ARRAY", "phrases that should activate this skill", req = true, items = "STRING"),
            ToolRegistry.p("steps", "ARRAY", "ordered step instructions (which tool with which arguments)", req = true, items = "STRING"),
            ToolRegistry.p("notes", "STRING", "when/how to use it, caveats")
        ),
        ToolRegistry.t(
            "update_plan", "Write or update your step plan for the current task; it is shown to the user.",
            ToolRegistry.p(
                "steps", "ARRAY",
                "each: {text, status: pending | doing | done | failed}", req = true, items = "OBJECT"
            )
        ),
        ToolRegistry.t(
            "schedule_task", "Schedule this agent to run an instruction later or repeatedly.",
            ToolRegistry.p("instruction", "STRING", "what the agent should do when it fires", req = true),
            ToolRegistry.p("at", "STRING", "ISO datetime for one-time tasks, e.g. 2026-01-02T15:30:00"),
            ToolRegistry.p("repeat", "STRING", "none | hourly | daily | weekly, default none",
                enum = listOf("none", "hourly", "daily", "weekly"))
        ),
        ToolRegistry.t("list_scheduled_tasks", "List every scheduled task with its instruction, schedule and status, so you can report or cancel them accurately.", listOf()),
        ToolRegistry.t("cancel_scheduled_task", "Cancel a scheduled task.",
            ToolRegistry.p("task_id", "STRING", req = true))
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        return when (name) {
            "batch_actions" -> runBatch(args, ctx)
            "wait" -> {
                val seconds = args.optDouble("seconds", 1.0).coerceIn(0.1, 120.0)
                delay((seconds * 1000).toLong())
                ctx.ok("waited_s" to seconds)
            }
            "ask_user" -> {
                val question = args.getString("question")
                val options = args.optJSONArray("options")
                ctx.service.postAgentEvent(
                    "AGENT ASKS: $question" + (options?.let { o ->
                        " [" + (0 until o.length()).joinToString(" / ") { k -> o.optString(k) } + "]"
                    } ?: "")
                )
                ctx.ok(
                    "asked" to true,
                    "question" to question,
                    "what_now" to "The question has been shown to the user. END YOUR TURN now and listen; the user's next spoken or typed message is the answer. Do not take any action until they respond."
                )
            }
            "memory_save" -> {
                val key = args.getString("key")
                val value = args.getString("value")
                val category = when (args.optString("category", "Preferences")) {
                    "Personal" -> com.mobileagent.app.data.memory.MemoryEntity.CATEGORY_PERSONAL
                    "Preferences" -> com.mobileagent.app.data.memory.MemoryEntity.CATEGORY_PREFERENCES
                    "Contacts" -> com.mobileagent.app.data.memory.MemoryEntity.CATEGORY_CONTACTS
                    "Routines" -> com.mobileagent.app.data.memory.MemoryEntity.CATEGORY_ROUTINES
                    else -> com.mobileagent.app.data.memory.MemoryEntity.CATEGORY_NOTES
                }
                val id = ctx.app.memoryRepository.saveMemory(
                    rawText = "$key: $value",
                    category = category,
                    key = key,
                    value = value
                )
                ctx.ok("saved" to true, "id" to id, "key" to key)
            }
            "skill_save" -> {
                val name = args.getString("name")
                val triggers = mutableListOf<String>()
                args.optJSONArray("triggers")?.let { arr ->
                    for (i in 0 until arr.length()) triggers.add(arr.optString(i))
                }
                val steps = mutableListOf<String>()
                args.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) steps.add(arr.optString(i))
                }
                val notes = args.optString("notes", "")
                val (ok, msg) = ctx.app.skillManager.saveLearnedSkill(name, triggers, steps, notes)
                if (ok) ctx.ok("saved" to true, "message" to msg, "name" to name)
                else ctx.fail(msg)
            }
            "memory_recall" -> {
                val query = args.getString("query")
                val limit = args.optInt("limit", 5).coerceIn(1, 20)
                val hits = ctx.app.memoryRepository.searchMemories(query).take(limit)
                val arr = JSONArray()
                hits.forEach { m ->
                    arr.put(JSONObject()
                        .put("key", m.key)
                        .put("value", m.value)
                        .put("category", m.category)
                        .put("saved_at", m.timestamp))
                }
                ctx.ok("count" to arr.length(), "memories" to arr)
            }
            "memory_forget" -> {
                val keyword = args.getString("keyword")
                val deleted = ctx.app.memoryRepository.deleteMemoriesByKeyword(keyword)
                ctx.ok("deleted" to deleted)
            }
            "update_plan" -> {
                val steps = args.optJSONArray("steps") ?: throw ToolException("update_plan needs a 'steps' array.")
                val sb = StringBuilder("TASK PLAN:")
                for (i in 0 until steps.length()) {
                    val s = steps.optJSONObject(i) ?: continue
                    sb.append("\n  ").append(i + 1).append(". [")
                        .append(s.optString("status", "pending")).append("] ")
                        .append(s.optString("text"))
                }
                ctx.service.postAgentEvent(sb.toString())
                ctx.ok("plan_updated" to true, "steps" to steps.length())
            }
            "schedule_task" -> {
                val instruction = args.getString("instruction")
                val repeat = args.optString("repeat", "none")
                val atStr = args.optString("at", "")
                val atMillis: Long = if (repeat == "none") {
                    if (atStr.isBlank()) throw ToolException("One-time tasks need an 'at' ISO datetime, or set repeat to hourly/daily/weekly.")
                    CommunicationTools.parseIso(atStr).timeInMillis
                } else {
                    // First occurrence: explicit 'at', else the next whole hour.
                    if (atStr.isNotBlank()) CommunicationTools.parseIso(atStr).timeInMillis
                    else (Calendar.getInstance().apply {
                        add(Calendar.HOUR, 1)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                    }).timeInMillis
                }
                if (atMillis <= System.currentTimeMillis() && repeat == "none") {
                    throw ToolException("The scheduled time is in the past. Use a future ISO datetime.")
                }
                val task = ScheduleStore.add(ctx.context, instruction, atMillis, repeat)
                ctx.ok("scheduled" to true, "task_id" to task.id, "instruction" to instruction, "repeat" to repeat)
            }
            "list_scheduled_tasks" -> {
                val arr = JSONArray()
                ScheduleStore.all(ctx.context).forEach { arr.put(it.toJson()) }
                ctx.ok("count" to arr.length(), "tasks" to arr)
            }
            "cancel_scheduled_task" -> {
                val cancelled = ScheduleStore.cancel(ctx.context, args.getString("task_id"))
                ctx.ok("cancelled" to cancelled)
            }
            else -> ctx.fail("Unknown agent-core tool '$name'.")
        }
    }

    private suspend fun runBatch(args: JSONObject, ctx: ToolContext): JSONObject {
        val actions = args.optJSONArray("actions") ?: throw ToolException("batch_actions needs an 'actions' array.")
        if (actions.length() == 0) throw ToolException("actions array is empty.")
        if (actions.length() > 8) throw ToolException("batch_actions supports at most 8 actions.")
        val stopOnError = args.optBoolean("stop_on_error", true)
        val observeAfter = args.optString("observe_after", "ui_tree")

        val results = JSONArray()
        for (i in 0 until actions.length()) {
            val item = actions.optJSONObject(i)
                ?: throw ToolException("actions[$i] is not an object.")
            val tool = item.optString("tool", "")
            if (tool.isBlank()) throw ToolException("actions[$i] is missing 'tool'.")
            if (tool in FORBIDDEN_IN_BATCH) {
                return ctx.fail("Action $i ('$tool') is not allowed inside batch_actions.",
                    JSONObject().put("results", results))
            }
            val toolArgs = item.optJSONObject("args") ?: JSONObject()
            val step = JSONObject().put("tool", tool).put("index", i)
            try {
                val r = ToolRegistry.dispatch(tool, toolArgs, ctx)
                step.put("result", r)
                val ok = r.optBoolean("success", true)
                if (ok) {
                    val expect = item.optJSONObject("expect")
                    if (expect != null) {
                        val verified = checkExpect(expect, ctx)
                        step.put("expect_met", verified)
                        if (!verified && stopOnError) {
                            results.put(step)
                            return ctx.fail("Expect check failed after action $i ('$tool').", JSONObject().put("results", results))
                        }
                    }
                }
                if (!ok && stopOnError) {
                    results.put(step)
                    return ctx.fail("Action $i ('$tool') failed. Batch stopped.", JSONObject().put("results", results))
                }
            } catch (e: ToolException) {
                step.put("result", ctx.fail(e.message ?: "tool error"))
                results.put(step)
                if (stopOnError) {
                    return ctx.fail("Action $i ('$tool') threw: ${e.message}", JSONObject().put("results", results))
                }
            }
            delay(180)
        }

        // Final observation so the model does not need an extra step.
        val obs = JSONObject()
        if (observeAfter == "ui_tree") {
            ctx.root?.let { root ->
                val arr = JSONArray()
                NodeQuery.walk(root, maxNodes = 120).forEachIndexed { idx, n ->
                    if (arr.length() < 40 && NodeQuery.interactive(n)) arr.put(NodeQuery.nodeJson(n, idx))
                }
                obs.put("package", root.packageName?.toString() ?: "")
                obs.put("nodes", arr)
            }
        } else if (observeAfter == "screenshot") {
            runCatching {
                PerceptionTools.run("screenshot", JSONObject(), ctx)
            }
        }
        return ctx.ok("batch_done" to true, "steps" to results.length(), "results" to results, "observe" to obs)
    }

    private suspend fun checkExpect(expect: JSONObject, ctx: ToolContext): Boolean {
        val timeout = expect.optLong("timeout_ms", 3000L).coerceIn(300, 15_000)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val root = ctx.root
            if (root != null) {
                expect.optString("text_visible", "").takeIf { it.isNotBlank() }?.let { txt ->
                    if (NodeQuery.find(root, txt).isNotEmpty()) return true
                }
                expect.optString("element", "").takeIf { it.isNotBlank() }?.let { el ->
                    if (NodeQuery.find(root, el).isNotEmpty()) return true
                }
                expect.optString("app", "").takeIf { it.isNotBlank() }?.let { appPkg ->
                    val current = root.packageName?.toString()?.lowercase() ?: ""
                    if (current.contains(appPkg.lowercase())) return true
                }
            }
            delay(300)
        }
        return false
    }
}
