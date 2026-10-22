package com.phoneagent.safety

import java.net.URI
import java.net.URLEncoder

/**
 * The parts of an intent the validator needs. The Android layer converts a real `Intent`
 * into this, so the policy is testable without an emulator.
 */
data class IntentRequest(
    val action: String,
    val uri: String? = null,
    val packageName: String? = null,
    val mimeType: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Checks an intent against the policy before anything is started.
 *
 * Every check is a reason to refuse, and the reason is written for the model to read and act
 * on ("WhatsApp is not installed" is more useful than "failed"). The last line of defence is
 * that the resolved package is checked against the denylist: an allowed action pointed at a
 * banking app is still refused.
 */
class IntentValidator(
    private val policy: IntentPolicy,
    private val denylist: PackageDenylist,
) {

    fun validate(request: IntentRequest, resolvedPackage: String?): IntentVerdict {
        val action = request.action.trim()
        val uri = request.uri?.trim()?.takeIf { it.isNotEmpty() }

        val actionDecision = policy.classifyAction(action)
        if (actionDecision is IntentDecision.Blocked) {
            return blocked(request, actionDecision.reason, resolvedPackage)
        }

        // An explicitly named package is checked even before resolving, so the refusal
        // explains the real problem instead of "no app found".
        val namedPackage = request.packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (namedPackage != null && denylist.isDenied(namedPackage)) {
            return blocked(
                request,
                "PhoneAgent does not act inside ${denylist.matchingEntry(namedPackage)}",
                resolvedPackage,
            )
        }

        if (uri != null) {
            if (uri.length > policy.maxUriLength) {
                return blocked(request, "Link is too long", resolvedPackage)
            }
            val scheme = schemeOf(uri)
            val schemeDecision = policy.classifyUriScheme(scheme)
            if (schemeDecision is IntentDecision.Blocked) {
                return blocked(request, schemeDecision.reason, resolvedPackage)
            }
            if (scheme != null && scheme.lowercase() in policy.blockedUriSchemes) {
                return blocked(request, "$scheme: links are not allowed", resolvedPackage)
            }
            if (!isWellFormedUri(uri, scheme)) {
                return blocked(request, "That link is not valid", resolvedPackage)
            }
        }

        if (request.extras.keys.any { it == "android.intent.extra.BCC" || it == "bcc" }) {
            return blocked(request, "Blind-copy recipients are not allowed", resolvedPackage)
        }
        val extrasSize = request.extras.entries.sumOf { it.key.length + it.value.length }
        if (extrasSize > policy.maxExtraBytes) {
            return blocked(request, "Too much extra data", resolvedPackage)
        }

        if (resolvedPackage != null && denylist.isDenied(resolvedPackage)) {
            return blocked(
                request,
                "PhoneAgent does not act inside ${denylist.matchingEntry(resolvedPackage)}",
                resolvedPackage,
            )
        }

        // Media/VIEW intents must carry a target; an empty VIEW opens a chooser and wastes a step.
        if (action == ACTION_VIEW && uri == null && namedPackage == null) {
            return blocked(request, "Nothing to open", resolvedPackage)
        }

        val risk = actionDecision.level
        return IntentVerdict(
            decision = IntentDecision.Allowed(risk),
            action = action,
            uri = uri,
            resolvedPackage = resolvedPackage,
            riskLevel = risk,
            reason = if (risk == RiskLevel.MEDIUM) {
                "Allowed, will be logged"
            } else {
                "Allowed"
            },
        )
    }

    private fun blocked(
        request: IntentRequest,
        reason: String,
        resolvedPackage: String?,
    ) = IntentVerdict(
        decision = IntentDecision.Blocked(reason),
        action = request.action,
        uri = request.uri,
        resolvedPackage = resolvedPackage,
        riskLevel = RiskLevel.BLOCKED,
        reason = reason,
    )

    private fun schemeOf(uri: String): String? = runCatching {
        URI(uri).scheme
    }.getOrNull() ?: uri.substringBefore(':', "").takeIf { it.isNotEmpty() }

    /**
     * A scheme-less string is treated as malformed: `VIEW` with "www.example.com" resolves to
     * nothing useful, and accepting it would hide a typo behind a confusing failure.
     */
    private fun isWellFormedUri(uri: String, scheme: String?): Boolean {
        if (scheme.isNullOrBlank()) return false
        if (scheme.equals("tel", true) || scheme.equals("sms", true) || scheme.equals("smsto", true)) {
            val body = uri.substringAfter(':')
            return body.isNotBlank() && body.any { it.isDigit() }
        }
        if (scheme.equals("mailto", true)) {
            return uri.substringAfter(':').substringBefore('?').contains('@')
        }
        return uri.length > scheme.length + 1
    }

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"

        /**
         * URL-encodes text for a `wa.me`-style prefill. `URLEncoder` is form encoding, which
         * uses `+` for spaces; WhatsApp and browsers expect `%20`, so that is corrected here.
         * Devanagari survives this unchanged once encoded as UTF-8.
         */
        fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
