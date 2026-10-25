package com.mobileagent.app

import android.app.Application
import com.mobileagent.app.security.SecurityManager
import com.mobileagent.app.audio.VoskModelManager
import com.mobileagent.app.automation.command.CommandExecutor
import com.mobileagent.app.automation.contacts.ContactResolver
import com.mobileagent.app.automation.phone.PhoneControlManager
import com.mobileagent.app.automation.whatsapp.WhatsAppAssistant
import com.mobileagent.app.automation.youtube.YouTubeAutomationManager
import com.mobileagent.app.data.memory.MemoryDatabase
import com.mobileagent.app.data.memory.MemoryManager
import com.mobileagent.app.data.memory.MemoryRepository
import com.mobileagent.app.data.preferences.AppPreferences
import com.mobileagent.app.data.preferences.YouTubePreferences
import com.mobileagent.app.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var youTubePreferences: YouTubePreferences
        private set

    lateinit var youTubeAutomationManager: YouTubeAutomationManager
        private set

    lateinit var contactResolver: ContactResolver
        private set

    lateinit var phoneControlManager: PhoneControlManager
        private set

    lateinit var whatsAppAssistant: WhatsAppAssistant
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    override fun onCreate() {
        super.onCreate()
        SecurityManager.verify(this)
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)

        // Memory System
        memoryDatabase = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDatabase.memoryDao())
        memoryManager = MemoryManager(memoryRepository, preferences, chatRepository, appScope)

        // Automation Systems
        youTubePreferences = YouTubePreferences(this)
        youTubeAutomationManager = YouTubeAutomationManager(this, youTubePreferences)
        contactResolver = ContactResolver(this)
        phoneControlManager = PhoneControlManager(this, contactResolver)
        whatsAppAssistant = WhatsAppAssistant(this, contactResolver)

        // Command Engine
        commandExecutor = CommandExecutor(
            phoneManager = phoneControlManager,
            youtubeManager = youTubeAutomationManager,
            whatsappAssistant = whatsAppAssistant,
            memoryManager = memoryManager
        )

        VoskModelManager.init(this, CoroutineScope(Dispatchers.Default))

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.mobileagent.app.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (startedActivities == 1) _isAppForeground.value = true
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentResumedActivity = activity
                com.mobileagent.app.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) _isAppForeground.value = false
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
        })
    }

    var currentResumedActivity: android.app.Activity? = null
        private set

    /**
     * Device profile cache (spec: auto device profile). Holds the stable static facts
     * (SDK, model, screen, hardware features) keyed by Build.FINGERPRINT so the profile
     * survives across sessions but rebuilds after OS/OTA changes. Permission and service
     * states are NEVER cached here - they are probed live before every use.
     */
    @Volatile
    var deviceProfileCache: String = ""
        private set

    private var deviceProfileFingerprint: String = ""

    fun refreshDeviceProfileCache(profile: String) {
        val fingerprint = android.os.Build.FINGERPRINT + "|" + android.os.Build.VERSION.SDK_INT
        synchronized(this) {
            if (fingerprint != deviceProfileFingerprint || deviceProfileCache.isBlank()) {
                deviceProfileFingerprint = fingerprint
                deviceProfileCache = profile
            }
        }
    }

    fun moveAppToBack(): Boolean {
        val act = currentResumedActivity ?: return false
        return try {
            act.moveTaskToBack(true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val _isAppForeground = MutableStateFlow(false)
        val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

        lateinit var instance: AgentApp
            private set
    }
}

