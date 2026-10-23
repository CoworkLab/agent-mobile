package com.phoneagent.testing

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.service.PhoneAgentAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Whether the accessibility service is up, treated as a hard requirement or a skip.
 *
 * The service is the agent's hands and eyes, and these tests are worthless without it, but
 * getting it running inside a `connectedAndroidTest` run takes care. Device setup
 * (`scripts/prepare-device.sh`) enables it and confirms the system binds it, and then the test
 * runner reinstalls the app just before the tests start. That install kills the process holding
 * the service and drops it from the enabled list, so by the first test the work is undone. The
 * accessibility dump at the end of a run shows this plainly: `Enabled services:{}`.
 *
 * There is no hook to run between that install and the first test, so the setup is repeated
 * here, from inside the run. This costs nothing when the service is already up, which is the
 * case on a developer's machine, and the tests that assert on what is on screen are left
 * undisturbed because the app is not started for them.
 *
 * When the service cannot be brought up: on the E2E job, which sets it up on purpose, that is a
 * failure, because a skip there would hide a real regression. Anywhere else it is a skip, so a
 * fork without an emulator does not show a red build. A skip reports as green, which is exactly
 * why nineteen of thirty tests once passed by not running.
 */
object ServiceRequirement {

    private const val ARGUMENT = "requireAccessibility"
    private const val TIMEOUT_MS = 30_000L
    private const val BIND_ATTEMPT_MS = 5_000L
    private const val POLL_MS = 250L

    private val required: Boolean
        get() = InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"

    fun requireRunning() {
        if (!PhoneAgentAccessibilityService.isRunning()) {
            bringTheServiceUp()
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

    /**
     * Starts the app and then enables the service, in that order.
     *
     * The order is the whole trick. The system does not bind the services of a stopped package,
     * so enabling first records the component but binds nothing — and once the app is started
     * nothing revisits the decision, because the setting has not changed again. Observed on the
     * E2E job: the write landed while the freshly reinstalled package was still stopped, the
     * activity came up a moment later, and the service stayed unbound for the whole run.
     */
    private fun bringTheServiceUp() {
        startTheAppToClearTheStoppedState()
        SystemClock.sleep(2_000)

        // The enable is written more than once on purpose. The system decides whether to bind
        // when the setting changes, so a write that lands while the package is still stopped is
        // recorded and then ignored; writing it again once the app is up gives the system a
        // fresh reason to look.
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            enableTheService()
            if (waitForService(BIND_ATTEMPT_MS)) return
        }
    }

    /** Writes the two settings that turn an installed component into a running service. */
    private fun enableTheService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = "${context.packageName}/${PhoneAgentAccessibilityService::class.java.name}"
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
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

    /** True once the service is up, false if it did not come up within [timeoutMs]. */
    private fun waitForService(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (PhoneAgentAccessibilityService.isRunning()) return true
            SystemClock.sleep(POLL_MS)
        }
        return PhoneAgentAccessibilityService.isRunning()
    }

    /**
     * Runs a shell command through the instrumentation, which has the shell's own permissions.
     * The command's output is drained so the pipe closes and the call does not block.
     */
    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes() }
        }
    }
}
