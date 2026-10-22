package com.phoneagent.util

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
)

/**
 * In-memory ring buffer of log entries plus an optional sink.
 *
 * Pure Kotlin so it is unit testable on the JVM. The Android file sink is injected
 * through [sink] so tests can capture writes instead of touching the filesystem.
 */
class Logger(
    private val capacity: Int = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (LogEntry) -> Unit = {},
) {
    private val nextId = AtomicLong(0)
    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val entry = LogEntry(
            id = nextId.incrementAndGet(),
            timestampMs = clock(),
            level = level,
            tag = tag,
            message = SecretMasker.mask(message),
            throwable = throwable?.let { SecretMasker.mask(renderThrowable(it)) },
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        runCatching { sink(entry) }
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)

    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Whole buffer as copyable text. Secrets are already masked on the way in. */
    fun export(): String = snapshot().joinToString("\n") { it.render() }

    private fun renderThrowable(t: Throwable): String =
        buildString {
            append(t::class.java.name).append(": ").append(t.message)
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 5) {
                append("\n  caused by ").append(cause::class.java.name)
                    .append(": ").append(cause.message)
                cause = cause.cause
                depth++
            }
        }
}

fun LogEntry.render(): String {
    val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        .format(java.util.Date(timestampMs))
    return buildString {
        append(ts).append(' ')
        append(level.name.padEnd(5)).append(' ')
        append(tag).append(": ").append(message)
        throwable?.let { append('\n').append(it) }
    }
}

/**
 * Masks anything that looks like an API key or bearer token so logs and bug reports never
 * leak credentials.
 */
object SecretMasker {
    private val patterns = listOf(
        Regex("""\bAIza[0-9A-Za-z_\-]{10,}"""),
        Regex("""\bAQ\.[0-9A-Za-z_\-]{10,}"""),
        Regex("""\bsk-[0-9A-Za-z_\-]{10,}"""),
        Regex("""\bgh[pousr]_[0-9A-Za-z]{20,}"""),
        Regex("""(?i)\b(bearer)\s+[0-9A-Za-z._\-]{10,}"""),
        Regex("""(?i)\b(api[_-]?key|token|password|secret)\b\s*[=:]\s*"?([^\s",]{6,})"?"""),
    )

    fun mask(input: String): String {
        var out = input
        for (p in patterns) {
            out = p.replace(out) { m ->
                if (m.groupValues.size >= 2 && m.groupValues[1].isNotEmpty()) {
                    "${m.groupValues[1]}=***"
                } else {
                    "***"
                }
            }
        }
        return out
    }
}
