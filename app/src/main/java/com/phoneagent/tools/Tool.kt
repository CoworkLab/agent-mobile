package com.phoneagent.tools

import com.phoneagent.safety.RiskLevel

/**
 * One call the model asked for. `args` is the raw JSON object so the registry can validate it
 * against the tool's schema and produce a useful error instead of a crash.
 */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
) {
    fun string(key: String): String? = args[key] as? String
    fun int(key: String): Int? = (args[key] as? Number)?.toInt()
    fun double(key: String): Double? = (args[key] as? Number)?.toDouble()
    fun bool(key: String): Boolean? = args[key] as? Boolean
    fun stringList(key: String): List<String>? =
        (args[key] as? List<*>)?.mapNotNull { it as? String }

    fun mapList(key: String): List<Map<String, Any?>>? =
        (args[key] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { entry -> entry.entries.associate { (k, v) -> k.toString() to v } }
}

/**
 * What happened. Failures carry a message the model can act on, which is why [message] is
 * written as guidance rather than an error code.
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val screenChanged: Boolean? = null,
    val data: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun ok(
            message: String,
            screenChanged: Boolean? = null,
            data: Map<String, Any?> = emptyMap(),
        ) = ToolResult(true, message, screenChanged, data)

        fun fail(message: String) = ToolResult(false, message)
    }
}

/**
 * A capability the agent can invoke.
 *
 * [riskLevel] is the tool's own risk before looking at a target: `type_text` is MEDIUM
 * because typing can change state, while `tap` is LOW and gets its risk from the node it
 * touches.
 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema for the arguments, exported to the model as a function definition. */
    val parametersSchema: Map<String, Any?>

    val riskLevel: RiskLevel get() = RiskLevel.LOW

    /** Tools that only read never need approval, whatever the screen says. */
    val isReadOnly: Boolean get() = false

    /** True when the tool may appear inside `batch_actions`. */
    val allowedInBatch: Boolean get() = false

    suspend fun execute(call: ToolCall, context: ToolContext): ToolResult
}

/**
 * Everything a tool needs to act. Kept as an interface so tools can be tested with fakes and
 * so the perception/actuator split from the design survives into the implementation.
 */
interface ToolContext {
    /** The most recent screen, used to resolve marks to nodes. */
    fun currentNodes(): List<com.phoneagent.perception.UiNode>

    fun currentPackageName(): String?

    /** Refreshes perception; called by tools that change the screen and need to confirm. */
    suspend fun refreshScreen(): com.phoneagent.perception.ScreenSnapshot

    /** Runs a gesture or text action on the real device. */
    val actuator: Actuator
}

/**
 * The "hands". Every method returns whether the platform accepted the call; the caller
 * verifies the screen afterwards rather than trusting the return value.
 */
interface Actuator {
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean
    suspend fun doubleTap(x: Int, y: Int): Boolean
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean
    suspend fun pinch(centerX: Int, centerY: Int, out: Boolean): Boolean
    suspend fun setText(nodeId: Int, text: String, clearFirst: Boolean): Boolean
    suspend fun pressEnter(nodeId: Int?): Boolean
    suspend fun clearText(nodeId: Int): Boolean
    suspend fun globalAction(action: GlobalAction): Boolean
    suspend fun scroll(nodeId: Int?, direction: ScrollDirection): Boolean
}
