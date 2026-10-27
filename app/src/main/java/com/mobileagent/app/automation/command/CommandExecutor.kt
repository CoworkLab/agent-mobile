package com.mobileagent.app.automation.command

import android.util.Log
import com.mobileagent.app.automation.phone.PhoneControlManager
import com.mobileagent.app.automation.whatsapp.WhatsAppAssistant
import com.mobileagent.app.automation.youtube.YouTubeAutomationManager
import com.mobileagent.app.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: AgentCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is AgentCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is AgentCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is AgentCommand.PauseMedia -> youtubeManager.pausePlayback()
            is AgentCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is AgentCommand.NextMedia -> youtubeManager.nextMedia()
            is AgentCommand.PreviousMedia -> youtubeManager.previousMedia()
            is AgentCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is AgentCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is AgentCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is AgentCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is AgentCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is AgentCommand.OpenApp -> withContext(Dispatchers.IO) { phoneManager.openApp(command.packageName) }
            is AgentCommand.OpenWebUrls -> withContext(Dispatchers.IO) { phoneManager.openMultipleUrls(command.urls) }
            is AgentCommand.PhoneCall -> withContext(Dispatchers.IO) { phoneManager.makePhoneCall(command.contactOrNumber) }
            is AgentCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp: full contacts ContentProvider query + Levenshtein scoring used to run
            // on the main thread - hundreds of ms of UI freeze per message/call command.
            is AgentCommand.WhatsAppMessage -> withContext(Dispatchers.IO) { whatsappAssistant.sendMessageDirectly(command.contact, command.message) }
            is AgentCommand.WhatsAppCall -> withContext(Dispatchers.IO) { whatsappAssistant.makeCall(command.contact, command.video) }
            is AgentCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is AgentCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is AgentCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is AgentCommand.Remember -> memoryManager.rememberFact(command.text)
            is AgentCommand.Forget -> memoryManager.forgetFact(command.query)
            is AgentCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is AgentCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is AgentCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is AgentCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is AgentCommand.GoToBackground -> "Going to background."
        }
    }
}
