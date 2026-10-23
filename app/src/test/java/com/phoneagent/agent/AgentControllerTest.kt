package com.phoneagent.agent

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentControllerTest {

    private fun logger() = Logger().apply { minLevel = LogLevel.DEBUG }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        maxSteps: Int = 10,
        observer: suspend () -> String = { "screen" },
        runner: suspend (String, Int, String) -> StepOutcome,
    ) = AgentController(logger(), scope, maxSteps, runner, observer)

    @Test
    fun `runs until Done and reports the summary`() = runTest {
        var steps = 0
        val controller = controller(this) { _, step, _ ->
            steps = step + 1
            if (step >= 2) StepOutcome.Done("finished") else StepOutcome.Continue()
        }

        controller.start("do the thing")
        advanceUntilIdle()

        assertEquals(3, steps)
        assertEquals(AgentState.DONE, controller.status.value.state)
        assertEquals("finished", controller.status.value.summary)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `Failed outcome surfaces the reason`() = runTest {
        val controller = controller(this) { _, _, _ -> StepOutcome.Failed("no network") }

        controller.start("do the thing")
        advanceUntilIdle()

        assertEquals(AgentState.FAILED, controller.status.value.state)
        assertEquals("no network", controller.status.value.error)
    }

    @Test
    fun `Stopped outcome is terminal and cancelled`() = runTest {
        val controller = controller(this) { _, _, _ -> StepOutcome.Stopped("blocked app") }

        controller.start("do the thing")
        advanceUntilIdle()

        assertEquals(AgentState.CANCELLED, controller.status.value.state)
        assertTrue(controller.status.value.state.isTerminal)
    }

    @Test
    fun `step limit produces a failure rather than looping forever`() = runTest {
        val controller = controller(this, maxSteps = 3) { _, _, _ -> StepOutcome.Continue() }

        controller.start("loop forever")
        advanceUntilIdle()

        assertEquals(AgentState.FAILED, controller.status.value.state)
        assertEquals(3, controller.status.value.stepIndex)
        assertTrue(controller.status.value.error!!.contains("3 steps"))
    }

    @Test
    fun `WaitingUser pauses until an answer arrives`() = runTest {
        var asked = false
        val controller = controller(this) { _, _, _ ->
            if (!asked) {
                asked = true
                StepOutcome.WaitingUser("Which account?")
            } else {
                StepOutcome.Done("done")
            }
        }

        controller.start("transfer money")
        advanceUntilIdle()

        // Paused: the loop is waiting, not finished.
        assertEquals(AgentState.WAITING_USER, controller.status.value.state)
        assertEquals("Which account?", controller.status.value.question)
        assertTrue(controller.isRunning)

        controller.userAnswer("Savings")
        advanceUntilIdle()

        assertEquals(AgentState.DONE, controller.status.value.state)
        assertEquals("done", controller.status.value.summary)
    }

    @Test
    fun `stop cancels a running task within one step`() = runTest {
        // The runner suspends, so the loop is genuinely mid-step when STOP arrives.
        val controller = controller(this) { _, _, _ ->
            kotlinx.coroutines.delay(10_000)
            StepOutcome.Continue()
        }

        controller.start("long task")
        testScheduler.runCurrent()
        assertTrue(controller.isRunning)

        controller.stop()
        advanceUntilIdle()

        assertEquals(AgentState.CANCELLED, controller.status.value.state)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `stop while idle does not change state to cancelled`() = runTest {
        val controller = controller(this) { _, _, _ -> StepOutcome.Continue() }

        controller.stop()
        advanceUntilIdle()

        assertEquals(AgentState.IDLE, controller.status.value.state)
    }

    @Test
    fun `start is ignored while a task is already running`() = runTest {
        var starts = 0
        val controller = controller(this) { _, _, _ ->
            starts++
            StepOutcome.WaitingUser("q")
        }

        controller.start("first")
        advanceUntilIdle()
        controller.start("second")
        advanceUntilIdle()

        assertEquals(1, starts)
        assertEquals("first", controller.status.value.goal)

        controller.stop()
        advanceUntilIdle()
    }

    @Test
    fun `a crashing step runner fails the task instead of killing the process`() = runTest {
        val controller = controller(this) { _, _, _ -> throw IllegalStateException("boom") }

        controller.start("doomed")
        advanceUntilIdle()

        assertEquals(AgentState.FAILED, controller.status.value.state)
        assertEquals("boom", controller.status.value.error)
    }

    @Test
    fun `a confirmation that times out is reported and the loop moves on`() = runTest {
        // The whole point of the timeout: an unanswered confirmation must not hold the task
        // open. Silence is read as "no", which is the safe direction, and the loop carries on.
        var timedOut = false
        var step = 0
        val controller = AgentController(
            logger(),
            this,
            maxSteps = 10,
            stepRunner = { _, _, _ ->
                step++
                if (step == 1) {
                    StepOutcome.WaitingUser("May I send it?", timeoutMs = 1_000)
                } else {
                    StepOutcome.Done("moved on")
                }
            },
            observer = { "screen" },
            onAnswerTimeout = { timedOut = true },
        )

        controller.start("send something risky")
        advanceUntilIdle()

        assertTrue("the timeout callback must fire", timedOut)
        assertEquals(AgentState.DONE, controller.status.value.state)
        assertEquals("moved on", controller.status.value.summary)
    }

    @Test
    fun `a clarification with no timeout waits indefinitely`() = runTest {
        // A question that is not a confirmation has no deadline: a person may take as long as
        // they like, and cutting them off would silently change what the agent was asked.
        var step = 0
        val controller = AgentController(
            logger(),
            this,
            maxSteps = 10,
            stepRunner = { _, _, _ ->
                step++
                if (step == 1) {
                    StepOutcome.WaitingUser("Which account?")
                } else {
                    StepOutcome.Done("done")
                }
            },
            observer = { "screen" },
            onAnswerTimeout = { throw AssertionError("a clarification must not time out") },
        )

        controller.start("transfer")
        advanceUntilIdle()

        assertEquals(AgentState.WAITING_USER, controller.status.value.state)
        assertTrue(controller.isRunning)

        controller.userAnswer("Savings")
        advanceUntilIdle()

        assertEquals(AgentState.DONE, controller.status.value.state)
    }

    @Test
    fun `acknowledge clears a terminal state back to idle`() = runTest {
        val controller = controller(this) { _, _, _ -> StepOutcome.Done("ok") }

        controller.start("thing")
        advanceUntilIdle()
        controller.acknowledge()

        assertEquals(AgentState.IDLE, controller.status.value.state)
        assertEquals(null, controller.status.value.goal)
    }
}
