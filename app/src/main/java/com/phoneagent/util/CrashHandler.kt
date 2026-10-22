package com.phoneagent.util

import android.content.Context
import java.io.File

/**
 * Records a crash trace to disk before the process dies, so the Log Viewer can show it on
 * the next launch.
 *
 * Chains to the previously installed handler so Android's normal crash reporting still runs.
 */
class CrashHandler private constructor(private val filesDir: File, private val logger: Logger) {

    private fun handle(thread: Thread, throwable: Throwable) {
        runCatching {
            logger.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            File(filesDir, FILE_NAME).writeText(
                buildString {
                    append("thread=").append(thread.name).append('\n')
                    append("time=").append(System.currentTimeMillis()).append('\n')
                    append(throwable.stackTraceToString())
                },
            )
        }
    }

    companion object {
        private const val TAG = "CrashHandler"
        private const val FILE_NAME = "last_crash.txt"

        fun install(context: Context, logger: Logger) {
            val handler = CrashHandler(context.filesDir, logger)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handler.handle(thread, throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }

        /** Returns the recorded crash trace and clears it, so it is reported only once. */
        fun consumeLastCrash(context: Context): String? {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            runCatching { f.delete() }
            return text
        }
    }
}
