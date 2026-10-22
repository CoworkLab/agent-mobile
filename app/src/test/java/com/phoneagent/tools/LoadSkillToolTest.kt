package com.phoneagent.tools

import com.phoneagent.memory.Skill
import com.phoneagent.memory.SkillLibrary
import com.phoneagent.memory.SkillStatus
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadSkillToolTest {

    private val logger = Logger()

    private fun tool(vararg skills: Skill) = LoadSkillTool(SkillLibrary(skills.toList()), logger)

    private val context = object : ToolContext {
        override fun currentNodes(): List<UiNode> = emptyList()
        override fun currentPackageName(): String? = "com.example.app"
        override suspend fun refreshScreen(): ScreenSnapshot = ScreenSnapshot(
            packageName = "com.example.app",
            windowTitle = null,
            timestampMs = 0,
            nodes = emptyList(),
            screenHash = "h",
            mode = PerceptionMode.TREE_ONLY,
        )
        override val actuator = FakeActuator()
    }

    private val popups = Skill(
        name = "Dismiss popups",
        description = "Getting past a dialog",
        body = "1. Read the dialog.\n2. Choose the quieter option.",
    )

    @Test
    fun `reading a skill returns its full text`() = runBlocking {
        val result = tool(popups).execute(ToolCall("load_skill", mapOf("id" to "dismiss-popups")), context)

        assertTrue(result.success)
        assertTrue(result.message, result.message.contains("quieter option"))
    }

    @Test
    fun `reading a skill does not claim the screen changed`() = runBlocking {
        val result = tool(popups).execute(ToolCall("load_skill", mapOf("id" to "dismiss-popups")), context)

        assertFalse(
            "claiming a change would make the loop wait for one that never comes",
            result.screenChanged == true,
        )
    }

    @Test
    fun `a skill can be read by its name as well as its id`() = runBlocking {
        val result = tool(popups).execute(ToolCall("load_skill", mapOf("id" to "Dismiss popups")), context)

        assertTrue(result.success)
    }

    @Test
    fun `an unknown skill fails with a helpful message rather than throwing`() = runBlocking {
        val result = tool(popups).execute(ToolCall("load_skill", mapOf("id" to "nope")), context)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("no skill"))
    }

    @Test
    fun `a missing id fails with an explanation`() = runBlocking {
        val result = tool(popups).execute(ToolCall("load_skill", emptyMap()), context)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("id"))
    }

    @Test
    fun `a draft cannot be read, so an unreviewed procedure never reaches a run`() = runBlocking {
        val draft = popups.copy(status = SkillStatus.DRAFT)

        val result = tool(draft).execute(ToolCall("load_skill", mapOf("id" to "dismiss-popups")), context)

        assertFalse(result.success)
        assertFalse("the body must not leak", result.message.contains("quieter option"))
    }

    @Test
    fun `the tool is named load_skill and asks for an id`() {
        val tool = tool(popups)

        assertTrue(tool.name == "load_skill")
        assertTrue(tool.parametersSchema.toString().contains("id"))
    }

    @Test
    fun `the description says it does not change the screen`() {
        assertTrue(tool(popups).description.contains("does not change the screen"))
    }
}
