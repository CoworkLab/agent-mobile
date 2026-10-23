package com.phoneagent.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneagent.memory.MemoryRepository
import com.phoneagent.memory.MemorySnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(
    private val repository: MemoryRepository,
) : ViewModel() {

    val uiState: StateFlow<MemorySnapshot> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemorySnapshot())

    fun forgetAll() {
        viewModelScope.launch { repository.forgetAll() }
    }

    fun forgetFact(key: String) {
        viewModelScope.launch { repository.forgetFact(key) }
    }

    fun forgetEpisode(id: Long) {
        viewModelScope.launch { repository.forgetEpisode(id) }
    }

    companion object {
        fun factory(repository: MemoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MemoryViewModel(repository) as T
            }
    }
}
