package com.phoneagent.ui.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.phoneagent.selftest.SelfTestCheck

object SelfTestTags {
    const val SCREEN = "selftest_screen"
    const val RUN = "selftest_run"
    const val HEADLINE = "selftest_headline"
    const val CHECK_PREFIX = "selftest_check_"
    const val FIX_PREFIX = "selftest_fix_"
    const val COPY_REPORT = "selftest_copy_report"
}

/**
 * The self-test screen.
 *
 * Two jobs: tell the person whether the app can work at all, and give them one button to press
 * to fix the most blocking problem. A list of ten failures with no order is not actionable, so
 * the headline counts only the blockers and the first fix is pulled out above the list.
 */
@Composable
fun SelfTestScreen(
    viewModel: SelfTestViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(SelfTestTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Check PhoneAgent",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = viewModel::run,
                enabled = !state.running,
                modifier = Modifier.testTag(SelfTestTags.RUN),
            ) {
                Text(if (state.running) "Checking…" else "Check now")
            }
            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
            TextButton(
                onClick = viewModel::copyReport,
                enabled = state.report != null,
                modifier = Modifier.testTag(SelfTestTags.COPY_REPORT),
            ) {
                Text("Copy report")
            }
        }

        state.report?.let { report ->
            Text(
                text = report.headline,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(SelfTestTags.HEADLINE),
            )

            report.firstAction?.let { action ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Do this first",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = action.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = action.remedy.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { viewModel.fix(action.id) },
                            modifier = Modifier.testTag(SelfTestTags.FIX_PREFIX + action.id.name),
                        ) {
                            Text("Fix this")
                        }
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(report.checks, key = { it.id }) { check ->
                    CheckRow(
                        check = check,
                        onFix = { viewModel.fix(check.id) },
                    )
                }
            }
        } ?: Text(
            text = "Run the check to see what is missing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(
    check: SelfTestCheck,
    onFix: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SelfTestTags.CHECK_PREFIX + check.id.name),
        colors = CardDefaults.cardColors(
            containerColor = if (check.passed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = check.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (check.passed) "OK" else "Needs attention",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!check.passed) {
                Text(
                    text = check.remedy.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (check.essential) {
                    TextButton(onClick = onFix) { Text("Fix this") }
                }
            }
        }
    }
}
