package com.phoneagent.safety

/**
 * How dangerous an action is.
 *
 * Ordered from safe to forbidden; `max` picks the worst of several signals, so a benign tool
 * acting on a dangerous target is still dangerous.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED;

    fun max(other: RiskLevel): RiskLevel = if (other.ordinal > ordinal) other else this

    companion object {
        fun worst(vararg levels: RiskLevel): RiskLevel =
            levels.maxByOrNull { it.ordinal } ?: LOW
    }
}

/** What the guard decided, and why. The reason is shown to the user and written to the audit log. */
data class RiskAssessment(
    val level: RiskLevel,
    val reason: String,
    val matchedKeyword: String? = null,
) {
    val requiresUserApproval: Boolean get() = level == RiskLevel.HIGH
    val isBlocked: Boolean get() = level == RiskLevel.BLOCKED
}

/** A screen target the classifier can judge: the label a user would read before tapping. */
data class RiskTarget(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val packageName: String? = null,
    val isPasswordField: Boolean = false,
    val isEditable: Boolean = false,
)

/**
 * Decides whether an action needs confirmation, based on what it does and what it touches.
 *
 * The keyword lists live in `assets/safety_keywords.json` and are matched in English, Hindi
 * (Devanagari) and Hinglish. Matching is on word boundaries for Latin text so that "sending"
 * does not match "end", and on substring for Devanagari, which has no word separators.
 */
class RiskClassifier(
    private val keywords: KeywordSets,
    private val denylist: PackageDenylist,
) {

    /**
     * Assesses a target. The worst signal wins: a deny-listed package outranks everything,
     * then a BLOCKED keyword, then a HIGH keyword, then MEDIUM.
     */
    fun assess(
        toolName: String,
        target: RiskTarget?,
        toolRisk: RiskLevel = RiskLevel.LOW,
    ): RiskAssessment {
        val packageName = target?.packageName
        if (packageName != null && denylist.isDenied(packageName)) {
            return RiskAssessment(
                level = RiskLevel.BLOCKED,
                reason = "App is on the restricted list ($packageName)",
            )
        }

        // A password field is only touchable when the goal explicitly supplied the text and
        // the user approves; the guard cannot know the goal, so it always asks.
        if (target?.isPasswordField == true) {
            return RiskAssessment(
                level = RiskLevel.HIGH,
                reason = "This is a password field",
            )
        }

        val haystack = listOfNotNull(
            target?.text,
            target?.contentDescription,
            target?.viewId?.substringAfterLast('/'),
        ).joinToString(" ").lowercase()

        val blocked = keywords.firstBlockedMatch(haystack)
        if (blocked != null) {
            return RiskAssessment(
                level = RiskLevel.BLOCKED,
                reason = "This action is never allowed (\"$blocked\")",
                matchedKeyword = blocked,
            )
        }

        val high = keywords.firstHighMatch(haystack)
        if (high != null) {
            return RiskAssessment(
                level = RiskLevel.HIGH,
                reason = "This looks like it will \"$high\". Please confirm.",
                matchedKeyword = high,
            )
        }

        val medium = keywords.firstMediumMatch(haystack)
        if (medium != null) {
            return RiskAssessment(
                level = RiskLevel.MEDIUM,
                reason = "May change something (\"$medium\")",
                matchedKeyword = medium,
            )
        }

        return RiskAssessment(level = toolRisk, reason = "Looks safe")
    }

    /**
     * A tool can be dangerous regardless of its target: typing into a password field, or
     * finishing a task in a banking app. This combines the tool's own risk with the target's.
     */
    fun assessToolAndTarget(
        toolName: String,
        toolRisk: RiskLevel,
        target: RiskTarget?,
    ): RiskAssessment = assess(toolName, target, toolRisk)
}

/**
 * Detects attempts to make the agent follow instructions found on screen.
 *
 * Screen text is data, never instructions. Detection does not stop the run by itself: the
 * text is still passed to the model inside `<untrusted_screen_content>`, and a match is
 * logged and surfaced so the prompt rules and the user can act on it.
 */
class InjectionDetector(private val keywords: KeywordSets) {

    fun scan(text: String?): InjectionScan {
        if (text.isNullOrBlank()) return InjectionScan(clean = true, matches = emptyList())
        val lower = text.lowercase()
        val matches = keywords.injectionKeywords.filter { lower.contains(it) }
        return InjectionScan(clean = matches.isEmpty(), matches = matches)
    }

    /** Wraps untrusted text so the model can tell data from instructions. */
    fun wrapUntrusted(text: String): String =
        "<untrusted_screen_content>\n$text\n</untrusted_screen_content>"
}

data class InjectionScan(val clean: Boolean, val matches: List<String>)
