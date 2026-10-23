package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.settings.AppSettings
import com.phoneagent.settings.SettingsViewModel
import com.phoneagent.util.LogLevel

object SettingsTags {
    const val SCREEN = "settings_screen"
    const val OPEN_SETUP = "settings_open_setup"
    const val MAX_STEPS = "settings_max_steps"
    const val MAX_MINUTES = "settings_max_minutes"
    const val SPEECH = "settings_speech"
    const val CONFIRM_RISKY = "settings_confirm_risky"
    const val DRAFT_SKILLS = "settings_draft_skills"
    const val CHECK_UPDATES = "settings_check_updates"
    const val DENYLIST = "settings_denylist"
    const val RESET = "settings_reset"
    const val LANGUAGE_PREFIX = "settings_language_"
    const val LOG_LEVEL_PREFIX = "settings_log_"
    const val PERCEPTION_PREFIX = "settings_perception_"
}

/**
 * The Settings screen.
 *
 * Grouped by what the person is trying to do, not by which class reads the value: limits,
 * voice, safety, and what gets recorded. Each control says what it changes in plain words,
 * because "perception mode" means nothing to the person using this.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(SettingsTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Setup") {
            Text(
                text = "Permissions, the API key and the first-run walkthrough.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Button(
                onClick = onOpenSetup,
                modifier = Modifier.testTag(SettingsTags.OPEN_SETUP),
            ) {
                Text("Open setup")
            }
        }

        Section("Limits") {
            SliderRow(
                label = "Most steps for one task",
                value = settings.maxSteps,
                range = AppSettings.MIN_STEPS.toFloat()..AppSettings.MAX_STEPS.toFloat(),
                steps = 0,
                testTag = SettingsTags.MAX_STEPS,
                onValueChange = { viewModel.setMaxSteps(it.toInt()) },
            )
            SliderRow(
                label = "Longest one task may run (minutes)",
                value = settings.maxTaskMinutes,
                range = AppSettings.MIN_MINUTES.toFloat()..AppSettings.MAX_MINUTES.toFloat(),
                steps = 0,
                testTag = SettingsTags.MAX_MINUTES,
                onValueChange = { viewModel.setMaxMinutes(it.toInt()) },
            )
            Text(
                text = "PhoneAgent stops when either limit is reached and tells you why.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Voice") {
            SwitchRow(
                label = "Speak questions and summaries",
                checked = settings.speechEnabled,
                testTag = SettingsTags.SPEECH,
                onCheckedChange = viewModel::setSpeechEnabled,
            )
            Text(
                text = "Language for listening and speaking",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGUAGE_LABELS.forEach { (tag, label) ->
                    FilterChip(
                        selected = settings.languageTag == tag,
                        onClick = { viewModel.setLanguage(tag) },
                        label = { Text(label) },
                        modifier = Modifier.testTag(SettingsTags.LANGUAGE_PREFIX + tag),
                    )
                }
            }
        }

        Section("Safety") {
            SwitchRow(
                label = "Ask me before anything risky",
                checked = settings.confirmRiskyActions,
                testTag = SettingsTags.CONFIRM_RISKY,
                onCheckedChange = viewModel::setConfirmRisky,
            )
            Text(
                text = "Turning this off means sending, paying and deleting happen without a " +
                    "question. Only do that if you are watching every step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Apps PhoneAgent must never touch",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            DenylistField(
                packages = settings.deniedPackages,
                onCommit = viewModel::setDeniedPackages,
            )
            Text(
                text = "One per line. A * at the end matches a family, such as com.bank.*",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("How it looks at the screen") {
            PerceptionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.perceptionMode == mode,
                    onClick = { viewModel.setPerceptionMode(mode) },
                    label = { Text(perceptionLabel(mode)) },
                    modifier = Modifier.testTag(SettingsTags.PERCEPTION_PREFIX + mode.name),
                )
            }
            Text(
                text = "Reading the screen as text is cheapest and works almost everywhere. " +
                    "Adding a picture helps with apps that draw their own buttons.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Learning") {
            SwitchRow(
                label = "Offer to save what worked as a skill",
                checked = settings.draftSkills,
                testTag = SettingsTags.DRAFT_SKILLS,
                onCheckedChange = viewModel::setDraftSkills,
            )
            Text(
                text = "A saved skill is only used after you approve it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Logs and updates") {
            Text(
                text = "How much is written to the log",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOG_LEVELS.forEach { (level, label) ->
                    FilterChip(
                        selected = settings.logLevel == level,
                        onClick = { viewModel.setLogLevel(level) },
                        label = { Text(label) },
                        modifier = Modifier.testTag(SettingsTags.LOG_LEVEL_PREFIX + level.name),
                    )
                }
            }
            SwitchRow(
                label = "Check for a new version at launch",
                checked = settings.checkForUpdates,
                testTag = SettingsTags.CHECK_UPDATES,
                onCheckedChange = viewModel::setCheckForUpdates,
            )
        }

        TextButton(
            onClick = viewModel::reset,
            modifier = Modifier.testTag(SettingsTags.RESET),
        ) {
            Text("Reset everything to defaults")
        }
    }
}

/**
 * The denylist editor.
 *
 * The text is edited locally and saved by an explicit button rather than on every keystroke.
 * That is deliberate: writing each character would briefly store a half-typed package name,
 * and during that moment the app it names would not be blocked.
 */
@Composable
private fun DenylistField(
    packages: List<String>,
    onCommit: (String) -> Unit,
) {
    var text by remember(packages) { mutableStateOf(packages.joinToString("\n")) }
    val changed = text != packages.joinToString("\n")

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTags.DENYLIST),
        minLines = 4,
        maxLines = 10,
        label = { Text("Blocked apps") },
    )

    Row {
        TextButton(
            onClick = { onCommit(text) },
            enabled = changed,
        ) {
            Text(if (changed) "Save blocked apps" else "Saved")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.testTag(testTag),
        )
    }
}

private val LANGUAGE_LABELS = listOf(
    "hi-IN" to "हिंदी",
    "en-IN" to "English (India)",
    "en-US" to "English (US)",
)

private val LOG_LEVELS = listOf(
    LogLevel.VERBOSE to "Everything",
    LogLevel.DEBUG to "Detailed",
    LogLevel.INFO to "Normal",
    LogLevel.WARN to "Warnings",
    LogLevel.ERROR to "Problems only",
)

/** A perception mode as a person would describe it. */
internal fun perceptionLabel(mode: PerceptionMode): String = when (mode) {
    PerceptionMode.TREE_ONLY -> "Text only (cheapest)"
    PerceptionMode.TREE_PLUS_SCREENSHOT -> "Text and a picture"
    PerceptionMode.SCREENSHOT_ONLY -> "Picture only"
}
