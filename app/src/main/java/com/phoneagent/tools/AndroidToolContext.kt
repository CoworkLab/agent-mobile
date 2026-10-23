package com.phoneagent.tools

import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode

/**
 * The real [ToolContext]: the last screen the loop saw, plus the real hands.
 *
 * The snapshot is captured once per step and handed in, rather than read fresh on every tool
 * call. That is deliberate. Marks are resolved against the screen the model was shown; if the
 * context re-read the screen mid-step, a mark the model picked would be resolved against a
 * screen it never saw, and a tap would land on whatever moved into that position.
 *
 * Tools that change the screen call [refreshScreen] to get the new one. That path does read
 * fresh, because at that point the tool is verifying its own effect rather than resolving a
 * mark.
 */
class AndroidToolContext(
    private val perception: PerceptionSource,
    private val mode: PerceptionMode,
    private val snapshot: ScreenSnapshot,
    override val actuator: Actuator,
) : ToolContext {

    override fun currentNodes(): List<UiNode> = snapshot.nodes

    override fun currentPackageName(): String? = snapshot.packageName

    override suspend fun refreshScreen(): ScreenSnapshot = perception.capture(mode)
}
