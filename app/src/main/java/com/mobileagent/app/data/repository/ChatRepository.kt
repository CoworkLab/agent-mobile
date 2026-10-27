package com.mobileagent.app.data.repository

import com.mobileagent.app.data.model.ChatTurn
import com.mobileagent.app.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists chat turns OFF the caller thread: Gson-serializing up to 100 turns on the
 * main thread froze every send. A single-writer IO scope + debounce coalesces bursts.
 */
class ChatRepository(private val preferences: AppPreferences) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSave: kotlinx.coroutines.Job? = null

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, agentText: String) {
        val cleanUser = userText.trim()
        val cleanAgent = agentText.trim()

        if (cleanUser.isEmpty() && cleanAgent.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        // Fill-in-place: the last turn is this exact user message still waiting for
        // its reply (pending placeholder) -> replace the placeholder with the real
        // reply instead of appending a duplicate bubble.
        if (cleanUser.isNotEmpty() && cleanAgent.isNotEmpty() && currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && isPendingReply(last.agentResponse)) {
                currentList[currentList.lastIndex] = last.copy(agentResponse = cleanAgent)
                _turns.value = currentList
                persist(currentList)
                return
            }
        }

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.agentResponse == cleanAgent) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            agentResponse = cleanAgent.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        persist(currentList)
    }

    /**
     * Fills the reply of the most recent turn only while it still shows the pending
     * placeholder. Used to surface delivery failures on the exact message that failed.
     */
    @Synchronized
    fun updateLastTurnReplyIfPending(reply: String) {
        val clean = reply.trim()
        if (clean.isEmpty()) return
        val currentList = _turns.value.toMutableList()
        if (currentList.isEmpty()) return
        val last = currentList.last()
        if (isPendingReply(last.agentResponse)) {
            currentList[currentList.lastIndex] = last.copy(agentResponse = clean)
            _turns.value = currentList
            persist(currentList)
        }
    }

    /** Debounced background persistence: replaces the previous queued save, never blocks the caller. */
    private fun persist(currentList: List<ChatTurn>) {
        pendingSave?.cancel()
        pendingSave = ioScope.launch {
            preferences.saveChatHistory(currentList)
        }
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }

    fun shutdown() {
        ioScope.cancel()
    }

    private fun isPendingReply(text: String): Boolean {
        val t = text.trim()
        return t == PENDING_REPLY_PLACEHOLDER || t == LEGACY_PENDING_REPLY_PLACEHOLDER
    }

    companion object {
        private const val PENDING_REPLY_PLACEHOLDER = "(Audio response)"
        private const val LEGACY_PENDING_REPLY_PLACEHOLDER = "..."
    }
}
