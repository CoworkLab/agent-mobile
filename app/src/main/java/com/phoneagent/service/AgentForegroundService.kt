package com.phoneagent.service

import android.app.Service
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.phoneagent.PhoneAgentApp
import com.phoneagent.agent.AgentController
import com.phoneagent.agent.AgentState
import com.phoneagent.agent.AgentStatus
import com.phoneagent.agent.StepOutcome
import com.phoneagent.permissions.PermissionChecks
import com.phoneagent.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the agent loop so it survives the UI going to the background, and owns the
 * persistent STOP notification.
 *
 * M2 wires the loop to a placeholder step runner: the real planner arrives in M5/M6. The
 * point of this milestone is that the service, notification, STOP path and lifecycle work.
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var controller: AgentController

    override fun onCreate() {
        super.onCreate()
        AgentNotifications.createChannels(this)

        controller = AgentController(
            logger = PhoneAgentApp.logger(),
            scope = scope,
            stepRunner = ::placeholderStep,
            observer = { "M2 placeholder observation" },
        )
        sharedController.value = controller

        scope.launch {
            controller.status.collect { status -> onStatusChanged(status) }
        }

        PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "Agent service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TASK -> {
                promoteToForeground(getString(android.R.string.ok), "Working on your task")
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                if (goal.isNotBlank()) controller.start(goal)
            }

            ACTION_STOP -> {
                PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "STOP received from notification")
                controller.stop()
            }

            else -> promoteToForeground("PhoneAgent", "Ready")
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, text: String) {
        val notification = AgentNotifications.buildForegroundNotification(
            context = this,
            title = title,
            text = text,
            showStop = controller.isRunning,
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                AgentNotifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                type,
            )
        }.onFailure {
            PhoneAgentApp.safeLog(LogLevel.ERROR, TAG, "startForeground failed", it)
        }
    }

    private fun onStatusChanged(status: AgentStatus) {
        _status.value = status
        val text = when (status.state) {
            AgentState.IDLE -> "Ready"
            AgentState.PLANNING -> "Planning"
            AgentState.OBSERVING -> "Looking at the screen"
            AgentState.DECIDING -> "Deciding what to do"
            AgentState.GUARDING -> "Checking this is safe"
            AgentState.ACTING -> "Doing it"
            AgentState.VERIFYING -> "Checking it worked"
            AgentState.WAITING_USER -> status.question ?: "Waiting for your answer"
            AgentState.DONE -> status.summary ?: "Done"
            AgentState.FAILED -> status.error ?: "Something went wrong"
            AgentState.CANCELLED -> status.summary ?: "Stopped"
        }
        // POST_NOTIFICATIONS is revocable at runtime. Lint's MissingPermission detector only
        // recognises a direct checkSelfPermission on this receiver, so check it inline here
        // in addition to the app-level helper, and still guard the call.
        val notificationsAllowed =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (notificationsAllowed) {
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    AgentNotifications.FOREGROUND_NOTIFICATION_ID,
                    AgentNotifications.buildForegroundNotification(
                        context = this,
                        title = status.goal?.let { "PhoneAgent: $it" } ?: "PhoneAgent",
                        text = text,
                        showStop = status.state.isBusy,
                    ),
                )
            }.onFailure {
                PhoneAgentApp.safeLog(LogLevel.WARN, TAG, "Notification update failed", it)
            }
        }
        if (status.state.isTerminal) {
            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        }
    }

    /**
     * M2 placeholder: acknowledges the goal and finishes, so the whole start -> run -> stop
     * path is exercisable before the brain exists.
     */
    private suspend fun placeholderStep(
        goal: String,
        stepIndex: Int,
        observation: String,
    ): StepOutcome = StepOutcome.Done("Received your request: $goal")

    override fun onDestroy() {
        sharedController.value = null
        scope.cancel()
        PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "Agent service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AgentService"

        const val ACTION_START_TASK = "com.phoneagent.action.START_TASK"
        const val ACTION_STOP = AgentNotifications.ACTION_STOP
        const val EXTRA_GOAL = "goal"

        private val sharedController = MutableStateFlow<AgentController?>(null)

        private val _status = MutableStateFlow(AgentStatus())
        val status: StateFlow<AgentStatus> = _status.asStateFlow()

        fun controller(): AgentController? = sharedController.value

        fun startTask(context: Context, goal: String) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START_TASK)
                .putExtra(EXTRA_GOAL, goal)
            context.startForegroundService(intent)
        }

        fun stopTask(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
            controller()?.stop()
        }
    }
}
