package com.phoneagent.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneagent.memory.Skill
import com.phoneagent.memory.SkillLibrary
import com.phoneagent.memory.SkillStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SkillsUiState(
    val all: List<Skill> = emptyList(),
) {
    val drafts: List<Skill> get() = all.filter { it.status == SkillStatus.DRAFT }
    val approved: List<Skill> get() = all.filter { it.status == SkillStatus.APPROVED }
}

class SkillsViewModel(
    private val library: SkillLibrary,
) : ViewModel() {

    private val version = MutableStateFlow(0L)

    val uiState: StateFlow<SkillsUiState> = version
        .map { SkillsUiState(all = library.all()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillsUiState())

    fun approve(id: String) {
        viewModelScope.launch {
            library.approve(id)
            bump()
        }
    }

    fun reject(id: String) {
        viewModelScope.launch {
            library.delete(id)
            bump()
        }
    }

    private fun bump() {
        version.value = version.value + 1
    }

    companion object {
        fun factory(library: SkillLibrary): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SkillsViewModel(library) as T
            }
    }
}
