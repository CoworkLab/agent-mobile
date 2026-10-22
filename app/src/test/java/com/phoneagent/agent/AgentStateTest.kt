package com.phoneagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateTest {

    @Test
    fun `terminal states are exactly the three end states`() {
        val terminal = AgentState.entries.filter { it.isTerminal }
        assertTrue(terminal.containsAll(listOf(AgentState.DONE, AgentState.FAILED, AgentState.CANCELLED)))
        assertTrue(terminal.size == 3)
    }

    @Test
    fun `idle and waiting user are not busy and not terminal`() {
        listOf(AgentState.IDLE, AgentState.WAITING_USER).forEach { state ->
            assertFalse("$state should not be busy", state.isBusy)
            assertFalse("$state should not be terminal", state.isTerminal)
        }
    }

    @Test
    fun `working states are busy and cancellable`() {
        listOf(
            AgentState.PLANNING,
            AgentState.OBSERVING,
            AgentState.DECIDING,
            AgentState.GUARDING,
            AgentState.ACTING,
            AgentState.VERIFYING,
        ).forEach { state ->
            assertTrue("$state should be busy", state.isBusy)
        }
    }
}
