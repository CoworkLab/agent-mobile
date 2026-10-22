package com.phoneagent.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends log entries to a rotating file under the app's private files directory.
 * Never throws: logging must not be able to crash the app.
 */
class FileLogSink(
    private val context: Context,
    private val fileName: String = "phoneagent.log",
    private val maxBytes: Long = 2L * 1024 * 1024,
) : (LogEntry) -> Unit {

    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val file: File
        get() = File(context.filesDir, fileName)

    override fun invoke(entry: LogEntry) {
        runCatching {
            synchronized(lock) {
                val f = file
                if (f.exists() && f.length() > maxBytes) rotate(f)
                f.appendText(timestamp.format(Date(entry.timestampMs)) + " " +
                    entry.level.name.padEnd(5) + " " + entry.tag + ": " + entry.message + "\n")
            }
        }.onFailure { Log.w("FileLogSink", "log write failed", it) }
    }

    fun readAll(): String = runCatching {
        synchronized(lock) { if (file.exists()) file.readText() else "" }
    }.getOrDefault("")

    fun clear() {
        runCatching { synchronized(lock) { file.delete() } }
    }

    private fun rotate(f: File) {
        val previous = File(f.parentFile, "$fileName.1")
        runCatching { previous.delete() }
        runCatching { f.renameTo(previous) }
    }
}
