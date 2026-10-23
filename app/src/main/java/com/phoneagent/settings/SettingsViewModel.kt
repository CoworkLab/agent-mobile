package com.phoneagent.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen.
 *
 * Writes go straight through to storage rather than being saved on a button, because a person
 * on a phone who changes a setting and then leaves the screen expects it to have stuck. The
 * only exception is the denylist text box, which is committed when the field loses focus, so a
 * half-typed package name is never stored.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun setMaxSteps(value: Int) = update { it.copy(maxSteps = value) }

    fun setMaxMinutes(value: Int) = update { it.copy(maxTaskMinutes = value) }

    fun setSpeechEnabled(enabled: Boolean) = update { it.copy(speechEnabled = enabled) }

    fun setLanguage(tag: String) = update { it.copy(languageTag = tag) }

    fun setLogLevel(level: com.phoneagent.util.LogLevel) = update { it.copy(logLevel = level) }

    fun setDraftSkills(enabled: Boolean) = update { it.copy(draftSkills = enabled) }

    fun setConfirmRisky(enabled: Boolean) = update { it.copy(confirmRiskyActions = enabled) }

    fun setCheckForUpdates(enabled: Boolean) = update { it.copy(checkForUpdates = enabled) }

    fun setPerceptionMode(mode: com.phoneagent.perception.PerceptionMode) =
        update { it.copy(perceptionMode = mode) }

    /** Adds one app to the denylist. Used by the "block this app" action on a running task. */
    fun addDeniedPackage(packageName: String) = update { current ->
        current.copy(deniedPackages = current.deniedPackages + packageName)
    }

    fun removeDeniedPackage(packageName: String) = update { current ->
        current.copy(deniedPackages = current.deniedPackages - packageName)
    }

    fun setDeniedPackages(text: String) = update { current ->
        current.copy(deniedPackages = text.split('\n'))
    }

    fun reset() {
        viewModelScope.launch {
            repository.reset()
            _toast.value = "Settings are back to their defaults"
        }
    }

    fun dismissToast() {
        _toast.value = null
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        DataStoreSettingsRepository(context.applicationContext),
                    ) as T
            }
    }
}
