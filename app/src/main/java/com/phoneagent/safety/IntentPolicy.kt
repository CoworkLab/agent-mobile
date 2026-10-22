package com.phoneagent.safety

/**
 * The intent policy from `assets/intent_policy.json`.
 *
 * The rule that matters most: **default deny**. An action that is not explicitly allowed is
 * blocked, so a new or obfuscated action cannot slip through. An allowed intent may only
 * open a screen or prefill it; it never completes a risky action.
 */
data class IntentPolicy(
    val allowedLow: Set<String>,
    val allowedMedium: Set<String>,
    val alwaysBlocked: Set<String>,
    val allowedUriSchemes: Set<String>,
    val blockedUriSchemes: Set<String>,
    val allowedSettingsPrefix: String = "android.settings.",
    val maxUriLength: Int = 2048,
    val maxExtraBytes: Int = 8192,
) {

    /** True when the action is a settings screen, which is always a safe destination. */
    fun isSettingsAction(action: String): Boolean =
        action.startsWith(allowedSettingsPrefix)

    /**
     * Classifies an action without looking at its URI. The URI is checked separately so the
     * audit log can say exactly which part was refused.
     */
    fun classifyAction(action: String): IntentDecision = when {
        action.isBlank() -> IntentDecision.Blocked("No action given")
        action in alwaysBlocked -> IntentDecision.Blocked("$action is never allowed")
        isSettingsAction(action) -> IntentDecision.Allowed(RiskLevel.LOW)
        action in allowedLow -> IntentDecision.Allowed(RiskLevel.LOW)
        action in allowedMedium -> IntentDecision.Allowed(RiskLevel.MEDIUM)
        else -> IntentDecision.Blocked("Unknown action $action (default deny)")
    }

    fun classifyUriScheme(scheme: String?): IntentDecision {
        if (scheme.isNullOrBlank()) return IntentDecision.Allowed(RiskLevel.LOW)
        val lower = scheme.lowercase()
        return when {
            lower in blockedUriSchemes ->
                IntentDecision.Blocked("$lower: links are not allowed; use content: or https:")
            lower in allowedUriSchemes -> IntentDecision.Allowed(RiskLevel.LOW)
            else -> IntentDecision.Blocked("Unknown link type $lower: (default deny)")
        }
    }

    companion object {
        val EMPTY = IntentPolicy(
            allowedLow = emptySet(),
            allowedMedium = emptySet(),
            alwaysBlocked = emptySet(),
            allowedUriSchemes = emptySet(),
            blockedUriSchemes = emptySet(),
        )
    }
}

sealed interface IntentDecision {
    val level: RiskLevel
    val reason: String

    data class Allowed(override val level: RiskLevel) : IntentDecision {
        override val reason: String get() = "Allowed"
    }

    data class Blocked(override val reason: String) : IntentDecision {
        override val level: RiskLevel get() = RiskLevel.BLOCKED
    }
}

/** The outcome of validating one intent, with the detail the audit log needs. */
data class IntentVerdict(
    val decision: IntentDecision,
    val action: String,
    val uri: String?,
    val resolvedPackage: String?,
    val riskLevel: RiskLevel,
    val reason: String,
) {
    val allowed: Boolean get() = riskLevel != RiskLevel.BLOCKED
}
