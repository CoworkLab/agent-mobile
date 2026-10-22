package com.phoneagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.phoneagent.PhoneAgentApp
import com.phoneagent.util.LogLevel

/**
 * The "body" of the agent.
 *
 * M2 scope: prove the service connects, that the accessibility tree can be read, and that
 * screenshots and gestures are available on this device. Perception (M3) and the action
 * tools (M4) build on the primitives exposed here.
 */
class PhoneAgentAccessibilityService : AccessibilityService() {

    @Volatile
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        instance = this
        log(LogLevel.INFO, "Accessibility service connected; ${describeCapabilities()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // M2 only records events at DEBUG level; M3 turns these into UI-tree snapshots.
        log(
            LogLevel.VERBOSE,
            "event=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "pkg=${event.packageName}",
        )
    }

    override fun onInterrupt() {
        log(LogLevel.WARN, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        instance = null
        log(LogLevel.WARN, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    /** Capability report used by the self-test screen and the onboarding flow. */
    fun describeCapabilities(): String {
        val capabilities = serviceInfo?.capabilities ?: 0
        return buildString {
            append("canTakeScreenshot=").append(hasCapability(capabilities, AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT))
            append(" canPerformGestures=")
                .append(hasCapability(capabilities, AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES))
            append(" canRetrieveWindowContent=").append(serviceInfo?.canRetrieveWindowContent)
            append(" apiLevel=").append(Build.VERSION.SDK_INT)
        }
    }

    private fun hasCapability(capabilities: Int, flag: Int): Boolean =
        capabilities and flag == flag

    /** Bounds of the active window, used later to clamp gestures into the screen. */
    fun activeWindowBounds(): Rect? {
        val window = windows.firstOrNull { it.isActive } ?: return null
        return runCatching {
            val rect = Rect()
            window.getBoundsInScreen(rect)
            rect
        }.getOrNull()
    }

    /** Root node of the active window, or null when unavailable (e.g. FLAG_SECURE screens). */
    fun activeRoot() = runCatching {
        (windows.firstOrNull { it.isActive } ?: windows.firstOrNull())?.root
    }.getOrNull()

    fun isConnected(): Boolean = connected

    private fun log(level: LogLevel, message: String) {
        PhoneAgentApp.safeLog(level, TAG, message)
    }

    companion object {
        private const val TAG = "PhoneAgentA11y"

        @Volatile
        private var instance: PhoneAgentAccessibilityService? = null

        fun get(): PhoneAgentAccessibilityService? = instance

        fun isRunning(): Boolean = instance?.isConnected() == true
    }
}
