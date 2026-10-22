package com.phoneagent.audit

import com.phoneagent.safety.RiskLevel
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import com.phoneagent.util.SecretMasker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The audit log the rest of the app writes to.
 *
 * Every write is best-effort: a database failure must never stop a task, because the log is
 * for the person's benefit rather than the agent's. The failure is logged instead, so a
 * persistent problem is still visible.
 *
 * Text going in is masked and truncated first. The log is meant to be safe to read and to
 * share when something goes wrong, which it cannot be if a screen's text or an API key can
 * land in it.
 */
interface AuditLog {
    fun observe(limit: Int = 500): Flow<List<AuditEvent>>
    suspend fun recent(limit: Int = 500): List<AuditEvent>
    suspend fun record(
        toolName: String,
        risk: RiskLevel,
        decision: AuditDecision,
        detail: String,
        packageName: String? = null,
        stepIndex: Int = 0,
        timestampMs: Long = System.currentTimeMillis(),
    )
    suspend fun prune(nowMs: Long = System.currentTimeMillis())
    suspend fun clear()
}

class RoomAuditLog(
    private val dao: AuditDao,
    private val logger: Logger,
) : AuditLog {

    override fun observe(limit: Int): Flow<List<AuditEvent>> = dao.observeRecent(limit)

    override suspend fun recent(limit: Int): List<AuditEvent> = try {
        dao.recent(limit)
    } catch (error: Throwable) {
        logger.log(LogLevel.ERROR, TAG, "Could not read the audit log: ${error.message}")
        emptyList()
    }

    override suspend fun record(
        toolName: String,
        risk: RiskLevel,
        decision: AuditDecision,
        detail: String,
        packageName: String?,
        stepIndex: Int,
        timestampMs: Long,
    ) {
        try {
            dao.insert(
                AuditEvent(
                    timestampMs = timestampMs,
                    packageName = packageName,
                    toolName = toolName,
                    risk = risk,
                    decision = decision,
                    detail = AuditDetail.sanitize(detail),
                    stepIndex = stepIndex,
                ),
            )
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "Could not write the audit log: ${error.message}")
        }
    }

    override suspend fun prune(nowMs: Long) {
        try {
            val rows = dao.recent(AuditPolicy.MAX_ROWS * 2)
            val ids = AuditPolicy.idsToPrune(rows, nowMs)
            if (ids.isNotEmpty()) {
                dao.deleteByIds(ids)
                logger.log(LogLevel.INFO, TAG, "Pruned ${ids.size} audit rows")
            }
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "Could not prune the audit log: ${error.message}")
        }
    }

    override suspend fun clear() {
        try {
            dao.clear()
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "Could not clear the audit log: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "AuditLog"
    }
}

/**
 * The one place a detail string is made safe to store.
 *
 * Both implementations use this, so the in-memory log cannot quietly differ from the real one.
 * The order matters: mask first, because a secret may span the whole line, then take the first
 * line and cap the length.
 */
object AuditDetail {
    const val MAX_CHARS = 200

    fun sanitize(detail: String): String =
        SecretMasker.mask(detail).lineSequence().first().take(MAX_CHARS)
}

/** An audit log that keeps events in memory. Used in tests and when the database cannot open. */
class InMemoryAuditLog : AuditLog {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<AuditEvent>>(emptyList())

    override fun observe(limit: Int): Flow<List<AuditEvent>> =
        state.map { it.take(limit) }

    override suspend fun recent(limit: Int): List<AuditEvent> = state.value.take(limit)

    override suspend fun record(
        toolName: String,
        risk: RiskLevel,
        decision: AuditDecision,
        detail: String,
        packageName: String?,
        stepIndex: Int,
        timestampMs: Long,
    ) {
        val event = AuditEvent(
            id = (state.value.maxOfOrNull { it.id } ?: 0) + 1,
            timestampMs = timestampMs,
            packageName = packageName,
            toolName = toolName,
            risk = risk,
            decision = decision,
            detail = AuditDetail.sanitize(detail),
            stepIndex = stepIndex,
        )
        // Newest first, matching the DAO's order.
        state.value = listOf(event) + state.value
    }

    override suspend fun prune(nowMs: Long) {
        val ids = AuditPolicy.idsToPrune(state.value, nowMs)
        if (ids.isNotEmpty()) state.value = state.value.filterNot { it.id in ids }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
