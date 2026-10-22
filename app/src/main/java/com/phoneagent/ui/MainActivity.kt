package com.phoneagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneagent.PhoneAgentApp
import com.phoneagent.permissions.PermissionChecks
import com.phoneagent.service.AgentForegroundService
import com.phoneagent.service.AgentNotifications
import com.phoneagent.ui.logs.LogViewerScreen
import com.phoneagent.ui.logs.LogViewerViewModel
import com.phoneagent.ui.onboarding.OnboardingScreen
import com.phoneagent.ui.onboarding.OnboardingViewModel
import com.phoneagent.ui.task.TaskScreen
import com.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentNotifications.createChannels(this)

        setContent {
            PhoneAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PhoneAgentRoot()
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    TASK("Task"),
    SETUP("Setup"),
    LOGS("Logs"),
}

@Composable
private fun PhoneAgentRoot() {
    val context = LocalContext.current
    val tabs = Tab.entries

    // Open on Setup until the core permissions are granted, otherwise straight to Task.
    var tab by remember {
        mutableIntStateOf(
            if (PermissionChecks.allCoreGranted(context)) Tab.TASK.ordinal else Tab.SETUP.ordinal,
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_nav")) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Text(
                                text = item.label.take(1),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("tab_${item.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tabs[tab]) {
                Tab.TASK -> TaskScreen(
                    onStart = { goal -> AgentForegroundService.startTask(context, goal) },
                    onStop = { AgentForegroundService.stopTask(context) },
                    onAnswer = { text -> AgentForegroundService.controller()?.userAnswer(text) },
                    logger = PhoneAgentApp.logger(),
                )

                Tab.SETUP -> OnboardingScreen(
                    viewModel = viewModel(factory = OnboardingViewModel.factory(context)),
                    onDone = { tab = Tab.TASK.ordinal },
                )

                Tab.LOGS -> LogViewerScreen(
                    viewModel = viewModel(
                        factory = LogViewerViewModel.factory(PhoneAgentApp.logger(), context),
                    ),
                )
            }
        }
    }
}
