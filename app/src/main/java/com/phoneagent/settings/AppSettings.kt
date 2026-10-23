package com.phoneagent.settings

import com.phoneagent.agent.LoopLimits
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.util.LogLevel

/**
 * Everything the person can change.
 *
 * A data class with defaults, so a new setting never breaks an existing stored file: an
 * absent key simply takes the default. That is the whole reason settings are stored as
 * individual keys rather than one serialised blob.
 *
 * Every value is clamped on the way in. This matters more than it looks: a step limit of
 * 100000 would let the agent run for hours on a metered connection, and a zero-second timeout
 * would make every action fail. The bounds live here, next to the fields, so they cannot drift
 * apart from the values they protect.
 */
data class AppSettings(
    /** The most steps one task may take. */
    val maxSteps: Int = LoopLimits.DEFAULT.maxSteps,

    /** The longest one task may run, in minutes. */
    val maxTaskMinutes: Int = 10,

    /** How the agent looks at the screen by default. */
    val perceptionMode: PerceptionMode = PerceptionMode.TREE_PLUS_SCREENSHOT,

    /** Whether the agent speaks its questions and summaries. */
    val speechEnabled: Boolean = true,

    /** The language for speech and for the model's replies, as a BCP-47 tag. */
    val languageTag: String = "en-IN",

    /** Apps the agent must never act in, as patterns. */
    val deniedPackages: List<String> = PackageDenylist.DEFAULT_PATTERNS,

    /** How much is written to the log viewer. */
    val logLevel: LogLevel = LogLevel.INFO,

    /** Whether a finished task of several steps may produce a draft skill. */
    val draftSkills: Boolean = true,

    /** Whether the agent asks before acting on anything risky. */
    val confirmRiskyActions: Boolean = true,

    /** Whether to check for a newer version at launch. */
    val checkForUpdates: Boolean = true,
) {
    /** The loop limits this configuration implies. */
    val loopLimits: LoopLimits
        get() = LoopLimits(
            maxSteps = maxSteps,
            maxDurationMs = maxTaskMinutes * 60_000L,
        )

    /** The settings after clamping, so no stored value can be out of range. */
    fun sanitized(): AppSettings = copy(
        maxSteps = maxSteps.coerceIn(MIN_STEPS, MAX_STEPS),
        maxTaskMinutes = maxTaskMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES),
        languageTag = languageTag.takeIf { it in SUPPORTED_LANGUAGES } ?: DEFAULT_LANGUAGE,
        deniedPackages = deniedPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct(),
        logLevel = logLevel,
    )

    /** True when the settings differ from the defaults, for the "reset" button. */
    val isDefault: Boolean get() = this == AppSettings()

    companion object {
        /** A limit of one step would never finish anything, so the floor is higher. */
        const val MIN_STEPS = 5

        /**
         * Five hundred steps is far more than any real task needs and still bounds the run.
         * The point of a maximum is to protect a metered connection and a battery, not to
         * express a preference.
         */
        const val MAX_STEPS = 500

        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 120

        /** The languages the speech recogniser and the interface support. */
        val SUPPORTED_LANGUAGES = listOf("hi-IN", "en-IN", "en-US")

        const val DEFAULT_LANGUAGE = "en-IN"
    }
}
