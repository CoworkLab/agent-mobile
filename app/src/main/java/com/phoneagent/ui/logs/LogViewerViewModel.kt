package com.phoneagent.ui.logs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneagent.util.CrashHandler
import com.phoneagent.util.FileLogSink
import com.phoneagent.util.LogEntry
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LogViewerUiState(
    val entries: List<LogEntry> = emptyList(),
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val lastCrash: String? = null,
    val copiedToClipboard: Boolean = false,
)

class LogViewerViewModel(
    private val logger: Logger,
    private val context: Context,
) : ViewModel() {

    private val fileSink = FileLogSink(context)

    private val _minLevel = MutableStateFlow(LogLevel.VERBOSE)
    private val _lastCrash = MutableStateFlow(CrashHandler.consumeLastCrash(context))
    private val _copied = MutableStateFlow(false)

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val uiState: StateFlow<LogViewerUiState> =
        combine(logger.entries, _minLevel, _lastCrash, _copied) { entries, level, crash, copied ->
            LogViewerUiState(
                entries = entries.filter { it.level.ordinal >= level.ordinal }.reversed(),
                minLevel = level,
                lastCrash = crash,
                copiedToClipboard = copied,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogViewerUiState())

    fun setMinLevel(level: LogLevel) {
        _minLevel.value = level
        logger.minLevel = level
    }

    fun refreshFromFile() {
        val text = fileSink.readAll()
        logger.i(TAG, "Loaded ${text.length} chars from the log file")
    }

    fun clear() {
        logger.clear()
        fileSink.clear()
        logger.i(TAG, "Logs cleared")
    }

    fun exportText(): String = buildString {
        append("PhoneAgent log export\n")
        append("entries=").append(logger.snapshot().size).append('\n')
        _lastCrash.value?.let { append("\n--- last crash ---\n").append(it).append('\n') }
        append("\n--- log ---\n")
        append(logger.export())
    }

    fun onCopied() {
        _copied.value = true
        _toast.value = "Logs copied"
    }

    fun dismissToast() {
        _toast.value = null
        _copied.value = false
    }

    fun dismissCrash() {
        _lastCrash.value = null
    }

    companion object {
        private const val TAG = "LogViewer"

        fun factory(logger: Logger, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LogViewerViewModel(logger, context.applicationContext) as T
            }
    }
}
