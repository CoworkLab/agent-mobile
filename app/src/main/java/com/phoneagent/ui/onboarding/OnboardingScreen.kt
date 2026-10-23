package com.phoneagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneagent.permissions.PermissionStatus

object OnboardingTags {
    const val SCREEN = "onboarding_screen"
    const val REFRESH = "onboarding_refresh"
    const val ROW_PREFIX = "onboarding_row_"
    const val RESTRICTED_HELP = "onboarding_restricted_help"
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(modifier = modifier.testTag(OnboardingTags.SCREEN)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Set up PhoneAgent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "PhoneAgent needs a few permissions to control your phone. " +
                    "Tap each one and turn it on. You can change these any time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (state.showRestrictedSettingsHelp) {
                RestrictedSettingsCard(
                    onOpenAppInfo = {
                        runCatching { context.startActivity(viewModel.restrictedSettingsIntent()) }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            state.permissions.forEach { permission ->
                PermissionRow(
                    status = permission,
                    onOpen = {
                        runCatching { context.startActivity(viewModel.settingsIntent(permission.id)) }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.testTag(OnboardingTags.REFRESH),
                ) {
                    Text("Re-check")
                }
                Button(
                    onClick = onDone,
                    enabled = state.allCoreGranted,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.allCoreGranted) "Start using PhoneAgent" else "Turn on the top 3")
                }
                // Always available. Setup is shown over the whole app, so without a way past
                // it a permission the phone will not grant — a locked-down work profile, say —
                // leaves the user stuck on a screen they cannot leave. Everything here can be
                // revisited from the Check tab, which lists the same permissions and fixes.
                TextButton(onClick = onDone) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsCard(onOpenAppInfo: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.RESTRICTED_HELP),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "If the Accessibility switch is greyed out",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Android hides this for apps installed outside the Play Store. " +
                    "Open the app info screen, tap the three-dot menu, choose " +
                    "\"Allow restricted settings\", then come back and turn on Accessibility.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenAppInfo) { Text("Open app info") }
        }
    }
}

@Composable
private fun PermissionRow(status: PermissionStatus, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.ROW_PREFIX + status.id.name),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (status.granted) "ON" else "OFF",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (status.granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(text = status.why, style = MaterialTheme.typography.bodySmall)
            if (!status.granted) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpen) { Text("Turn on") }
            }
        }
    }
}
