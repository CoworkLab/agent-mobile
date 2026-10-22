package com.phoneagent.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneagent.agent.AgentState
import com.phoneagent.agent.AgentStatus
import com.phoneagent.service.AgentForegroundService
import com.phoneagent.util.Logger

object TaskTags {
    const val SCREEN = "task_screen"
    const val GOAL_FIELD = "task_goal_field"
    const val START = "task_start"
    const val STOP = "task_stop"
    const val STATUS = "task_status"
    const val ANSWER_FIELD = "task_answer_field"
    const val SEND_ANSWER = "task_send_answer"
}

@Composable
fun TaskScreen(
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String) -> Unit,
    logger: Logger,
    modifier: Modifier = Modifier,
) {
    val status by AgentForegroundService.status.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    val running = status.state.isBusy

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TaskTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "What should PhoneAgent do?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("For example: set an alarm for 7 am") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TaskTags.GOAL_FIELD),
            enabled = !running,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (goal.isNotBlank()) {
                        logger.i("TaskScreen", "Task started from UI")
                        onStart(goal)
                    }
                },
                enabled = !running && goal.isNotBlank(),
                modifier = Modifier.testTag(TaskTags.START),
            ) {
                Text("Start")
            }
            Button(
                onClick = {
                    logger.i("TaskScreen", "STOP tapped in app")
                    onStop()
                },
                enabled = true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag(TaskTags.STOP),
            ) {
                Text("STOP")
            }
        }

        StatusCard(status)

        if (status.state == AgentState.WAITING_USER) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = status.question ?: "PhoneAgent has a question",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        label = { Text("Your answer") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TaskTags.ANSWER_FIELD),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (answer.isNotBlank()) {
                                onAnswer(answer)
                                answer = ""
                            }
                        },
                        modifier = Modifier.testTag(TaskTags.SEND_ANSWER),
                    ) {
                        Text("Send answer")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: AgentStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (status.state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = status.state.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag(TaskTags.STATUS),
                )
            }
            status.goal?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = "Goal: $it", style = MaterialTheme.typography.bodySmall)
            }
            if (status.state.isBusy || status.stepIndex > 0) {
                Text(
                    text = "Step ${status.stepIndex}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.lastNote?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            status.summary?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            status.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
