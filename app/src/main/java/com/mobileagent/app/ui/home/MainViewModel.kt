package com.mobileagent.app.ui.home

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.AgentApp
import com.mobileagent.app.background.AgentConversationService
import com.mobileagent.app.data.model.ChatTurn
import com.mobileagent.app.data.model.ConversationState
import com.mobileagent.app.data.preferences.AppPreferences
import com.mobileagent.app.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This ViewModel is intentionally "thin": the microphone, the Gemini Live
 * socket, and the standby/wake-word listener all live inside
 * [AgentConversationService], not here. This class only mirrors that
 * service's state for the UI and forwards user taps as control intents.
 *
 * That split matters: when the app is removed from Recents, Android destroys
 * the Activity and this ViewModel - if the live conversation lived here, it
 * would die with it. Because it lives in the foreground service instead, the
 * conversation (or standby listening) keeps running untouched, and whenever
 * the UI comes back it just resumes observing whatever the service is doing.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: AppPreferences = (application as AgentApp).preferences

    val isSessionOn: StateFlow<Boolean> = AgentConversationService.isSessionOn
    val conversationState: StateFlow<ConversationState> = AgentConversationService.conversationState
    val audioLevel: StateFlow<Float> = AgentConversationService.audioLevel
    val audioFrequency: StateFlow<com.mobileagent.app.audio.AudioFrequencyData> = AgentConversationService.audioFrequency
    val liveUserTranscript: StateFlow<String> = AgentConversationService.liveUserTranscript
    val liveAssistantResponse: StateFlow<String> = AgentConversationService.liveAssistantResponse
    val liveError: StateFlow<com.mobileagent.app.background.AgentLiveError?> = AgentConversationService.liveError
    val chatTurns: StateFlow<List<ChatTurn>> = (application as AgentApp).chatRepository.turns

    fun clearLiveError() {
        AgentConversationService.clearLiveError()
    }

    private val _isMicMuted = MutableStateFlow(preferences.isMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Surfaces "STANDBY" whenever the service is listening only for the wake
    // word, otherwise mirrors the service's live connection status.
    private val _connectionStatus = MutableStateFlow("READY")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _liveTime = MutableStateFlow("")
    val liveTime: StateFlow<String> = _liveTime.asStateFlow()

    private val _personalityName = MutableStateFlow(preferences.personality)
    val personalityName: StateFlow<String> = _personalityName.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    private var timeClockJob: Job? = null

    init {
        startTimeClock()
        observeServiceStatus()
        observeServiceEvents()
    }

    private fun startTimeClock() {
        timeClockJob = viewModelScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) {
                _liveTime.value = sdf.format(Date())
                delay(1000)
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            combine(
                AgentConversationService.isStandby,
                AgentConversationService.connectionStatus
            ) { standby, liveStatus -> if (standby) "STANDBY" else liveStatus }
                .collect { _connectionStatus.value = it }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            AgentConversationService.events.collect { message -> _eventFlow.emit(message) }
        }
        viewModelScope.launch {
            AgentConversationService.wakeEvents.collect {
                _eventFlow.emit("Wake word detected")
            }
        }
    }

    fun refreshSettings() {
        _personalityName.value = preferences.personality
        _isMicMuted.value = preferences.isMicMuted
        AgentConversationService.setMicMuted(preferences.isMicMuted)
    }

    fun turnOffSession() {
        sendServiceAction(AgentConversationService.ACTION_STOP_ALL)
        viewModelScope.launch {
            _eventFlow.emit("Agent Powered Off")
        }
    }

    fun toggleSession() {
        if (AgentConversationService.isSessionOn.value) {
            turnOffSession()
        } else {
            val context = getApplication<Application>()
            if (!PermissionHelper.hasAllRequiredPermissions(context)) {
                viewModelScope.launch {
                    _eventFlow.emit("Please allow all permissions in Settings to activate Agent.")
                }
                return
            }
            val apiKey = preferences.apiKey.trim()
            if (apiKey.isBlank()) {
                viewModelScope.launch {
                    _eventFlow.emit("Please configure your Gemini API Key in Settings first.")
                }
                return
            }
            sendServiceAction(AgentConversationService.ACTION_START_CONVERSATION)
        }
    }

    fun toggleMicMute() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        preferences.isMicMuted = newMuted
        AgentConversationService.setMicMuted(newMuted)
        viewModelScope.launch {
            _eventFlow.emit(if (newMuted) "Microphone Muted (Off)" else "Microphone Active (On)")
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(getApplication<Application>(), AgentConversationService::class.java)
            .setAction(action)
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
    }

    override fun onCleared() {
        super.onCleared()
        timeClockJob?.cancel()
        // Deliberately NOT stopping AgentConversationService here. The
        // Activity/ViewModel being torn down (e.g. the app swiped away from
        // Recents) must not stop the mic or the live conversation - that's
        // the whole point of owning them in the service instead of here.
    }
}
