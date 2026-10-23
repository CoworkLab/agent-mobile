package com.phoneagent.testing

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.service.PhoneAgentAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Whether the accessibility service is up, treated as a hard requirement or a skip.
 *
 * These tests need the service, and only the system can bind it. Two things get in the way, and
 * both are handled here rather than in the workflow:
 *
 *  - A package in Android's stopped state has its services left unbound. The test runner
 *    reinstalls the app just before the tests, which puts it back in that state, so the service
 *    that `scripts/prepare-device.sh` confirmed as bound a moment earlier is gone by the time
 *    the first test asks for it.
 *  - Binding happens on the system's schedule, not ours, so even a service that is about to come
 *    up needs a moment.
 *
 * Starting the app clears the stopped state and the system then binds the enabled service, so
 * [requireRunning] does that and waits. It only does so when the service is not already up, which
 * keeps it out of the way of the tests that care about what is on screen.
 *
 * When the service cannot be brought up: on the E2E job, which sets it up on purpose, that is a
 * failure, because a skip there would hide a real regression. Anywhere else it is a skip, so a
 * fork without an emulator does not show a red build. A skip reports as green, which is exactly
 * why nineteen of thirty tests once passed by not running.
 */
object ServiceRequirement {

    private const val ARGUMENT = "requireAccessibility"
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 250L

    private val required: Boolean
        get() = InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"

    fun requireRunning() {
        if (!PhoneAgentAccessibilityService.isRunning()) {
            startTheAppToClearTheStoppedState()
            waitForService()
        }
        val running = PhoneAgentAccessibilityService.isRunning()
        if (required) {
            assertTrue(
                "The accessibility service is not running and this run requires it. " +
                    "scripts/prepare-device.sh enables it and prints the `dumpsys accessibility` " +
                    "output that shows whether it bound; check that output first.",
                running,
            )
        } else {
            assumeTrue("Accessibility service not enabled; see e2e-emulator.yml", running)
        }
    }

    /** Starting an activity clears the package's stopped state; the system then binds the service. */
    private fun startTheAppToClearTheStoppedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { context.startActivity(intent) }
    }

    private fun waitForService() {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (PhoneAgentAccessibilityService.isRunning()) return
            SystemClock.sleep(POLL_MS)
        }
    }
}
