package com.mobileagent.app.tools

import android.Manifest
import android.content.Intent
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telecom.TelecomManager
import android.os.Build
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Communication & personal data: SMS, calls, contacts, call log, calendar, alarms,
 * timers, sharing and email drafts. Every permission-sensitive tool reports a structured
 * fix path instead of failing silently.
 */
object CommunicationTools {

    val NAMES: Set<String> = setOf(
        "send_sms", "read_sms", "make_call", "end_call", "read_contacts", "read_call_log",
        "read_calendar", "create_event", "set_alarm", "set_timer", "share_content", "send_email"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t("send_sms", "Send an SMS message.",
            ToolRegistry.p("number", "STRING", "phone number (or contact name if resolvable)", req = true),
            ToolRegistry.p("text", "STRING", req = true)),
        ToolRegistry.t("read_sms", "Read recent SMS messages.",
            ToolRegistry.p("limit", "INTEGER", "default 20"),
            ToolRegistry.p("from", "STRING", "filter by number or name"),
            ToolRegistry.p("query", "STRING", "filter by body text")),
        ToolRegistry.t("make_call", "Place a phone call to a number or a contact name.",
            ToolRegistry.p("number", "STRING", "number or contact name", req = true)),
        ToolRegistry.t("end_call", "End the current active call (Android 9+).", listOf()),
        ToolRegistry.t("read_contacts", "Search the contact list.",
            ToolRegistry.p("query", "STRING", "name/number filter; empty = all"),
            ToolRegistry.p("limit", "INTEGER", "default 20")),
        ToolRegistry.t("read_call_log", "Read recent calls.",
            ToolRegistry.p("limit", "INTEGER", "default 20"),
            ToolRegistry.p("type", "STRING", "all | missed | incoming | outgoing",
                enum = listOf("all", "missed", "incoming", "outgoing"))),
        ToolRegistry.t("read_calendar", "Read calendar events in a time range.",
            ToolRegistry.p("from", "STRING", "ISO datetime, e.g. 2026-01-01T00:00:00", req = true),
            ToolRegistry.p("to", "STRING", "ISO datetime", req = true)),
        ToolRegistry.t("create_event", "Create a calendar event.",
            ToolRegistry.p("title", "STRING", req = true),
            ToolRegistry.p("start", "STRING", "ISO datetime", req = true),
            ToolRegistry.p("end", "STRING", "ISO datetime (default: start + 1h)"),
            ToolRegistry.p("location", "STRING"),
            ToolRegistry.p("description", "STRING")),
        ToolRegistry.t("set_alarm", "Set an alarm via the system clock app.",
            ToolRegistry.p("hour", "INTEGER", "0-23", req = true),
            ToolRegistry.p("minute", "INTEGER", "0-59", req = true),
            ToolRegistry.p("label", "STRING"),
            ToolRegistry.p("days", "ARRAY", "repeat days 1=Sun..7=Sat")),
        ToolRegistry.t("set_timer", "Start a countdown timer.",
            ToolRegistry.p("seconds", "INTEGER", req = true),
            ToolRegistry.p("label", "STRING")),
        ToolRegistry.t("share_content", "Share text or a file via the system share sheet.",
            ToolRegistry.p("text", "STRING"),
            ToolRegistry.p("file_path", "STRING"),
            ToolRegistry.p("package", "STRING", "send directly to this app")),
        ToolRegistry.t("send_email", "Open an email draft.",
            ToolRegistry.p("to", "STRING", req = true),
            ToolRegistry.p("subject", "STRING"),
            ToolRegistry.p("body", "STRING"))
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val context = ctx.context
        return when (name) {
            "send_sms" -> {
                if (!ctx.hasPermission(Manifest.permission.SEND_SMS)) ctx.permissionError("send_sms", Manifest.permission.SEND_SMS)
                val raw = args.getString("number")
                val number = resolveNumber(ctx, raw)
                    ?: throw ToolException("Could not resolve '$raw' to a phone number. Use read_contacts to search contacts.")
                val text = args.getString("text")
                if (text.isBlank()) throw ToolException("SMS text is empty.")
                val sm = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
                         else @Suppress("DEPRECATION") SmsManager.getDefault()
                // Long messages fail silently with sendTextMessage; divide into multipart parts.
                runCatching {
                    if (text.length <= 160) {
                        sm.sendTextMessage(number, null, text, null, null)
                    } else {
                        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
                    }
                }.onFailure { e ->
                    throw ToolException(
                        "SMS send failed: ${e.message}. Check that a SIM is inserted, mobile network is available, " +
                            "and the number is valid (with country code if needed)."
                    )
                }
                ctx.ok("sent" to true, "to" to number, "text" to text.take(120))
            }
            "read_sms" -> {
                if (!ctx.hasPermission(Manifest.permission.READ_SMS)) ctx.permissionError("read_sms", Manifest.permission.READ_SMS)
                val limit = args.optInt("limit", 20).coerceIn(1, 100)
                val from = args.optString("from", "").lowercase()
                val query = args.optString("query", "").lowercase()
                val arr = JSONArray()
                querySms(context, limit * 3).forEach { m ->
                    if (arr.length() >= limit) return@forEach
                    if (from.isNotBlank() && !(m.address.lowercase().contains(from))) return@forEach
                    if (query.isNotBlank() && !m.body.lowercase().contains(query)) return@forEach
                    arr.put(m.toJson())
                }
                ctx.ok("count" to arr.length(), "messages" to arr)
            }
            "make_call" -> {
                if (!ctx.hasPermission(Manifest.permission.CALL_PHONE)) ctx.permissionError("make_call", Manifest.permission.CALL_PHONE)
                val raw = args.getString("number")
                val number = resolveNumber(ctx, raw)
                    ?: throw ToolException("Could not resolve '$raw' to a phone number. Use read_contacts to search contacts.")
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val placed = runCatching { context.startActivity(intent) }.isSuccess
                ctx.ok("calling" to placed, "number" to number)
            }
            "end_call" -> {
                if (Build.VERSION.SDK_INT < 28) return ctx.fail("end_call needs Android 9+.")
                if (!ctx.hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) ctx.permissionError("end_call", Manifest.permission.ANSWER_PHONE_CALLS)
                val tm = context.getSystemService(TelecomManager::class.java)
                val ended = runCatching { tm.endCall() }.getOrDefault(false)
                ctx.ok("ended" to ended)
            }
            "read_contacts" -> {
                if (!ctx.hasPermission(Manifest.permission.READ_CONTACTS)) ctx.permissionError("read_contacts", Manifest.permission.READ_CONTACTS)
                val query = args.optString("query", "").trim()
                val limit = args.optInt("limit", 20).coerceIn(1, 100)
                val arr = JSONArray()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { c ->
                    while (c.moveToNext() && arr.length() < limit) {
                        val n = c.getString(0) ?: continue
                        val num = c.getString(1) ?: continue
                        if (query.isNotBlank() && !n.lowercase().contains(query.lowercase()) && !num.contains(query)) continue
                        arr.put(JSONObject().put("name", n).put("number", num))
                    }
                }
                ctx.ok("count" to arr.length(), "contacts" to arr)
            }
            "read_call_log" -> {
                if (!ctx.hasPermission(Manifest.permission.READ_CALL_LOG)) ctx.permissionError("read_call_log", Manifest.permission.READ_CALL_LOG)
                val limit = args.optInt("limit", 20).coerceIn(1, 100)
                val typeFilter = args.optString("type", "all")
                val sel = when (typeFilter) {
                    "missed" -> "${CallLog.Calls.TYPE}=?"
                    "incoming" -> "${CallLog.Calls.TYPE}=?"
                    "outgoing" -> "${CallLog.Calls.TYPE}=?"
                    else -> null
                }
                val selArgs = when (typeFilter) {
                    "missed" -> arrayOf(CallLog.Calls.MISSED_TYPE.toString())
                    "incoming" -> arrayOf(CallLog.Calls.INCOMING_TYPE.toString())
                    "outgoing" -> arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
                    else -> null
                }
                val arr = JSONArray()
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, null, sel, selArgs,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext() && arr.length() < limit) {
                        val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: continue
                        val t = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val callType = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        arr.put(JSONObject()
                            .put("number", number)
                            .put("cached_name", c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "")
                            .put("type", when (callType) {
                                CallLog.Calls.MISSED_TYPE -> "missed"
                                CallLog.Calls.INCOMING_TYPE -> "incoming"
                                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                else -> "other"
                            })
                            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))))
                    }
                }
                ctx.ok("count" to arr.length(), "calls" to arr)
            }
            "read_calendar" -> {
                if (!ctx.hasPermission(Manifest.permission.READ_CALENDAR)) ctx.permissionError("read_calendar", Manifest.permission.READ_CALENDAR)
                val from = parseIso(args.getString("from"))
                val to = parseIso(args.getString("to"))
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(from.time.toString()).appendPath(to.time.toString()).build()
                val arr = JSONArray()
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION,
                        CalendarContract.Instances.DESCRIPTION
                    ), null, null, "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { c ->
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    while (c.moveToNext() && arr.length() < 50) {
                        arr.put(JSONObject()
                            .put("title", c.getString(0) ?: "(no title)")
                            .put("start", fmt.format(Date(c.getLong(1))))
                            .put("end", fmt.format(Date(c.getLong(2))))
                            .put("location", c.getString(3) ?: "")
                            .put("description", (c.getString(4) ?: "").take(300)))
                    }
                }
                ctx.ok("count" to arr.length(), "events" to arr)
            }
            "create_event" -> {
                if (!ctx.hasPermission(Manifest.permission.WRITE_CALENDAR)) ctx.permissionError("create_event", Manifest.permission.WRITE_CALENDAR)
                val title = args.getString("title")
                val start = parseIso(args.getString("start"))
                val end = args.optString("end", "").takeIf { it.isNotBlank() }?.let { parseIso(it) }
                    ?: (start.clone() as Calendar).apply { add(Calendar.HOUR, 1) }
                val location = args.optString("location", "").takeIf { it.isNotBlank() }
                val description = args.optString("description", "").takeIf { it.isNotBlank() }
                // Direct calendar insert (WRITE_CALENDAR is already required above) - no editor UI needed.
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, start.timeInMillis)
                    put(CalendarContract.Events.DTEND, end.timeInMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                    put(CalendarContract.Events.CALENDAR_ID, pickWritableCalendarId(context))
                }
                val inserted = runCatching {
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }.getOrNull()
                if (inserted != null) {
                    ctx.ok("created" to true, "event_uri" to inserted.toString(),
                        "start" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(start.timeInMillis)))
                } else {
                    // Fallback: open the calendar editor so the user can save it manually.
                    val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.timeInMillis)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    description?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
                    runCatching { context.startActivity(i) }
                        .onFailure { throw ToolException("Could not create the event directly (no writable calendar found) and the editor failed to open: ${it.message}") }
                    ctx.ok("draft_opened" to true, "note" to "No writable calendar accepted the direct insert; the calendar editor is open. Confirm it with UI tools.")
                }
            }
            "set_alarm" -> {
                val hour = args.optInt("hour", -1).let { if (it in 0..23) it else throw ToolException("hour must be 0-23.") }
                val minute = args.optInt("minute", -1).let { if (it in 0..59) it else throw ToolException("minute must be 0-59.") }
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                args.optString("label", "").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                args.optJSONArray("days")?.let { days ->
                    val list = ArrayList<Int>()
                    for (k in 0 until days.length()) list.add(days.optInt(k, 0))
                    if (list.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, list)
                }
                val ok = runCatching { context.startActivity(i) }.isSuccess
                ctx.ok("alarm_set" to ok, "time" to String.format(Locale.US, "%02d:%02d", hour, minute))
            }
            "set_timer" -> {
                val seconds = args.optInt("seconds", 0).let { if (it > 0) it else throw ToolException("seconds must be > 0.") }
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                args.optString("label", "").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                val ok = runCatching { context.startActivity(i) }.isSuccess
                ctx.ok("timer_set" to ok, "seconds" to seconds)
            }
            "share_content" -> {
                val text = args.optString("text", "")
                val filePath = args.optString("file_path", "")
                if (text.isBlank() && filePath.isBlank()) throw ToolException("Provide 'text' or 'file_path'.")
                val i = Intent().apply {
                    action = Intent.ACTION_SEND
                    if (filePath.isNotBlank()) {
                        val f = File(if (filePath.startsWith("file://")) Uri.parse(filePath).path ?: filePath else filePath)
                        if (!f.exists()) throw ToolException("File not found: ${f.absolutePath}")
                        type = guessShareMime(f.name)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", f
                        )
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                    } else {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    args.optString("package", "").takeIf { it.isNotBlank() }?.let { setPackage(it) }
                }
                runCatching {
                    context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { e ->
                    throw ToolException("Could not open the share sheet: ${e.message}")
                }
                ctx.ok("share_opened" to true, "file" to filePath.takeIf { it.isNotBlank() })
            }
            "send_email" -> {
                val to = args.getString("to")
                val i = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$to")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    args.optString("subject", "").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    args.optString("body", "").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
                val launched = runCatching { context.startActivity(i) }.isSuccess
                ctx.ok("draft_opened" to launched)
            }
            else -> ctx.fail("Unknown communication tool '$name'.")
        }
    }

    // ---- helpers -----------------------------------------------------------

    data class SmsRow(val address: String, val body: String, val date: Long, val box: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("from", address)
            .put("body", body.take(500))
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(date)))
            .put("box", box)
    }

    private fun querySms(context: Context, limit: Int): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        val boxes = listOf("inbox" to Telephony.Sms.Inbox.CONTENT_URI, "sent" to Telephony.Sms.Sent.CONTENT_URI)
        for ((box, uri) in boxes) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, "${Telephony.Sms.DATE} DESC")
                    ?.use { c ->
                        while (c.moveToNext() && out.size < limit) {
                            out.add(SmsRow(
                                address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                                body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                                date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                                box = box
                            ))
                        }
                    }
            }
        }
        return out.sortedByDescending { it.date }
    }

    /** Resolve a contact name to a number, or pass through anything that already looks like a number. */
    private fun resolveNumber(ctx: ToolContext, raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.any { it.isDigit() } && !cleaned.contains(' ')) return cleaned
        if (ctx.hasPermission(Manifest.permission.READ_CONTACTS)) {
            ctx.context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleaned%"), null
            )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        }
        return null
    }

    fun parseIso(value: String): Calendar {
        val cleaned = value.trim().replace("Z", "+0000")
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (p in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.isLenient = false
                val d = fmt.parse(cleaned) ?: continue
                return Calendar.getInstance().apply { time = d }
            }
        }
        throw ToolException("Could not parse datetime '$value'. Use ISO format like 2026-01-01T09:00:00.")
    }

    /** First writable calendar account id for direct event inserts, or null when none exists. */
    private fun pickWritableCalendarId(context: Context): Long {
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: 1L
    }

    private fun guessShareMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "vcf" -> "text/vcard"
        else -> "application/octet-stream"
    }
}
