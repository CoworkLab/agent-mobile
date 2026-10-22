package com.phoneagent.safety

/**
 * The single place that decides whether an action may proceed.
 *
 * Everything that acts goes through here: single tools, each step of a batch, and intents.
 * The point of one entry point is that a rule added here protects every path, rather than
 * needing to be remembered in each caller.
 */
class SafetyGuard(
    private val classifier: RiskClassifier,
    private val denylist: PackageDenylist,
) {

    /** Assesses a tool against the node it will touch. */
    fun assessToolAndTarget(
        toolName: String,
        toolRisk: RiskLevel,
        target: RiskTarget?,
    ): RiskAssessment = classifier.assessToolAndTarget(toolName, toolRisk, target)

    /** Refuses anything inside a restricted app, whatever the action. */
    fun isPackageAllowed(packageName: String?): Boolean = !denylist.isDenied(packageName)

    fun packageRefusalReason(packageName: String?): String? {
        val entry = denylist.matchingEntry(packageName) ?: return null
        return "PhoneAgent does not act inside restricted apps ($entry)."
    }
}
