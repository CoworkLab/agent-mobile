package com.phoneagent.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneagent.R
import com.phoneagent.util.LogEntry
import com.phoneagent.util.LogLevel

object LogViewerTags {
    const val SCREEN = "log_viewer_screen"
    const val COPY = "log_viewer_copy"
    const val CLEAR = "log_viewer_clear"
    const val LIST = "log_viewer_list"
    const val LEVEL_PREFIX = "log_viewer_level_"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    viewModel: LogViewerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val copyAll: () -> Unit = {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("PhoneAgent logs", viewModel.exportText()),
        )
        viewModel.onCopied()
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissToast()
        }
    }

    Scaffold(
        modifier = modifier.testTag(LogViewerTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("PhoneAgent logs") },
                actions = {
                    IconButton(onClick = { viewModel.refreshFromFile() }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_refresh),
                            contentDescription = "Reload",
                        )
                    }
                    IconButton(
                        onClick = copyAll,
                        modifier = Modifier.testTag(LogViewerTags.COPY),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                            contentDescription = "Copy all",
                        )
                    }
                    IconButton(
                        onClick = { viewModel.clear() },
                        modifier = Modifier.testTag(LogViewerTags.CLEAR),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_delete),
                            contentDescription = "Clear",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            state.lastCrash?.let { crash ->
                CrashCard(crash = crash, onDismiss = viewModel::dismissCrash)
            }

            LevelFilterRow(
                selected = state.minLevel,
                onSelect = viewModel::setMinLevel,
            )

            if (state.entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No log entries yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(LogViewerTags.LIST),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun CrashCard(crash: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "Previous crash",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = crash.take(1500),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

@Composable
private fun LevelFilterRow(selected: LogLevel, onSelect: (LogLevel) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LogLevel.entries.forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level.name, fontSize = 11.sp) },
                modifier = Modifier.testTag(LogViewerTags.LEVEL_PREFIX + level.name),
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFB26A00)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = entry.level.name,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = entry.tag, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        entry.throwable?.let {
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = color,
            )
        }
    }
}
