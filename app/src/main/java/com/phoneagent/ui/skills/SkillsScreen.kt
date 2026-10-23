package com.phoneagent.ui.skills

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneagent.memory.Skill
import com.phoneagent.memory.SkillStatus

object SkillsTags {
    const val SCREEN = "skills_screen"
    const val EMPTY = "skills_empty"
    const val ROW_PREFIX = "skills_row_"
    const val APPROVE_PREFIX = "skills_approve_"
    const val REJECT_PREFIX = "skills_reject_"
}

/**
 * The Skills screen.
 *
 * Two lists, in this order: skills waiting for approval, then the ones already in use. A draft
 * is shown first because it is the only thing here that needs the person to do something, and
 * a draft is never used until it is approved.
 */
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(SkillsTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Saved skills",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "A skill is a set of steps that worked. PhoneAgent only uses one after you " +
                "approve it, and skills describe what to look for rather than where to tap, " +
                "so they keep working when an app moves its buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.all.isEmpty()) {
            Text(
                text = "No skills yet. After a task works well, PhoneAgent may offer to save it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(SkillsTags.EMPTY),
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.drafts.isNotEmpty()) {
                item { Header("Waiting for your approval") }
                items(state.drafts, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onApprove = { viewModel.approve(skill.id) },
                        onReject = { viewModel.reject(skill.id) },
                    )
                }
            }

            if (state.approved.isNotEmpty()) {
                item { Header("In use") }
                items(state.approved, key = { it.id }) { skill ->
                    SkillCard(skill = skill, onApprove = null, onReject = null)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onApprove: (() -> Unit)?,
    onReject: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SkillsTags.ROW_PREFIX + skill.id),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.status == SkillStatus.DRAFT) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
            )
            if (skill.apps.isNotEmpty()) {
                Text(
                    text = "For: ${skill.apps.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onApprove != null && onReject != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onApprove,
                        modifier = Modifier.testTag(SkillsTags.APPROVE_PREFIX + skill.id),
                    ) {
                        Text("Approve")
                    }
                    TextButton(
                        onClick = onReject,
                        modifier = Modifier.testTag(SkillsTags.REJECT_PREFIX + skill.id),
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}
