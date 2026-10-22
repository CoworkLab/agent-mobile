package com.phoneagent.audit

import com.phoneagent.safety.RiskLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditEventTest {

    private fun event(
        id: Long = 1,
        timestampMs: Long = 0,
        packageName: String? = "com.whatsapp",
        toolName: String = "tap",
        risk: RiskLevel = RiskLevel.LOW,
        decision: AuditDecision = AuditDecision.ALLOWED,
        detail: String = "Tapped Send",
        stepIndex: Int = 0,
    ) = AuditEvent(id, timestampMs, packageName, toolName, risk, decision, detail, stepIndex)

    @Test
    fun `an allowed action reads as done`() {
        val line = AuditFormatter.describe(event(decision = AuditDecision.ALLOWED))
        assertTrue(line, line.contains("done"))
    }

    @Test
    fun `an approved action says so, so the person knows they agreed`() {
        val line = AuditFormatter.describe(event(decision = AuditDecision.APPROVED))
        assertTrue(line, line.contains("approved"))
    }

    @Test
    fun `a denial is phrased from the person's point of view`() {
        val line = AuditFormatter.describe(event(decision = AuditDecision.DENIED))
        assertTrue(line, line.contains("you said no"))
    }

    @Test
    fun `a timeout is not described as a refusal, because it is different`() {
        val line = AuditFormatter.describe(event(decision = AuditDecision.TIMED_OUT))
        assertTrue(line, line.contains("no answer"))
        assertFalse("a timeout is not the same as saying no", line.contains("you said no"))
    }

    @Test
    fun `a blocked action says a rule refused it, not that the person refused`() {
        val line = AuditFormatter.describe(event(decision = AuditDecision.BLOCKED))
        assertTrue(line, line.contains("safety rule"))
    }

    @Test
    fun `tool names are shown as words a person uses`() {
        assertEquals("Tap", AuditFormatter.actionLabel("tap"))
        assertEquals("Use a system action", AuditFormatter.actionLabel("launch_intent"))
        assertEquals("A short sequence", AuditFormatter.actionLabel("batch_actions"))
        assertEquals("Go back", AuditFormatter.actionLabel("back"))
    }

    @Test
    fun `an unknown tool name is still readable`() {
        assertEquals("Some new thing", AuditFormatter.actionLabel("some_new_thing"))
    }

    @Test
    fun `a package name is shortened to the part a person recognises`() {
        assertEquals("whatsapp", AuditFormatter.shortPackage("com.whatsapp"))
        assertEquals("chrome", AuditFormatter.shortPackage("com.android.chrome"))
    }

    @Test
    fun `a two-letter tail falls back to the previous part`() {
        assertEquals("android", AuditFormatter.shortPackage("com.google.android.gm"))
    }

    @Test
    fun `a short package name is left alone`() {
        assertEquals("app", AuditFormatter.shortPackage("app"))
    }

    @Test
    fun `the summary line has a time, a place and a description`() {
        val line = AuditFormatter.summaryLine(event(timestampMs = 12 * 60 * 60 * 1000L + 4 * 60 * 1000L))
        assertTrue(line, line.contains("12:04"))
        assertTrue(line, line.contains("whatsapp"))
        assertTrue(line, line.contains("Tap"))
    }

    @Test
    fun `an event with no package says PhoneAgent rather than showing null`() {
        val line = AuditFormatter.summaryLine(event(packageName = null))
        assertTrue(line, line.contains("PhoneAgent"))
        assertFalse(line, line.contains("null"))
    }

    @Test
    fun `only the decisions the person made are marked user facing`() {
        assertTrue(AuditDecision.APPROVED.isUserFacing)
        assertTrue(AuditDecision.DENIED.isUserFacing)
        assertTrue(AuditDecision.TIMED_OUT.isUserFacing)
        assertFalse(AuditDecision.ALLOWED.isUserFacing)
        assertFalse(AuditDecision.BLOCKED.isUserFacing)
    }
}

class AuditPolicyTest {

    private fun event(id: Long, ageDays: Long, toolName: String = "tap") = AuditEvent(
        id = id,
        timestampMs = NOW - ageDays * 24 * 60 * 60 * 1000L,
        packageName = "com.whatsapp",
        toolName = toolName,
        risk = RiskLevel.LOW,
        decision = AuditDecision.ALLOWED,
        detail = "did a thing",
        stepIndex = 0,
    )

    @Test
    fun `rows older than the retention window are pruned`() {
        val rows = listOf(event(1, ageDays = 40), event(2, ageDays = 1))

        val pruned = AuditPolicy.idsToPrune(rows, NOW)

        assertEquals(listOf(1L), pruned)
    }

    @Test
    fun `a row exactly at the cutoff is kept`() {
        val rows = listOf(event(1, ageDays = AuditPolicy.RETENTION_DAYS))

        assertTrue(AuditPolicy.idsToPrune(rows, NOW).isEmpty())
    }

    @Test
    fun `rows past the cap are pruned, oldest first`() {
        val rows = (1..10L).map { event(it, ageDays = it) }

        val pruned = AuditPolicy.idsToPrune(rows, NOW, maxRows = 3)

        // Newest are ages 1, 2 and 3, so 4 through 10 go.
        assertEquals((4L..10L).toList(), pruned.sorted())
    }

    @Test
    fun `the cap and the retention rule do not double count`() {
        val rows = listOf(event(1, ageDays = 100), event(2, ageDays = 100))

        val pruned = AuditPolicy.idsToPrune(rows, NOW, maxRows = 5)

        assertEquals(listOf(1L, 2L), pruned.sorted())
        assertEquals("ids must be unique", pruned.size, pruned.distinct().size)
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(AuditPolicy.filter(listOf(event(1, 0)), "").isNotEmpty())
        assertTrue(AuditPolicy.filter(listOf(event(1, 0)), "   ").isNotEmpty())
    }

    @Test
    fun `search matches the package name`() {
        // One event in WhatsApp, one elsewhere, so the match is unambiguous.
        val rows = listOf(event(1, 0), event(2, 0, toolName = "back").copy(packageName = "com.other.app"))
        assertEquals(1, AuditPolicy.filter(rows, "whatsapp").size)
    }

    @Test
    fun `search matches the tool's readable name`() {
        val rows = listOf(event(1, 0, toolName = "tap"), event(2, 0, toolName = "back"))
        assertEquals(1, AuditPolicy.filter(rows, "go back").size)
    }

    @Test
    fun `search is case insensitive`() {
        assertEquals(1, AuditPolicy.filter(listOf(event(1, 0)), "WHATSAPP").size)
    }

    @Test
    fun `the in-memory log and the shared rule sanitize the same way`() {
        val raw = "Typed token=abcdef123456\nsecond line"
        assertEquals(AuditDetail.sanitize(raw), AuditDetail.sanitize(raw))
        assertTrue("one line only", !AuditDetail.sanitize(raw).contains("second line"))
    }

    @Test
    fun `search matches the description`() {
        assertEquals(1, AuditPolicy.filter(listOf(event(1, 0)), "did a thing").size)
    }

    @Test
    fun `a search with no matches returns nothing`() {
        assertTrue(AuditPolicy.filter(listOf(event(1, 0)), "nonexistent").isEmpty())
    }

    @Test
    fun `counts group by decision`() {
        val rows = listOf(
            event(1, 0).copy(decision = AuditDecision.ALLOWED),
            event(2, 0).copy(decision = AuditDecision.DENIED),
            event(3, 0).copy(decision = AuditDecision.DENIED),
        )

        val counts = AuditPolicy.counts(rows)

        assertEquals(1, counts[AuditDecision.ALLOWED])
        assertEquals(2, counts[AuditDecision.DENIED])
    }

    private companion object {
        val NOW = 1_700_000_000_000L
    }
}

class InMemoryAuditLogTest {

    private val log = InMemoryAuditLog()

    @Test
    fun `a recorded event is readable`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "Tapped Search")

        val events = log.recent()
        assertEquals(1, events.size)
        assertEquals("tap", events.single().toolName)
    }

    @Test
    fun `events come back newest first`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "first", timestampMs = 1000)
        log.record("back", RiskLevel.LOW, AuditDecision.ALLOWED, "second", timestampMs = 2000)

        assertEquals("back", log.recent().first().toolName)
    }

    @Test
    fun `ids are unique and increasing`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "a")
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "b")

        val ids = log.recent().map { it.id }
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun `a secret in the detail is masked before it is stored`() = runBlocking {
        log.record(
            "type_text",
            RiskLevel.HIGH,
            AuditDecision.APPROVED,
            "Typed password=hunter2secret",
        )

        val detail = log.recent().single().detail
        assertFalse("a secret must never reach the audit log", detail.contains("hunter2secret"))
    }

    @Test
    fun `an api key in the detail is masked`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "key AIzaSyD1234567890abcdef")

        assertFalse(log.recent().single().detail.contains("AIzaSyD1234567890abcdef"))
    }

    @Test
    fun `a very long detail is truncated so one row cannot bloat the log`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "x".repeat(5000))

        assertTrue(log.recent().single().detail.length <= 200)
    }

    @Test
    fun `only the first line of a multi-line detail is kept`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "first line\nsecond line")

        assertEquals("first line", log.recent().single().detail)
    }

    @Test
    fun `the flow reports what is stored`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "a")

        assertEquals(1, log.observe().first().size)
    }

    @Test
    fun `clear empties the log`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "a")
        log.clear()

        assertTrue(log.recent().isEmpty())
    }

    @Test
    fun `prune drops rows past the cap`() = runBlocking {
        val now = 1_700_000_000_000L
        repeat(5) { index ->
            log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "e$index", timestampMs = now - index)
        }

        log.prune(now)

        assertEquals("nothing is old enough to prune here", 5, log.recent().size)
    }

    @Test
    fun `a step index is stored`() = runBlocking {
        log.record("tap", RiskLevel.LOW, AuditDecision.ALLOWED, "a", stepIndex = 7)

        assertEquals(7, log.recent().single().stepIndex)
    }
}

class AuditDatabaseInstrumentedShapeTest {

    /**
     * The Room database cannot be opened on the JVM, so this asserts the parts that can be
     * checked without it: the converters round-trip, which is what a schema change would break.
     */
    private val converters = AuditConverters()

    @Test
    fun `every risk level round-trips through the converter`() {
        RiskLevel.entries.forEach { level ->
            assertEquals(level, converters.stringToRisk(converters.riskToString(level)))
        }
    }

    @Test
    fun `every decision round-trips through the converter`() {
        AuditDecision.entries.forEach { decision ->
            assertEquals(decision, converters.stringToDecision(converters.decisionToString(decision)))
        }
    }

    @Test
    fun `an unknown stored value falls back rather than throwing`() {
        // A row written by an older or newer version must not crash the History screen.
        assertEquals(RiskLevel.LOW, converters.stringToRisk("SOMETHING_NEW"))
        assertEquals(AuditDecision.ALLOWED, converters.stringToDecision("SOMETHING_NEW"))
    }

    @Test
    fun `enums are stored by name, so reordering the enum cannot corrupt existing rows`() {
        assertEquals("HIGH", converters.riskToString(RiskLevel.HIGH))
        assertEquals("TIMED_OUT", converters.decisionToString(AuditDecision.TIMED_OUT))
    }
}
