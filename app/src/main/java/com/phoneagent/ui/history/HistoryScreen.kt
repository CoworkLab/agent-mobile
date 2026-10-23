package com.phoneagent.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneagent.audit.AuditDecision
import com.phoneagent.audit.AuditEvent
import com.phoneagent.audit.AuditFormatter
import com.phoneagent.audit.AuditPolicy

object HistoryTags {
    const val SCREEN = "history_screen"
    const val SEARCH = "history_search"
    const val CLEAR = "history_clear"
    const val EMPTY = "history_empty"
    const val ROW_PREFIX = "history_row_"
}

/**
 * The History screen: what the agent did, and what the person approved.
 *
 * Only the decisions the person actually made are highlighted. Everything else is a quiet
 * line, because the interesting question after an unattended run is "what did it ask me
 * about, and what did I say?" — not a wall of every tap.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(HistoryTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "What PhoneAgent did",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.events.isNotEmpty()) {
                TextButton(
                    onClick = viewModel::clear,
                    modifier = Modifier.testTag(HistoryTags.CLEAR),
                ) {
                    Text("Clear")
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text("Search by app, action or word") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HistoryTags.SEARCH),
        )

        if (state.visible.isEmpty()) {
            Text(
                text = state.emptyMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(HistoryTags.EMPTY),
            )
            return@Column
        }

        state.approvalSummary()?.let { summary ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = summary,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.visible, key = { it.id }) { event ->
                HistoryRow(event)
            }
        }
    }
}

@Composable
private fun HistoryRow(event: AuditEvent) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HistoryTags.ROW_PREFIX + event.id),
        colors = CardDefaults.cardColors(
            // A decision the person made is worth noticing; the rest is context.
            containerColor = if (event.decision.isUserFacing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = AuditFormatter.summaryLine(event),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (event.decision.isUserFacing) FontWeight.Bold else FontWeight.Normal,
            )
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun HistoryUiState.emptyMessage(): String = when {
    events.isEmpty() -> "Nothing here yet. Once PhoneAgent does a task, it will be listed here."
    else -> "Nothing matched \"$query\"."
}

/** A one-line summary of the decisions the person made, or null when there were none. */
private fun HistoryUiState.approvalSummary(): String? {
    val counts = AuditPolicy.counts(events)
    val approved = counts[AuditDecision.APPROVED] ?: 0
    val denied = counts[AuditDecision.DENIED] ?: 0
    val timedOut = counts[AuditDecision.TIMED_OUT] ?: 0
    val blocked = counts[AuditDecision.BLOCKED] ?: 0
    if (approved + denied + timedOut + blocked == 0) return null

    return buildString {
        append("You approved $approved")
        if (denied > 0) append(", refused $denied")
        if (timedOut > 0) append(", did not answer $timedOut")
        if (blocked > 0) append(", $blocked refused by a safety rule")
        append(".")
    }
}
