package com.phoneagent.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.phoneagent.PhoneAgentApp
import com.phoneagent.util.LogLevel

/**
 * Reads notifications so a task can pick up one-time codes and confirmations.
 *
 * Notification text is untrusted input: it is recorded here and, from M3 onward, is passed
 * to the model only inside `<untrusted_screen_content>` markers.
 */
class PhoneAgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        log(LogLevel.INFO, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        log(LogLevel.WARN, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        log(
            LogLevel.DEBUG,
            "notification pkg=${sbn.packageName} title=${title.orEmpty()} text=${text.orEmpty()}",
        )
    }

    private fun log(level: LogLevel, message: String) {
        PhoneAgentApp.safeLog(level, TAG, message)
    }

    companion object {
        private const val TAG = "NotificationListener"
    }
}
