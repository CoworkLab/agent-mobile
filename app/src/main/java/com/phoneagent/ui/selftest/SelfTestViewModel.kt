package com.phoneagent.ui.selftest

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneagent.brain.ProviderRepository
import com.phoneagent.permissions.PermissionChecks
import com.phoneagent.selftest.SelfTest
import com.phoneagent.selftest.SelfTestCheck
import com.phoneagent.selftest.SelfTestFixes
import com.phoneagent.selftest.SelfTestProbe
import com.phoneagent.selftest.SelfTestReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SelfTestUiState(
    val running: Boolean = false,
    val report: SelfTestReport? = null,
)

class SelfTestViewModel(
    private val context: Context,
    private val probe: SelfTestProbe,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelfTestUiState())
    val uiState: StateFlow<SelfTestUiState> = _uiState.asStateFlow()

    /** Opens the system screen where the missing permission can be granted. */
    private val _openSettings = MutableStateFlow<android.content.Intent?>(null)
    val openSettings: StateFlow<android.content.Intent?> = _openSettings.asStateFlow()

    fun run() {
        if (_uiState.value.running) return
        _uiState.value = SelfTestUiState(running = true)

        viewModelScope.launch {
            val facts = probe.gather()
            _uiState.value = SelfTestUiState(running = false, report = SelfTest.run(facts))
        }
    }

    /**
     * Sends the person to the right system screen for a check.
     *
     * The mapping lives here rather than in the screen so the intent is built in one place, and
     * a check with no system screen simply does nothing instead of opening the wrong one.
     */
    fun fix(id: SelfTestCheck.Id) {
        val permission = SelfTestFixes.permissionFor(id) ?: return
        _openSettings.value = PermissionChecks.settingsIntent(context, permission)
    }

    fun consumeIntent() {
        _openSettings.value = null
    }

    fun copyReport() {
        val report = _uiState.value.report ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("PhoneAgent self-test", SelfTestFixes.reportText(report)),
        )
    }

    companion object {
        fun factory(context: Context, providers: ProviderRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SelfTestViewModel(
                        context.applicationContext,
                        SelfTestProbe(context.applicationContext, providers),
                    ) as T
            }
    }
}
