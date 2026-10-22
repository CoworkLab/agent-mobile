package com.phoneagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phoneagent.PhoneAgentApp
import com.phoneagent.util.LogLevel

/**
 * Receives the STOP action from the foreground notification and from the overlay bubble.
 *
 * Registered in the manifest so it still works when the app process UI is gone.
 */
class StopActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AgentNotifications.ACTION_STOP) return
        PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "STOP broadcast received")
        AgentForegroundService.controller()?.stop()
    }

    companion object {
        private const val TAG = "StopActionReceiver"
    }
}
