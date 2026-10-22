package com.phoneagent.agent

import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopBudgetTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `a fresh budget allows work`() {
        assertNull(LoopBudget(LoopLimits.DEFAULT).check())
    }

    @Test
    fun `the step limit stops the loop`() {
        val budget = LoopBudget(LoopLimits(maxSteps = 3))
        budget.start()
        repeat(3) { budget.recordStep() }

        val reason = budget.check()
        assertTrue(reason is StopReason.StepLimitReached)
        assertTrue(reason!!.describe().contains("3"))
    }

    @Test
    fun `the time limit stops the loop`() {
        val clock = FakeClock()
        val budget = LoopBudget(LoopLimits(maxDurationMs = 1000), clock = { clock.now })
        budget.start()
        clock.advance(1001)

        assertTrue(budget.check() is StopReason.TimeLimitReached)
    }

    @Test
    fun `the time limit is reported in minutes for the user`() {
        val reason = StopReason.TimeLimitReached(10 * 60 * 1000L)
        assertTrue(reason.describe(), reason.describe().contains("10 minutes"))
    }

    @Test
    fun `consecutive tool errors stop the loop`() {
        val budget = LoopBudget(LoopLimits(maxConsecutiveToolErrors = 3))
        budget.start()
        repeat(3) { budget.recordToolResult(success = false) }

        assertTrue(budget.check() is StopReason.ToolErrors)
    }

    @Test
    fun `a success resets the error count`() {
        val budget = LoopBudget(LoopLimits(maxConsecutiveToolErrors = 3))
        budget.start()
        repeat(2) { budget.recordToolResult(success = false) }
        budget.recordToolResult(success = true)
        repeat(2) { budget.recordToolResult(success = false) }

        assertNull("errors must be consecutive, not cumulative", budget.check())
    }

    @Test
    fun `the action rate is capped inside a minute`() {
        val clock = FakeClock()
        val budget = LoopBudget(LoopLimits(maxActionsPerMinute = 5), clock = { clock.now })
        budget.start()

        repeat(5) { assertNull(budget.recordAction()) }

        assertTrue("the sixth action in the window must be refused", budget.recordAction() != null)
    }

    @Test
    fun `the rate window slides rather than resetting`() {
        val clock = FakeClock()
        val budget = LoopBudget(LoopLimits(maxActionsPerMinute = 5), clock = { clock.now })
        budget.start()

        repeat(5) { budget.recordAction() }
        clock.advance(61_000)

        assertNull("old actions should have left the window", budget.recordAction())
    }

    @Test
    fun `an old action does not count against a new burst`() {
        val clock = FakeClock()
        val budget = LoopBudget(LoopLimits(maxActionsPerMinute = 3), clock = { clock.now })
        budget.start()

        budget.recordAction()
        clock.advance(30_000)
        budget.recordAction()
        clock.advance(31_000)
        // The first action is now older than a minute and must not be counted.
        assertNull(budget.recordAction())
        assertNull(budget.recordAction())
    }
}

class StuckDetectorTest {

    private fun detector(
        stepsBeforeHint: Int = 2,
        beforeReplan: Int = 3,
        beforeGivingUp: Int = 5,
    ) = StuckDetector(
        LoopLimits(
            stuckStepsBeforeHint = stepsBeforeHint,
            stuckEventsBeforeReplan = beforeReplan,
            stuckEventsBeforeGivingUp = beforeGivingUp,
        ),
    )

    @Test
    fun `the first observation never looks stuck`() {
        assertNull(detector().observe("hash-a").hint)
    }

    @Test
    fun `one repeated hash is not yet stuck`() {
        val detector = detector(stepsBeforeHint = 2)
        detector.observe("a")
        assertNull("one repeat is normal", detector.observe("a").hint)
    }

    @Test
    fun `two repeated hashes produce a hint`() {
        val detector = detector(stepsBeforeHint = 2)
        detector.observe("a")
        detector.observe("a")
        assertNotNull("two identical screens mean something is wrong", detector.observe("a").hint)
    }

    @Test
    fun `the hints escalate instead of repeating`() {
        val detector = detector(stepsBeforeHint = 1, beforeReplan = 99, beforeGivingUp = 99)
        detector.observe("a")

        val hints = mutableListOf<String>()
        repeat(4) { detector.observe("a").hint?.let { hints += it } }

        assertEquals("each hint should differ", hints.size, hints.distinct().size)
    }

    @Test
    fun `the last hint suggests the screenshot view`() {
        val detector = detector(stepsBeforeHint = 1, beforeReplan = 99, beforeGivingUp = 99)
        detector.observe("a")

        val hints = mutableListOf<String>()
        repeat(5) { detector.observe("a").hint?.let { hints += it } }

        assertTrue(
            "the screenshot hint is the fallback when the tree is unhelpful: $hints",
            hints.any { it.contains("screenshot") },
        )
    }

    @Test
    fun `a changed screen clears the stuck state`() {
        // Two unchanged steps are needed, which is the shipped default.
        val detector = detector(stepsBeforeHint = 2, beforeReplan = 2, beforeGivingUp = 99)
        detector.observe("a")
        detector.observe("a")
        detector.observe("a")

        // The screen moved, so the hint counter starts again: one repeat is not stuck.
        assertNull(detector.observe("b").hint)
        assertNull("a single repeat is not stuck", detector.observe("b").hint)
    }

    @Test
    fun `stuck events accumulate across the run, because repeated stalling means a bad plan`() {
        val detector = detector(stepsBeforeHint = 1, beforeReplan = 3, beforeGivingUp = 99)
        detector.observe("a")

        detector.observe("a")
        detector.observe("b")
        detector.observe("b")
        val third = detector.observe("b")

        assertEquals("progress does not erase the history of stalling", 3, third.stuckEvents)
        assertTrue(third.replan)
    }

    @Test
    fun `stuck events eventually ask for a replan`() {
        val detector = detector(stepsBeforeHint = 1, beforeReplan = 3, beforeGivingUp = 99)
        detector.observe("a")

        val reactions = (1..3).map { detector.observe("a") }

        assertTrue("the third stuck event should replan", reactions.last().replan)
        assertFalse("and should not give up yet", reactions.last().giveUp)
    }

    @Test
    fun `stuck events eventually give up`() {
        val detector = detector(stepsBeforeHint = 1, beforeReplan = 2, beforeGivingUp = 4)
        detector.observe("a")

        val reactions = (1..4).map { detector.observe("a") }

        assertTrue("the fourth stuck event should give up", reactions.last().giveUp)
        assertEquals(4, reactions.last().stuckEvents)
    }

    @Test
    fun `reset clears the counters`() {
        val detector = detector(stepsBeforeHint = 1)
        detector.observe("a")
        detector.observe("a")
        detector.reset()

        assertNull(detector.observe("a").hint)
        assertEquals(0, detector.events)
    }
}

class HistoryCompactorTest {

    private fun entry(index: Int, withScreenshot: Boolean = false) = HistoryCompactor.Entry(
        stepIndex = index,
        summary = "did step $index",
        screenshotJpeg = if (withScreenshot) ByteArray(1000) { 1 } else null,
        screenshotBytes = if (withScreenshot) 1000 else 0,
    )

    @Test
    fun `an empty history produces nothing`() {
        assertTrue(HistoryCompactor(LoopLimits.DEFAULT).forPrompt().isEmpty())
    }

    @Test
    fun `only the last two screenshots are kept`() {
        val compactor = HistoryCompactor(LoopLimits(maxScreenshotsInHistory = 2))
        repeat(5) { compactor.add(entry(it, withScreenshot = true)) }

        val kept = compactor.forPrompt()
        assertEquals("all steps are still listed", 5, kept.size)
        assertEquals(
            "only the last two keep their image",
            2,
            kept.count { it.screenshotJpeg != null },
        )
    }

    @Test
    fun `older steps keep a one-line summary`() {
        val compactor = HistoryCompactor(LoopLimits(maxScreenshotsInHistory = 1))
        repeat(4) { compactor.add(entry(it, withScreenshot = true)) }

        val oldest = compactor.forPrompt().first()
        assertNull(oldest.screenshotJpeg)
        assertTrue(oldest.summary.isNotBlank())
    }

    @Test
    fun `a long summary is truncated when its screenshot is dropped`() {
        val compactor = HistoryCompactor(LoopLimits(maxScreenshotsInHistory = 0))
        compactor.add(
            HistoryCompactor.Entry(stepIndex = 0, summary = "x".repeat(500), screenshotBytes = 0),
        )

        assertTrue(compactor.forPrompt().first().summary.length <= 200)
    }

    @Test
    fun `trimming drops the oldest entries until the prompt fits`() {
        val compactor = HistoryCompactor(LoopLimits(maxPromptChars = 100))
        repeat(10) { compactor.add(entry(it)) }

        // Each entry costs 50 characters, so at most two fit.
        val fitted = compactor.trimToFit { list -> list.size * 50 }

        assertTrue("the estimate must fit the cap", fitted.size * 50 <= 100)
        assertTrue("the newest entry must survive", fitted.last().stepIndex == 9)
    }

    @Test
    fun `trimming keeps at least one entry even when nothing fits`() {
        val compactor = HistoryCompactor(LoopLimits(maxPromptChars = 1))
        repeat(3) { compactor.add(entry(it)) }

        val fitted = compactor.trimToFit { 10_000 }

        assertEquals("something must be sent, or the model has no context", 1, fitted.size)
    }

    @Test
    fun `the screenshot byte total reflects what is held`() {
        val compactor = HistoryCompactor(LoopLimits.DEFAULT)
        repeat(3) { compactor.add(entry(it, withScreenshot = true)) }

        assertEquals(3000, compactor.screenshotBytes())
    }

    @Test
    fun `clear empties the history`() {
        val compactor = HistoryCompactor(LoopLimits.DEFAULT)
        compactor.add(entry(0))
        compactor.clear()

        assertEquals(0, compactor.size())
    }
}

class AgentPlanTest {

    @Test
    fun `a numbered plan is parsed`() {
        val plan = AgentPlan.parse("1. Open WhatsApp\n2. Find Amit\n3. Type hello")
        assertEquals(listOf("Open WhatsApp", "Find Amit", "Type hello"), plan.steps)
    }

    @Test
    fun `bullets are parsed`() {
        val plan = AgentPlan.parse("- Open WhatsApp\n* Find Amit\n• Type hello")
        assertEquals(3, plan.steps.size)
        assertEquals("Open WhatsApp", plan.steps[0])
    }

    @Test
    fun `a plain line list is parsed`() {
        val plan = AgentPlan.parse("Open WhatsApp\nFind Amit")
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun `a plan longer than seven steps is capped`() {
        val plan = AgentPlan.parse((1..20).joinToString("\n") { "$it. step $it" })
        assertEquals(AgentPlan.MAX_PLAN_LINES, plan.steps.size)
    }

    @Test
    fun `a plan is capped because the brief asks for three to seven steps`() {
        assertTrue(AgentPlan.MAX_PLAN_LINES in 3..7)
    }

    @Test
    fun `blank lines and a bare plan heading are dropped`() {
        val plan = AgentPlan.parse("Plan:\n\n1. Do a thing\n\n")
        assertEquals(listOf("Do a thing"), plan.steps)
    }

    @Test
    fun `a step that begins with the word plan is kept`() {
        val plan = AgentPlan.parse("1. Plan the route\n2. Go")
        assertEquals(listOf("Plan the route", "Go"), plan.steps)
    }

    @Test
    fun `an empty or null plan is empty but still valid`() {
        assertTrue(AgentPlan.parse(null).isEmpty)
        assertTrue(AgentPlan.parse("").isEmpty)
        assertTrue(AgentPlan.parse("   \n  ").isEmpty)
    }

    @Test
    fun `the prompt text is numbered`() {
        val text = AgentPlan.parse("a\nb").asPromptText()
        assertTrue(text.startsWith("1. a"))
        assertTrue(text.contains("2. b"))
    }

    @Test
    fun `an empty plan shows a placeholder rather than nothing`() {
        assertEquals("(no plan)", AgentPlan.parse("").asPromptText())
    }
}
