package com.phoneagent

import android.app.Application
import android.util.Log
import com.phoneagent.util.CrashHandler
import com.phoneagent.util.FileLogSink
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

class PhoneAgentApp : Application() {

    lateinit var logger: Logger
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
        logger.i(TAG, "PhoneAgent started, versionCode=${BuildConfig.VERSION_CODE}")
    }

    companion object {
        private const val TAG = "PhoneAgentApp"

        @Volatile
        private var instance: PhoneAgentApp? = null

        fun logger(): Logger = requireNotNull(instance) { "Application not initialised" }.logger

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
