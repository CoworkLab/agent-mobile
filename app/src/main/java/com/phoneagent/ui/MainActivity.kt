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
import androidx.compose.runtime.mutableStateOf
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
import com.phoneagent.ui.history.HistoryScreen
import com.phoneagent.ui.history.HistoryViewModel
import com.phoneagent.ui.logs.LogViewerScreen
import com.phoneagent.ui.logs.LogViewerViewModel
import com.phoneagent.ui.memory.MemoryScreen
import com.phoneagent.ui.memory.MemoryViewModel
import com.phoneagent.ui.onboarding.OnboardingScreen
import com.phoneagent.ui.onboarding.OnboardingViewModel
import com.phoneagent.ui.selftest.SelfTestScreen
import com.phoneagent.ui.selftest.SelfTestViewModel
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.settings.SettingsViewModel
import com.phoneagent.ui.skills.SkillsScreen
import com.phoneagent.ui.skills.SkillsViewModel
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

/**
 * The bottom tabs.
 *
 * Six is already at the limit of what a phone bar holds comfortably, so the labels are short
 * and the icons are the first letter. Anything more detailed belongs inside a screen, not in
 * the bar.
 */
private enum class Tab(val label: String) {
    TASK("Task"),
    HISTORY("History"),
    MEMORY("Memory"),
    SKILLS("Skills"),
    SETTINGS("Settings"),
    CHECK("Check"),
    LOGS("Logs"),
}

@Composable
private fun PhoneAgentRoot() {
    val context = LocalContext.current
    val tabs = Tab.entries

    // Open on Setup until the core permissions are granted, otherwise straight to Task.
    var tab by remember {
        mutableIntStateOf(
            // Start on Check when the core permissions are missing: that screen says exactly
            // what is wrong and has a button to fix each one, which is more useful than a
            // task box that would fail on the first step.
            if (PermissionChecks.allCoreGranted(context)) Tab.TASK.ordinal else Tab.CHECK.ordinal,
        )
    }

    // Setup is shown over the tabs rather than as a tab of its own: seven tabs do not fit a
    // phone bar, and setup is something you do once rather than somewhere you visit.
    var showSetup by remember {
        mutableStateOf(!PermissionChecks.allCoreGranted(context))
    }

    if (showSetup) {
        OnboardingScreen(
            viewModel = viewModel(factory = OnboardingViewModel.factory(context)),
            onDone = {
                showSetup = false
                tab = Tab.TASK.ordinal
            },
        )
        return
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

                Tab.HISTORY -> HistoryScreen(
                    viewModel = viewModel(
                        factory = HistoryViewModel.factory(PhoneAgentApp.auditLog()),
                    ),
                )

                Tab.MEMORY -> MemoryScreen(
                    viewModel = viewModel(
                        factory = MemoryViewModel.factory(PhoneAgentApp.memory()),
                    ),
                )

                Tab.SKILLS -> SkillsScreen(
                    viewModel = viewModel(
                        factory = SkillsViewModel.factory(PhoneAgentApp.skills()),
                    ),
                )

                Tab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel(factory = SettingsViewModel.factory(context)),
                    onOpenSetup = { showSetup = true },
                )

                Tab.CHECK -> SelfTestScreen(
                    viewModel = viewModel(
                        factory = SelfTestViewModel.factory(context, PhoneAgentApp.providers()),
                    ),
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
