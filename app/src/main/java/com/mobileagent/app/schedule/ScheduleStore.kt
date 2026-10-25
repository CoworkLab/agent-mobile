package com.mobileagent.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lightweight scheduled-task store backed by SharedPreferences + AlarmManager.
 * A scheduled task fires the agent's conversation service with the stored instruction.
 */
object ScheduleStore {

    private const val PREFS = "agent_scheduled_tasks"
    private const val KEY = "tasks_json"

    data class Task(
        val id: String,
        val instruction: String,
        val atMillis: Long,
        val repeat: String // none | hourly | daily | weekly
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("instruction", instruction)
            .put("next_run", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(atMillis)))
            .put("repeat", repeat)
    }

    fun all(context: Context): List<Task> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = ArrayList<Task>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Task(o.optString("id"), o.optString("instruction"), o.optLong("at"), o.optString("repeat")))
        }
        return out.sortedBy { it.atMillis }
    }

    fun add(context: Context, instruction: String, atMillis: Long, repeat: String): Task {
        val task = Task(UUID.randomUUID().toString().take(8), instruction, atMillis, repeat)
        val tasks = all(context).toMutableList()
        tasks.add(task)
        persist(context, tasks)
        arm(context, task)
        return task
    }

    fun cancel(context: Context, id: String): Boolean {
        val tasks = all(context)
        val task = tasks.firstOrNull { it.id == id } ?: return false
        unarm(context, task)
        persist(context, tasks.filterNot { it.id == id })
        return true
    }

    /** Re-arm after reboot or execution; removes expired one-time tasks. */
    fun refresh(context: Context) {
        val now = System.currentTimeMillis()
        val keep = all(context).filter { it.repeat != "none" || it.atMillis > now }
        persist(context, keep)
        keep.forEach { arm(context, it) }
    }

    fun onFired(context: Context, id: String) {
        val task = all(context).firstOrNull { it.id == id } ?: return
        deliver(context, task)
        if (task.repeat == "none") {
            persist(context, all(context).filterNot { it.id == id })
        } else {
            val next = nextOccurrence(task.repeat, task.atMillis)
            val moved = task.copy(atMillis = next)
            persist(context, all(context).map { if (it.id == id) moved else it })
            arm(context, moved)
        }
    }

    fun nextOccurrence(repeat: String, fromMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        when (repeat) {
            "hourly" -> cal.add(Calendar.HOUR, 1)
            "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            else -> return fromMillis
        }
        return cal.timeInMillis
    }

    private fun deliver(context: Context, task: Task) {
        val intent = Intent(context, com.mobileagent.app.AgentConversationService::class.java).apply {
            action = com.mobileagent.app.AgentConversationService.ACTION_SCHEDULED_TASK
            putExtra("instruction", task.instruction)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }.onFailure {
            // Service not running: fall back to a visible notification.
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val pi = PendingIntent.getActivity(
                    context, task.id.hashCode(),
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?: Intent().setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notif = if (Build.VERSION.SDK_INT >= 26) {
                    val channel = android.app.NotificationChannel(
                        "scheduled_tasks", "Scheduled tasks", android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    nm.createNotificationChannel(channel)
                    android.app.Notification.Builder(context, "scheduled_tasks")
                        .setContentTitle("Scheduled task")
                        .setContentText(task.instruction.take(120))
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentIntent(pi)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    android.app.Notification.Builder(context)
                        .setContentTitle("Scheduled task")
                        .setContentText(task.instruction.take(120))
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentIntent(pi)
                        .build()
                }
                nm.notify(task.id.hashCode(), notif)
            }
        }
    }

    private fun arm(context: Context, task: Task) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentAlarmReceiver::class.java).apply {
            action = AgentAlarmReceiver.ACTION_FIRE
            putExtra("task_id", task.id)
        }
        val pi = PendingIntent.getBroadcast(
            context, task.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.atMillis, pi)
        }
    }

    private fun unarm(context: Context, task: Task) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentAlarmReceiver::class.java).apply { action = AgentAlarmReceiver.ACTION_FIRE }
        val pi = PendingIntent.getBroadcast(
            context, task.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun persist(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("instruction", t.instruction)
                .put("at", t.atMillis).put("repeat", t.repeat))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
