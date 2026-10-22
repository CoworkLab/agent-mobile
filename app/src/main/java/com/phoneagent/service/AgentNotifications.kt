package com.phoneagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Notification channels and the persistent foreground-service notification.
 *
 * The foreground notification always carries a STOP action so the user has a second way to
 * cancel a task besides the overlay bubble.
 */
object AgentNotifications {

    const val CHANNEL_STATUS = "agent_status"
    const val CHANNEL_QUESTIONS = "agent_questions"
    const val CHANNEL_UPDATES = "agent_updates"

    const val FOREGROUND_NOTIFICATION_ID = 1001
    const val ACTION_STOP = "com.phoneagent.action.STOP"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Agent status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows what PhoneAgent is doing and a STOP button"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUESTIONS,
                "Agent questions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks you to confirm risky actions"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                "Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when a new version is available"
            },
        )
    }

    fun stopPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_STOP).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, com.phoneagent.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun buildForegroundNotification(
        context: Context,
        title: String,
        text: String,
        showStop: Boolean,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context, 1))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (showStop) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "STOP",
                        stopPendingIntent(context),
                    )
                }
            }
            .build()

    fun buildQuestionNotification(
        context: Context,
        question: String,
        notificationId: Int,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("PhoneAgent needs your answer")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent(context, notificationId))
            .build()
}
