package com.phoneagent

import android.app.Application
import android.util.Log
import com.phoneagent.audit.AuditDatabase
import com.phoneagent.audit.AuditLog
import com.phoneagent.audit.RoomAuditLog
import com.phoneagent.brain.ProviderRepository
import com.phoneagent.brain.KeystoreSecretCipher
import com.phoneagent.brain.createProviderRepository
import com.phoneagent.memory.BundledSkills
import com.phoneagent.memory.InMemoryMemoryRepository
import com.phoneagent.memory.MemoryRepository
import com.phoneagent.memory.SkillLibrary
import com.phoneagent.util.CrashHandler
import com.phoneagent.util.FileLogSink
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

class PhoneAgentApp : Application() {

    lateinit var logger: Logger
        private set

    /**
     * The shared objects the screens need.
     *
     * Built once here rather than per screen, because two screens showing the same audit log or
     * skill list must be looking at the same instance. Creating a repository inside a ViewModel
     * factory would give each screen its own copy and the lists would silently disagree.
     */
    lateinit var providers: ProviderRepository
        private set

    lateinit var auditLog: AuditLog
        private set

    lateinit var memory: MemoryRepository
        private set

    lateinit var skills: SkillLibrary
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val fileSink = FileLogSink(this)
        logger = Logger(sink = fileSink)
        logger.minLevel = LogLevel.DEBUG
        CrashHandler.install(this, logger)
        CrashHandler.consumeLastCrash(this)?.let {
            logger.e("CrashHandler", "Previous session crashed:\n$it")
        }
        providers = createProviderRepository(this, KeystoreSecretCipher(logger), logger)
        auditLog = RoomAuditLog(AuditDatabase.build(this).auditDao(), logger)
        skills = SkillLibrary(BundledSkills.load(this, logger))
        memory = InMemoryMemoryRepository()

        logger.i(TAG, "PhoneAgent started, versionCode=${BuildConfig.VERSION_CODE}")
    }

    companion object {
        private const val TAG = "PhoneAgentApp"

        @Volatile
        private var instance: PhoneAgentApp? = null

        fun logger(): Logger = requireNotNull(instance) { "Application not initialised" }.logger

        fun providers(): ProviderRepository = requireNotNull(instance).providers

        fun auditLog(): AuditLog = requireNotNull(instance).auditLog

        fun memory(): MemoryRepository = requireNotNull(instance).memory

        fun skills(): SkillLibrary = requireNotNull(instance).skills

        /** Best-effort logging that never throws, safe to call from anywhere. */
        fun safeLog(level: LogLevel, tag: String, message: String, t: Throwable? = null) {
            val l = instance?.logger
            if (l != null) {
                l.log(level, tag, message, t)
            } else {
                Log.println(level.toAndroidPriority(), tag, message)
            }
        }
    }
}

private fun LogLevel.toAndroidPriority(): Int = when (this) {
    LogLevel.VERBOSE -> Log.VERBOSE
    LogLevel.DEBUG -> Log.DEBUG
    LogLevel.INFO -> Log.INFO
    LogLevel.WARN -> Log.WARN
    LogLevel.ERROR -> Log.ERROR
}
