package com.phoneagent.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import androidx.core.graphics.toColorInt
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.phoneagent.PhoneAgentApp
import com.phoneagent.service.AgentNotifications
import com.phoneagent.util.LogLevel
import kotlin.math.abs

/**
 * Always-visible STOP bubble drawn over other apps.
 *
 * Draggable, and tapping it broadcasts STOP. The whole thing is wrapped in try/catch because
 * window-manager calls can fail on vendor ROMs, and a broken overlay must never take the
 * agent down with it.
 */
class StopOverlay(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    fun show() {
        if (added) return
        if (!Settings.canDrawOverlays(context)) {
            PhoneAgentApp.safeLog(LogLevel.WARN, TAG, "Overlay permission missing; not showing")
            return
        }

        val wm = windowManager ?: return
        runCatching {
            val bubble = buildBubble()
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 320
            }

            wm.addView(bubble, layoutParams)
            view = bubble
            params = layoutParams
            added = true
            PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "STOP overlay shown")
        }.onFailure {
            PhoneAgentApp.safeLog(LogLevel.ERROR, TAG, "Failed to show overlay", it)
        }
    }

    fun hide() {
        val wm = windowManager ?: return
        val v = view ?: return
        runCatching { wm.removeView(v) }
            .onFailure { PhoneAgentApp.safeLog(LogLevel.WARN, TAG, "Failed to hide overlay", it) }
        view = null
        params = null
        added = false
    }

    fun isShowing(): Boolean = added

    private fun buildBubble(): View {
        val size = (56 * context.resources.displayMetrics.density).toInt()
        val text = TextView(context).apply {
            text = context.getString(com.phoneagent.R.string.overlay_stop)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#D32F2F".toColorInt())
                setStroke((2 * context.resources.displayMetrics.density).toInt(), Color.WHITE)
            }
        }
        val container = FrameLayout(context).apply {
            addView(
                text,
                FrameLayout.LayoutParams(size, size),
            )
        }
        attachTouchHandling(container)
        return container
    }

    /** Tap broadcasts STOP; drag moves the bubble. */
    private fun attachTouchHandling(container: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        container.setOnTouchListener { _, event ->
            val p = params
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p?.x ?: 0
                    initialY = p?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.rawX - touchX)
                    val dy = abs(event.rawY - touchY)
                    if (dx > TAP_SLOP || dy > TAP_SLOP) dragged = true
                    if (dragged && p != null) {
                        p.x = initialX + (event.rawX - touchX).toInt()
                        p.y = initialY + (event.rawY - touchY).toInt()
                        runCatching { windowManager?.updateViewLayout(view, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        container.performClick()
                        broadcastStop()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun broadcastStop() {
        PhoneAgentApp.safeLog(LogLevel.INFO, TAG, "STOP tapped on overlay")
        runCatching {
            context.sendBroadcast(
                Intent(AgentNotifications.ACTION_STOP).setPackage(context.packageName),
            )
        }.onFailure {
            PhoneAgentApp.safeLog(LogLevel.ERROR, TAG, "Failed to broadcast STOP", it)
        }
    }

    companion object {
        private const val TAG = "StopOverlay"
        private const val TAP_SLOP = 16f
    }
}
