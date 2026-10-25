package com.mobileagent.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires scheduled agent tasks. Deliveries survive reboots because the receiver
 * re-arms remaining alarms when the device boots.
 */
class AgentAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val taskId = intent.getStringExtra("task_id") ?: return
                Log.i("AgentSchedule", "Firing scheduled task $taskId")
                ScheduleStore.onFired(context, taskId)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i("AgentSchedule", "Re-arming scheduled tasks after boot")
                runCatching { ScheduleStore.refresh(context) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.mobileagent.app.action.FIRE_SCHEDULED_TASK"
    }
}
