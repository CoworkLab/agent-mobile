package com.phoneagent.tools

import com.phoneagent.perception.UiNode

/**
 * Turns a model's target (`mark` or `x,y`) into a real node and a real point.
 *
 * Marks are the preferred way to address things because a mark is bound to a node in the
 * snapshot the model actually saw. Coordinates are accepted as a fallback for custom-drawn
 * screens, where there are no nodes to mark.
 */
object NodeResolver {

    sealed interface Resolved {
        val x: Int
        val y: Int
        val node: UiNode?

        data class FromMark(override val node: UiNode) : Resolved {
            override val x: Int get() = node.bounds.centerX
            override val y: Int get() = node.bounds.centerY
        }

        data class FromPoint(override val x: Int, override val y: Int) : Resolved {
            override val node: UiNode? get() = null
        }
    }

    /**
     * Why a target could not be resolved. Extends `Exception` so it can travel through
     * `Result.failure` and still carry a message written for the model to act on.
     */
    sealed class Failure(message: String) : Exception(message) {
        class NoSuchMark(val mark: Int) : Failure(
            "There is no element [$mark] on this screen. Look at the current marks again.",
        )

        data object NoTarget : Failure(
            "Give either a mark from the screen or an x,y position.",
        )

        class OutOfBounds(val x: Int, val y: Int, val maxX: Int, val maxY: Int) : Failure(
            "Position ($x,$y) is outside the screen (0,0-$maxX,$maxY).",
        )
    }

    /**
     * Resolves a call's target. When both a mark and coordinates are given the mark wins,
     * because the mark was chosen by looking at the screen and the coordinates may be stale.
     */
    fun resolve(
        mark: Int?,
        x: Int?,
        y: Int?,
        nodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): Result<Resolved> {
        if (mark != null) {
            val node = nodes.firstOrNull { it.mark == mark }
                ?: return Result.failure(Failure.NoSuchMark(mark))
            return Result.success(Resolved.FromMark(node))
        }

        if (x == null || y == null) return Result.failure(Failure.NoTarget)

        // Clamp rather than reject a point a few pixels off: the screen bounds reported by
        // the platform can differ slightly from the bitmap the model measured.
        if (x < -SLACK_PX || y < -SLACK_PX ||
            x > screenWidth + SLACK_PX || y > screenHeight + SLACK_PX
        ) {
            return Result.failure(Failure.OutOfBounds(x, y, screenWidth, screenHeight))
        }

        val clampedX = x.coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        val clampedY = y.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        return Result.success(Resolved.FromPoint(clampedX, clampedY))
    }

    private const val SLACK_PX = 8
}

/** Shared helper for the many small schema definitions below. */
internal fun schemaOf(
    properties: Map<String, Any?>,
    required: List<String> = emptyList(),
): Map<String, Any?> = buildMap {
    put("type", "object")
    put("properties", properties)
    if (required.isNotEmpty()) put("required", required)
    put("additionalProperties", false)
}

internal fun stringProperty(description: String) =
    mapOf("type" to "string", "description" to description)

internal fun intProperty(description: String) =
    mapOf("type" to "integer", "description" to description)

internal fun numberProperty(description: String) =
    mapOf("type" to "number", "description" to description)

internal fun boolProperty(description: String) =
    mapOf("type" to "boolean", "description" to description)
