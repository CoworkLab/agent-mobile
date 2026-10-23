package com.phoneagent.testing

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The test runner, which keeps UiAutomation from suppressing the accessibility service.
 *
 * This is the reason the device tests spent a long time skipping. The scenarios drive the testbed
 * through UiAutomator, and a `UiAutomation` connection switches off every other accessibility
 * service on the device while it is held. That is deliberate — it keeps an automated run from
 * being disturbed — but it means the service under test is destroyed the moment the runner sets
 * up, and no amount of enabling it from the outside brings it back. The accessibility dump tells
 * the story: the component is in `Enabled services`, and `Bound services` is empty.
 *
 * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` opts out. It has to be set when the instance is
 * created and the instance is cached, so it is set here, before the tests can ask for one, and
 * every later `getUiAutomation()` — including the one UiAutomator makes — gets this same
 * connection. This is the fix the UiAutomator2 server in Appium uses.
 *
 * It also has to happen before `super.onCreate`: the parent starts the instrumentation, and the
 * suppression would otherwise be applied on the connection it makes on the way through.
 *
 * It is asked for at both points where the framework lets a runner run code before the tests,
 * because which of them is early enough depends on when the framework first reaches for a
 * connection. The instance is cached after the first call, so the second is a no-op; if the
 * first did not take, the tests say so loudly rather than the run quietly losing the service.
 */
class PhoneAgentTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        keepAccessibilityServicesAlive()
        super.onCreate(arguments)
    }

    override fun onStart() {
        keepAccessibilityServicesAlive()
        super.onStart()
    }

    private fun keepAccessibilityServicesAlive() {
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
}
