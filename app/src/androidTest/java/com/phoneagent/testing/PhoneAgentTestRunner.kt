package com.phoneagent.testing

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The test runner, which keeps UiAutomation from switching off the accessibility service.
 *
 * This is the reason the device tests spent a long time skipping. The scenarios drive the testbed
 * through UiAutomator, and a `UiAutomation` connection suppresses every other accessibility
 * service on the device while it is held. That is deliberate — an automated run should not fight
 * the user's accessibility tools — but it means the service under test is destroyed as soon as
 * the runner sets up. Enabling it again from the outside could never work: the connection that
 * suppressed it was still open, and `dumpsys accessibility` showed the component in
 * `Enabled services` with `Bound services` empty, and in `Crashed services` besides.
 *
 * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` opts out. Setting it once is not enough, because
 * `UiAutomation` is cached together with the flags it was created with: the framework and
 * UiAutomator both ask for one with no flags, and that call discards the flagged instance and
 * replaces it with a suppressing one. The logcat of the run that first exposed this shows the
 * two connections, the second with `flags=0`.
 *
 * So the flag is added to whatever flags are asked for, on both entry points. Every caller —
 * the framework, UiAutomator, the tests — then shares the one connection that does not suppress,
 * which is what the UiAutomator2 server in Appium does for the same reason.
 */
class PhoneAgentTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        // Before super.onCreate: the parent starts the instrumentation, and a connection made on
        // the way through would otherwise be the suppressing kind.
        getUiAutomation()
        super.onCreate(arguments)
    }

    override fun getUiAutomation(): UiAutomation =
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    override fun getUiAutomation(flags: Int): UiAutomation =
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
}
