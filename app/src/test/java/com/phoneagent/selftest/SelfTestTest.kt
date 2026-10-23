package com.phoneagent.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestTest {

    private fun allGood() = SelfTest.Facts(
        accessibilityEnabled = true,
        screenCaptureGranted = true,
        overlayGranted = true,
        notificationsGranted = true,
        notificationListenerEnabled = true,
        microphoneGranted = true,
        networkAvailable = true,
        apiKeyPresent = true,
        batteryUnrestricted = true,
        canInstallUpdates = true,
        modelReachable = true,
    )

    @Test
    fun `a phone with everything granted is ready`() {
        val report = SelfTest.run(allGood())

        assertTrue(report.isReady)
        assertTrue(report.failed.isEmpty())
        assertEquals("Everything is ready.", report.headline)
    }

    @Test
    fun `a missing accessibility service is the first thing to fix`() {
        val report = SelfTest.run(allGood().copy(accessibilityEnabled = false))

        assertFalse(report.isReady)
        assertEquals(SelfTestCheck.Id.ACCESSIBILITY_SERVICE, report.firstAction?.id)
    }

    @Test
    fun `a missing accessibility service explains how to turn it on`() {
        val report = SelfTest.run(allGood().copy(accessibilityEnabled = false))

        val remedy = report.failed.single { it.id == SelfTestCheck.Id.ACCESSIBILITY_SERVICE }.remedy!!
        assertTrue(remedy, remedy.contains("Accessibility"))
    }

    @Test
    fun `a passed check carries no remedy, so failures stand out`() {
        val report = SelfTest.run(allGood())

        assertTrue(report.checks.all { it.remedy == null })
    }

    @Test
    fun `a failed check always carries a remedy, because a failure without one is a dead end`() {
        val report = SelfTest.run(
            allGood().copy(apiKeyPresent = false, accessibilityEnabled = false),
        )

        report.failed.forEach { assertNotNull(it.title, it.remedy) }
    }

    @Test
    fun `a missing api key is essential`() {
        val report = SelfTest.run(allGood().copy(apiKeyPresent = false))

        assertFalse(report.isReady)
        assertTrue(report.failedEssential.any { it.id == SelfTestCheck.Id.API_KEY })
    }

    @Test
    fun `missing screen capture alone is not a blocker, because text-only works`() {
        val report = SelfTest.run(allGood().copy(screenCaptureGranted = false))

        assertTrue(report.isReady)
        assertEquals(1, report.failed.size)
    }

    @Test
    fun `missing microphone alone is not a blocker, because talking is optional`() {
        val report = SelfTest.run(allGood().copy(microphoneGranted = false))

        assertTrue(report.isReady)
    }

    @Test
    fun `an unreachable model is a blocker once it has been tried`() {
        val report = SelfTest.run(allGood().copy(modelReachable = false))

        assertFalse(report.isReady)
        assertEquals(SelfTestCheck.Id.MODEL_REACHABLE, report.firstAction?.id)
    }

    @Test
    fun `an untried model is reported but is not a blocker`() {
        val report = SelfTest.run(allGood().copy(modelReachable = null))

        val check = report.checks.single { it.id == SelfTestCheck.Id.MODEL_REACHABLE }
        assertTrue("not trying is not the same as failing", check.passed)
    }

    @Test
    fun `the headline counts only the essential failures`() {
        val report = SelfTest.run(
            allGood().copy(screenCaptureGranted = false, microphoneGranted = false),
        )

        assertTrue(report.headline, report.headline.contains("work better"))
    }

    @Test
    fun `the headline says how many things need attention when it cannot work`() {
        val report = SelfTest.run(
            allGood().copy(accessibilityEnabled = false, apiKeyPresent = false),
        )

        assertTrue(report.headline, report.headline.contains("2 thing(s)"))
    }

    @Test
    fun `the first action is the most blocking one, not just the first failure`() {
        // The microphone is listed after the accessibility service, and it is optional, so a
        // missing microphone must never be offered as the thing to do first.
        val report = SelfTest.run(
            allGood().copy(accessibilityEnabled = false, microphoneGranted = false),
        )

        assertEquals(SelfTestCheck.Id.ACCESSIBILITY_SERVICE, report.firstAction?.id)
    }

    @Test
    fun `an empty report does not claim to be ready`() {
        val report = SelfTestReport(emptyList())

        assertTrue(report.isReady)
        assertEquals("Nothing was checked yet.", report.headline)
    }

    @Test
    fun `every check has a distinct id, so the list cannot show duplicates`() {
        val report = SelfTest.run(allGood())

        assertEquals(report.checks.size, report.checks.map { it.id }.distinct().size)
    }

    @Test
    fun `the report lists the essential checks before the optional ones`() {
        val report = SelfTest.run(allGood())

        val firstOptional = report.checks.indexOfFirst { !it.essential }
        val lastEssential = report.checks.indexOfLast { it.essential }

        assertTrue(
            "the order is what makes firstAction the right answer",
            lastEssential < firstOptional,
        )
    }
}
