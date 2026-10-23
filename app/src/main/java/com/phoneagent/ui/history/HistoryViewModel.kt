package com.phoneagent.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneagent.audit.AuditEvent
import com.phoneagent.audit.AuditLog
import com.phoneagent.audit.AuditPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val events: List<AuditEvent> = emptyList(),
    val query: String = "",
) {
    /** The events that match the search, newest first. */
    val visible: List<AuditEvent> get() = AuditPolicy.filter(events, query)
}

class HistoryViewModel(
    private val audit: AuditLog,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> =
        combine(audit.observe(), _query) { events, query ->
            HistoryUiState(events = events, query = query)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun clear() {
        viewModelScope.launch { audit.clear() }
    }

    companion object {
        fun factory(audit: AuditLog): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(audit) as T
            }
    }
}
