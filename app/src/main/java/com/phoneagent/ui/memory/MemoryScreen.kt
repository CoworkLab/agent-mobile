package com.phoneagent.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

object MemoryTags {
    const val SCREEN = "memory_screen"
    const val FORGET_ALL = "memory_forget_all"
    const val CONFIRM_FORGET = "memory_confirm_forget"
    const val FACT_PREFIX = "memory_fact_"
    const val EPISODE_PREFIX = "memory_episode_"
    const val EMPTY = "memory_empty"
}

/**
 * The Memory screen: what PhoneAgent remembers, and one button to forget all of it.
 *
 * This screen exists because the person cannot audit a memory they cannot see. Everything that
 * is stored about them is listed here in full, and deleting it takes one press. The skills are
 * listed separately and survive a wipe, because a skill is not about the person — but that is
 * stated on the screen rather than left as a surprise.
 */
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(MemoryTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "What PhoneAgent remembers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { confirming = true },
                enabled = state.hasAnythingPersonal,
                modifier = Modifier.testTag(MemoryTags.FORGET_ALL),
            ) {
                Text("Forget everything")
            }
        }

        Text(
            text = "Passwords, one-time codes and card numbers are never saved. They are " +
                "removed before anything is written.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.hasAnythingPersonal) {
            Text(
                text = "Nothing is remembered about you yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(MemoryTags.EMPTY),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.facts.isNotEmpty()) {
                item { GroupHeader("Facts about you") }
                items(state.facts.entries.toList(), key = { it.key }) { (key, value) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MemoryTags.FACT_PREFIX + key),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(text = key, style = MaterialTheme.typography.labelMedium)
                            Text(text = value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (state.episodes.isNotEmpty()) {
                item { GroupHeader("Tasks it has done") }
                items(state.episodes, key = { it.id }) { episode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MemoryTags.EPISODE_PREFIX + episode.id),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = episode.goal,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = episode.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (episode.succeeded) {
                                    "Finished in ${episode.stepCount} steps"
                                } else {
                                    "Stopped after ${episode.stepCount} steps"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Forget everything?") },
            text = {
                Text(
                    "This deletes every fact and every task history. Saved skills are kept, " +
                        "because they are not about you. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetAll()
                        confirming = false
                    },
                    modifier = Modifier.testTag(MemoryTags.CONFIRM_FORGET),
                ) {
                    Text("Forget everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}
