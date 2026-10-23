package com.phoneagent.selftest

import com.phoneagent.permissions.PermissionId

/**
 * Which system screen to open for each failing check, and how to write the report out.
 *
 * Pure functions of their inputs, so both are testable without a device. The mapping is a
 * deliberate whitelist: a check that has no system screen returns null and the button does
 * nothing, which is better than opening the wrong screen and confusing the person.
 */
object SelfTestFixes {

    fun permissionFor(id: SelfTestCheck.Id): PermissionId? = when (id) {
        SelfTestCheck.Id.ACCESSIBILITY_SERVICE -> PermissionId.ACCESSIBILITY
        SelfTestCheck.Id.OVERLAY -> PermissionId.OVERLAY
        SelfTestCheck.Id.NOTIFICATIONS -> PermissionId.NOTIFICATIONS
        SelfTestCheck.Id.NOTIFICATION_LISTENER -> PermissionId.NOTIFICATION_LISTENER
        SelfTestCheck.Id.MICROPHONE -> PermissionId.MICROPHONE
        SelfTestCheck.Id.BATTERY_UNRESTRICTED -> PermissionId.BATTERY_OPTIMIZATION
        SelfTestCheck.Id.CAN_INSTALL_UPDATES -> PermissionId.INSTALL_PACKAGES
        // The key is entered in Setup and the network is a phone setting; neither has a
        // permission screen, so the button is simply not offered.
        SelfTestCheck.Id.API_KEY,
        SelfTestCheck.Id.NETWORK,
        SelfTestCheck.Id.SCREEN_CAPTURE,
        SelfTestCheck.Id.MODEL_REACHABLE,
        -> null
    }

    /**
     * The report as text, for pasting into a message when asking for help.
     *
     * Failures come first and each one carries its remedy, because the person sharing this
     * wants the reader to see the problem, not to scroll past what already works.
     */
    fun reportText(report: SelfTestReport): String = buildString {
        appendLine("PhoneAgent self-test")
        appendLine(report.headline)
        appendLine()
        report.failed.forEach { check ->
            appendLine("NEEDS ATTENTION: ${check.title}")
            check.remedy?.let { appendLine("  $it") }
        }
        if (report.failed.isEmpty()) appendLine("No problems found.")
        appendLine()
        appendLine("Passed:")
        report.passed.forEach { appendLine("  ${check(it)}") }
    }

    private fun check(c: SelfTestCheck) = "${c.title}: ${if (c.passed) "OK" else "needs attention"}"
}
