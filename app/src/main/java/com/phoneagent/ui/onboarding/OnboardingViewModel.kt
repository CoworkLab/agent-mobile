package com.phoneagent.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.phoneagent.permissions.PermissionChecks
import com.phoneagent.permissions.PermissionId
import com.phoneagent.permissions.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OnboardingUiState(
    val permissions: List<PermissionStatus> = emptyList(),
    val allCoreGranted: Boolean = false,
    val showRestrictedSettingsHelp: Boolean = false,
)

class OnboardingViewModel(private val appContext: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads live permission state. Called on resume and after each settings trip. */
    fun refresh() {
        val snapshot = runCatching { PermissionChecks.snapshot(appContext) }.getOrDefault(emptyList())
        _uiState.update {
            it.copy(
                permissions = snapshot,
                allCoreGranted = runCatching {
                    PermissionChecks.allCoreGranted(appContext)
                }.getOrDefault(false),
                showRestrictedSettingsHelp = runCatching {
                    PermissionChecks.needsRestrictedSettingsHelp(appContext)
                }.getOrDefault(false),
            )
        }
    }

    fun settingsIntent(id: PermissionId) = PermissionChecks.settingsIntent(appContext, id)

    fun restrictedSettingsIntent() = PermissionChecks.restrictedSettingsAppInfoIntent(appContext)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OnboardingViewModel(context.applicationContext) as T
            }
    }
}
