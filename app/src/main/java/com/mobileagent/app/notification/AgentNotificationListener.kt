package com.mobileagent.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Captures active notifications so the agent can read and act on them
 * (read_notifications / notification_action tools).
 */
class AgentNotificationListener : NotificationListenerService() {

    data class Captured(
        val id: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val actions: List<String>,
        val postedAt: Long,
        val sbn: StatusBarNotification
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("notification_id", id)
            .put("package", packageName)
            .put("app", appLabel)
            .put("title", title)
            .put("text", text.take(300))
            .put("actions", actions)
            .put("posted", postedAt)
    }

    override fun onListenerConnected() {
        isBound = true
        activeNotifications?.forEach { store(it) }
    }

    override fun onListenerDisconnected() {
        isBound = false
        lock.lock()
        try { latest.clear() } finally { lock.unlock() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isOngoingSystem(sbn)) return
        store(sbn)
        trim()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lock.lock()
        try { latest.remove(keyOf(sbn)) } finally { lock.unlock() }
    }

    private fun store(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val actions = sbn.notification.actions
            ?.mapNotNull { it.title?.toString() }
            .orEmpty()
        val captured = Captured(
            id = keyOf(sbn),
            packageName = sbn.packageName,
            appLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName },
            title = title,
            text = text,
            actions = actions.take(6),
            postedAt = sbn.postTime,
            sbn = sbn
        )
        lock.lock()
        try { latest[keyOf(sbn)] = captured } finally { lock.unlock() }
    }

    private fun trim() {
        lock.lock()
        try {
            if (latest.size > MAX) {
                latest.entries.sortedBy { it.value.postedAt }.take(latest.size - MAX).forEach { latest.remove(it.key) }
            }
        } finally { lock.unlock() }
    }

    private fun isOngoingSystem(sbn: StatusBarNotification): Boolean =
        sbn.packageName == "android" || sbn.packageName == packageName ||
            (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            sbn.packageName.startsWith("com.android.systemui")

    companion object {
        private const val MAX = 60
        private val lock = Object()
        private val latest = LinkedHashMap<String, Captured>()

        @Volatile
        var isBound: Boolean = false
            private set

        fun snapshot(): List<Captured> {
            synchronized(lock) { return latest.values.sortedByDescending { it.postedAt }.toList() }
        }

        fun byId(id: String): Captured? = synchronized(lock) { latest[id] }

        fun dismiss(n: Captured): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 31) cancelNotification(n.sbn.key) else cancelNotification(n.packageName, n.sbn.tag, n.sbn.id)
            true
        }.getOrDefault(false)

        fun open(n: Captured): Boolean = runCatching {
            val pi = n.sbn.notification.contentIntent ?: return false
            pi.send()
            true
        }.getOrDefault(false)

        fun triggerAction(n: Captured, label: String): Boolean = runCatching {
            val action = n.sbn.notification.actions
                ?.firstOrNull { it.title?.toString()?.lowercase()?.contains(label.lowercase()) == true }
                ?: n.sbn.notification.actions?.firstOrNull()
                ?: return false
            action.actionIntent.send()
            true
        }.getOrDefault(false)

        private fun keyOf(sbn: StatusBarNotification): String = "${sbn.packageName}|${sbn.id}|${sbn.tag ?: ""}"
    }
}
