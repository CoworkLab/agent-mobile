package com.phoneagent.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.service.PhoneAgentAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Whether the accessibility service is up, treated as a hard requirement or a skip.
 *
 * These tests need the service, and there is no way to provide it from inside the process: it
 * is the system that binds it. On a developer's machine and on a fork without the emulator
 * setup the honest answer is to skip, which is what happens by default.
 *
 * On the E2E job the service is set up on purpose, so a skip there would hide a real
 * regression. That job passes `requireAccessibility=true` and these become failures. The
 * distinction matters because a skip reports as green: nineteen of thirty tests were skipped
 * on a passing run and the build said nothing.
 */
object ServiceRequirement {

    private const val ARGUMENT = "requireAccessibility"

    private val required: Boolean
        get() = InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"

    fun requireRunning() {
        val running = PhoneAgentAccessibilityService.isRunning()
        if (required) {
            assertTrue(
                "The accessibility service is not running and this run requires it. " +
                    "scripts/prepare-device.sh is supposed to start it; check the " +
                    "`dumpsys accessibility` output it prints.",
                running,
            )
        } else {
            assumeTrue("Accessibility service not enabled; see e2e-emulator.yml", running)
        }
    }
}
