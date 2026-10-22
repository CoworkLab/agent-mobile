package com.phoneagent.tools

import com.phoneagent.safety.IntentRequest
import com.phoneagent.safety.IntentValidator
import com.phoneagent.safety.IntentVerdict
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskLevel
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Starts an Android intent so a common job takes one step instead of eight.
 *
 * The rule from the design that this tool enforces: an intent may only **open or prefill**.
 * It never completes a risky action. Opening a WhatsApp chat with text filled in is one step;
 * pressing Send is a separate `tap`, which the guard classifies HIGH and asks about.
 *
 * Every call is validated first and then audited, whether it was allowed or refused. The
 * audit row is what makes "why did the agent open that?" answerable later.
 */
class LaunchIntentTool(
    private val validator: IntentValidator,
    private val launcher: IntentLauncher,
    private val auditLog: IntentAuditLog,
    private val logger: Logger,
    private val denylist: PackageDenylist,
) : Tool {

    override val name = "launch_intent"
    override val description =
        "Open an app screen directly with an Android intent: a web link, a map location, " +
            "an email or message composer, the dialer, a settings screen, an alarm or timer. " +
            "Faster and more reliable than tapping through the UI. It only opens the screen; " +
            "it never sends, pays or deletes."

    override val parametersSchema = schemaOf(
        mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "The intent action, for example android.intent.action.VIEW",
            ),
            "uri" to stringProperty(
                "The data URI, for example https://..., geo:..., mailto:..., tel:..., " +
                    "sms:..., or content://",
            ),
            "package" to stringProperty("Optional: the app that must handle it"),
            "mime_type" to stringProperty("Optional MIME type, for share intents"),
            "extras" to mapOf(
                "type" to "object",
                "description" to "Optional string extras, for example a prefilled message body",
                "additionalProperties" to mapOf("type" to "string"),
            ),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val action = call.string("action")?.trim()
            ?: return ToolResult.fail("action is required.")

        val request = IntentRequest(
            action = action,
            uri = call.string("uri"),
            packageName = call.string("package"),
            mimeType = call.string("mime_type"),
            extras = call.extrasMap(),
        )

        // Resolve first so the refusal can name the real problem. "WhatsApp is not installed"
        // tells the model to switch to the UI route; "no handler" would not.
        val resolution = launcher.resolve(request)

        if (resolution is IntentResolution.NoHandler) {
            audit(request, null, RiskLevel.BLOCKED, "no handler")
            return ToolResult.fail(
                "Nothing on this phone can handle that (${describeTarget(request)}). " +
                    "It may not be installed. Try doing it through the app's own screens.",
            )
        }

        val resolvedPackage = when (resolution) {
            is IntentResolution.Handled -> resolution.packageName
            is IntentResolution.Ambiguous -> resolution.candidates.first()
            is IntentResolution.NoHandler -> null
        }

        val verdict = validator.validate(request, resolvedPackage)
        if (!verdict.allowed) {
            audit(request, resolvedPackage, verdict.riskLevel, verdict.reason)
            logger.log(LogLevel.WARN, TAG, "Refused intent: ${verdict.reason}")
            return ToolResult.fail(verdict.reason)
        }

        if (resolution is IntentResolution.Ambiguous) {
            // Choosing between handlers is the user's call, not the agent's.
            val names = resolution.candidates.joinToString(", ")
            audit(request, resolvedPackage, verdict.riskLevel, "ambiguous: $names")
            return ToolResult.fail(
                "More than one app can open that: $names. Say which app you want, " +
                    "or use the package argument.",
            )
        }

        val result = launcher.start(request)
        return when (result) {
            is LaunchResult.Started -> {
                audit(request, resolvedPackage, verdict.riskLevel, "started")
                val note = if (verdict.riskLevel == RiskLevel.MEDIUM) " (logged)" else ""
                ToolResult.ok(
                    "Opened ${result.packageName}${describeTarget(request)}$note. " +
                        "Nothing was sent; the screen is only open.",
                    screenChanged = true,
                )
            }

            is LaunchResult.Blocked -> {
                audit(request, resolvedPackage, RiskLevel.BLOCKED, result.reason)
                ToolResult.fail(result.reason)
            }

            is LaunchResult.Failed -> {
                audit(request, resolvedPackage, verdict.riskLevel, "failed: ${result.reason}")
                ToolResult.fail("Could not open it: ${result.reason}")
            }
        }
    }

    private fun describeTarget(request: IntentRequest): String =
        request.uri?.let { " for $it" } ?: ""

    private suspend fun audit(
        request: IntentRequest,
        resolvedPackage: String?,
        risk: RiskLevel,
        decision: String,
    ) {
        auditLog.record(
            IntentAuditEntry(
                timestampMs = System.currentTimeMillis(),
                action = request.action,
                uri = request.uri,
                resolvedPackage = resolvedPackage,
                riskLevel = risk,
                decision = decision,
            ),
        )
    }

    private fun ToolCall.extrasMap(): Map<String, String> {
        val raw = args["extras"] as? Map<*, *> ?: return emptyMap()
        return raw.entries
            .mapNotNull { (key, value) -> (key as? String)?.let { it to value.toString() } }
            .toMap()
    }

    companion object {
        const val TAG = "LaunchIntent"
    }
}

/** The Android side of starting an intent, behind an interface so the tool is testable. */
interface IntentLauncher {
    suspend fun resolve(request: IntentRequest): IntentResolution
    suspend fun start(request: IntentRequest): LaunchResult
}

sealed interface IntentResolution {
    data class Handled(val packageName: String) : IntentResolution
    data class Ambiguous(val candidates: List<String>) : IntentResolution
    data class NoHandler(val action: String, val uri: String?) : IntentResolution
}

sealed interface LaunchResult {
    data class Started(val packageName: String) : LaunchResult
    data class Blocked(val reason: String) : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

/** One audit row. The History screen and the export both read these. */
data class IntentAuditEntry(
    val timestampMs: Long,
    val action: String,
    val uri: String?,
    val resolvedPackage: String?,
    val riskLevel: RiskLevel,
    val decision: String,
)

/** Storage for the audit rows. The Room implementation lands with the History screen. */
interface IntentAuditLog {
    suspend fun record(entry: IntentAuditEntry)
}

/** A no-op log for tests and for the brief window before the database is open. */
class InMemoryIntentAuditLog : IntentAuditLog {
    private val entries = mutableListOf<IntentAuditEntry>()
    override suspend fun record(entry: IntentAuditEntry) {
        entries += entry
    }

    fun all(): List<IntentAuditEntry> = entries.toList()
}
