package com.phoneagent.audit

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.phoneagent.safety.RiskLevel

/**
 * One row of the audit log: what the agent tried, and what was decided.
 *
 * This is deliberately an append-only record. It answers the question a person asks after a
 * run they did not watch: "what did it actually do, and what did I approve?" Nothing here is
 * used to drive behaviour, so a corrupt or trimmed log can never change how the agent acts.
 */
@Entity(tableName = "audit_events")
data class AuditEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    /** Null when the action was not tied to a package, such as a plan change. */
    val packageName: String?,
    val toolName: String,
    val risk: RiskLevel,
    val decision: AuditDecision,
    /** Short, already-masked description. Never a secret, never raw screen text. */
    val detail: String,
    val stepIndex: Int,
)

/** What happened to a proposed action. */
enum class AuditDecision {
    /** Ran without asking. */
    ALLOWED,

    /** Ran because the user approved. */
    APPROVED,

    /** Did not run because the user said no. */
    DENIED,

    /** Did not run because the request timed out; a timeout is a denial. */
    TIMED_OUT,

    /** Never ran: a rule refused it. */
    BLOCKED,

    /** Never ran: the tool failed on its own. */
    FAILED,
    ;

    val isUserFacing: Boolean
        get() = this == APPROVED || this == DENIED || this == TIMED_OUT
}

/**
 * What the person is shown in the History screen.
 *
 * Kept separate from the entity so the wording can be tested and improved without touching
 * the database, and so the wording lives in one place rather than being assembled in a view.
 */
object AuditFormatter {

    /** One line for the list: "12:04  WhatsApp  Tap Send — approved". */
    fun summaryLine(event: AuditEvent): String {
        val time = clockTime(event.timestampMs)
        val where = event.packageName?.let { shortPackage(it) } ?: "PhoneAgent"
        return "$time  $where  ${describe(event)}"
    }

    fun describe(event: AuditEvent): String {
        val action = actionLabel(event.toolName)
        val outcome = when (event.decision) {
            AuditDecision.ALLOWED -> "done"
            AuditDecision.APPROVED -> "approved, then done"
            AuditDecision.DENIED -> "you said no"
            AuditDecision.TIMED_OUT -> "no answer, so it was skipped"
            AuditDecision.BLOCKED -> "refused by a safety rule"
            AuditDecision.FAILED -> "failed"
        }
        return "$action — $outcome"
    }

    /** A tool name as a person would say it. */
    fun actionLabel(toolName: String): String = when (toolName) {
        "tap" -> "Tap"
        "long_press" -> "Press and hold"
        "type_text" -> "Type"
        "swipe" -> "Swipe"
        "scroll" -> "Scroll"
        "back" -> "Go back"
        "home" -> "Go home"
        "recents" -> "Open recent apps"
        "open_app" -> "Open an app"
        "launch_intent" -> "Use a system action"
        "batch_actions" -> "A short sequence"
        "read_screen" -> "Read the screen"
        "screenshot" -> "Look at the screen"
        "finish" -> "Finish"
        "ask_user" -> "Ask you a question"
        "fail" -> "Give up"
        "update_plan" -> "Change the plan"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** The last part of a package name, which is the part a person recognises. */
    fun shortPackage(packageName: String): String {
        val parts = packageName.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return packageName
        // "com.whatsapp" reads better as "whatsapp"; "com.google.android.gm" as "gm".
        val tail = parts.last()
        return if (tail.length <= 2 && parts.size >= 3) parts[parts.size - 2] else tail
    }

    private fun clockTime(timestampMs: Long): String {
        val totalMinutes = (timestampMs / 60_000L) % (24 * 60)
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()
        return "%02d:%02d".format(hours, minutes)
    }
}

/**
 * Retention and search rules, as pure functions.
 *
 * Kept out of the DAO so the rules can be tested on the JVM, where the Android database is
 * not available.
 */
object AuditPolicy {

    /** Rows kept. Beyond this the oldest are pruned, so the log cannot grow without bound. */
    const val MAX_ROWS = 5_000

    /** How long a row is kept. A month is long enough to answer "what did it do last week?". */
    const val RETENTION_DAYS = 30L

    fun retentionCutoffMs(nowMs: Long): Long = nowMs - RETENTION_DAYS * 24 * 60 * 60 * 1000L

    /** The ids to delete: everything older than the cutoff, and everything past the cap. */
    fun idsToPrune(
        rows: List<AuditEvent>,
        nowMs: Long,
        maxRows: Int = MAX_ROWS,
    ): List<Long> {
        val cutoff = retentionCutoffMs(nowMs)
        val tooOld = rows.filter { it.timestampMs < cutoff }.map { it.id }

        // Rows are newest-first. Anything past the cap is pruned as well.
        val tooMany = rows.sortedByDescending { it.timestampMs }.drop(maxRows).map { it.id }

        return (tooOld + tooMany).distinct()
    }

    /**
     * Whether an event matches a search.
     *
     * Only the fields a person would search on are matched, and the match is
     * case-insensitive substring rather than a regex, because a person typing into a search
     * box is not writing a pattern.
     */
    fun matches(event: AuditEvent, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return listOfNotNull(
            event.packageName,
            event.toolName,
            event.detail,
            AuditFormatter.actionLabel(event.toolName),
            AuditFormatter.describe(event),
        ).any { it.lowercase().contains(needle) }
    }

    fun filter(events: List<AuditEvent>, query: String): List<AuditEvent> =
        if (query.isBlank()) events else events.filter { matches(it, query) }

    /** Counts for a run summary: how many were approved, denied, refused. */
    fun counts(events: List<AuditEvent>): Map<AuditDecision, Int> =
        events.groupingBy { it.decision }.eachCount()
}
