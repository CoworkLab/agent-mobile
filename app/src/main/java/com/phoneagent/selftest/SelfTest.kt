package com.phoneagent.selftest

/**
 * One thing the app needs in order to work, and whether it currently has it.
 *
 * The self-test exists because the failure modes of an accessibility agent are quiet. If the
 * service is off, taps go nowhere and the agent simply looks confused; if the network is
 * missing, the model call fails and it looks like a bug in the agent. Neither shows an error
 * a person would recognise. This screen turns each of those into a sentence and a button.
 */
data class SelfTestCheck(
    val id: Id,
    val title: String,
    val passed: Boolean,
    /** What to do about it, in plain words. Null when it passed. */
    val remedy: String? = null,
    /** True when the app cannot work at all without this. */
    val essential: Boolean = true,
) {
    enum class Id {
        ACCESSIBILITY_SERVICE,
        SCREEN_CAPTURE,
        OVERLAY,
        NOTIFICATIONS,
        NOTIFICATION_LISTENER,
        MICROPHONE,
        NETWORK,
        API_KEY,
        BATTERY_UNRESTRICTED,
        CAN_INSTALL_UPDATES,
        MODEL_REACHABLE,
    }
}

/** The result of a whole self-test run. */
data class SelfTestReport(
    val checks: List<SelfTestCheck>,
) {
    val passed: List<SelfTestCheck> get() = checks.filter { it.passed }
    val failed: List<SelfTestCheck> get() = checks.filterNot { it.passed }
    val failedEssential: List<SelfTestCheck> get() = failed.filter { it.essential }

    /** True when nothing essential is missing. */
    val isReady: Boolean get() = failedEssential.isEmpty()

    /**
     * The single most useful next action.
     *
     * A person looking at a list of failures needs one thing to do, not ten. The first failed
     * essential check is the one that unblocks the most, because the checks are ordered from
     * "cannot work at all" to "would work better".
     */
    val firstAction: SelfTestCheck? get() = failedEssential.firstOrNull()

    /** A one-line verdict for the top of the screen. */
    val headline: String get() = when {
        checks.isEmpty() -> "Nothing was checked yet."
        isReady && failed.isEmpty() -> "Everything is ready."
        isReady -> "Ready, with ${failed.size} thing(s) that would make it work better."
        else -> "${failedEssential.size} thing(s) need attention before PhoneAgent can work."
    }
}

/**
 * Builds the report from the raw facts.
 *
 * Deliberately a pure function of its inputs: every check is decided from a boolean the caller
 * gathered, so the ordering, the wording and the "what next" logic can be tested without a
 * device. The screen only gathers the booleans.
 */
object SelfTest {

    data class Facts(
        val accessibilityEnabled: Boolean,
        val screenCaptureGranted: Boolean,
        val overlayGranted: Boolean,
        val notificationsGranted: Boolean,
        val notificationListenerEnabled: Boolean,
        val microphoneGranted: Boolean,
        val networkAvailable: Boolean,
        val apiKeyPresent: Boolean,
        val batteryUnrestricted: Boolean,
        val canInstallUpdates: Boolean,
        /** Null when the model was not reached, true when it answered, false when it refused. */
        val modelReachable: Boolean? = null,
    )

    fun run(facts: Facts): SelfTestReport = SelfTestReport(
        checks = listOf(
            check(
                SelfTestCheck.Id.ACCESSIBILITY_SERVICE,
                "Permission to read and tap the screen",
                facts.accessibilityEnabled,
                "Open Settings, then Accessibility, then PhoneAgent, and turn it on.",
            ),
            check(
                SelfTestCheck.Id.OVERLAY,
                "Permission to show its buttons over other apps",
                facts.overlayGranted,
                "Allow \"display over other apps\" so the stop button stays reachable.",
            ),
            check(
                SelfTestCheck.Id.NOTIFICATIONS,
                "Permission to show notifications",
                facts.notificationsGranted,
                "Allow notifications so you can see progress and stop a task.",
            ),
            check(
                SelfTestCheck.Id.API_KEY,
                "A Gemini API key",
                facts.apiKeyPresent,
                "Add your key in Setup. Without it PhoneAgent cannot think.",
            ),
            check(
                SelfTestCheck.Id.NETWORK,
                "An internet connection",
                facts.networkAvailable,
                "Connect to Wi-Fi or mobile data. PhoneAgent cannot think offline.",
            ),
            check(
                SelfTestCheck.Id.SCREEN_CAPTURE,
                "Permission to take a screenshot",
                facts.screenCaptureGranted,
                "Grant screen capture, or set PhoneAgent to read the screen as text only.",
                essential = false,
            ),
            check(
                SelfTestCheck.Id.NOTIFICATION_LISTENER,
                "Permission to read notifications",
                facts.notificationListenerEnabled,
                "Optional. Helps it notice one-time codes and progress from other apps.",
                essential = false,
            ),
            check(
                SelfTestCheck.Id.MICROPHONE,
                "Permission to use the microphone",
                facts.microphoneGranted,
                "Optional. Only needed if you want to talk to PhoneAgent.",
                essential = false,
            ),
            check(
                SelfTestCheck.Id.BATTERY_UNRESTRICTED,
                "Exempt from battery saving",
                facts.batteryUnrestricted,
                "Optional, but without it Android may stop long tasks midway.",
                essential = false,
            ),
            check(
                SelfTestCheck.Id.CAN_INSTALL_UPDATES,
                "Permission to install updates",
                facts.canInstallUpdates,
                "Optional. Needed only for the in-app updater.",
                essential = false,
            ),
            check(
                SelfTestCheck.Id.MODEL_REACHABLE,
                "The model answered",
                facts.modelReachable != false,
                "The model could not be reached. Check the key and the connection.",
                essential = facts.modelReachable != null,
            ),
        ),
    )

    /**
     * A check whose message is written once.
     *
     * A passed check carries no remedy: showing "how to fix this" next to something that works
     * is noise, and it makes the failures harder to spot.
     */
    private fun check(
        id: SelfTestCheck.Id,
        title: String,
        passed: Boolean,
        remedy: String,
        essential: Boolean = true,
    ) = SelfTestCheck(
        id = id,
        title = title,
        passed = passed,
        remedy = remedy.takeIf { !passed },
        essential = essential,
    )
}
