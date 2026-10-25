package com.mobileagent.app.tools

import android.accessibilityservice.AccessibilityService
import android.os.Build
import org.json.JSONObject

/**
 * Global navigation actions: back, home, recents and other system-level gestures.
 */
object GlobalActionTools {

    val NAMES: Set<String> = setOf("back", "home", "recents", "global_action")

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t("back", "Press the system Back button.", listOf()),
        ToolRegistry.t("home", "Press the system Home button.", listOf()),
        ToolRegistry.t("recents", "Open the recent apps screen.", listOf()),
        ToolRegistry.t(
            "global_action", "Trigger other system actions.",
            ToolRegistry.p("action", "STRING",
                "notifications | quick_settings | lock_screen | power_dialog | split_screen | take_screenshot | accessibility_menu",
                req = true,
                enum = listOf("notifications", "quick_settings", "lock_screen", "power_dialog", "split_screen", "take_screenshot", "accessibility_menu"))
        )
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val svc = ctx.requireAccessibility()
        val perform: (Int) -> Boolean = { svc.performGlobalAction(it) }
        return when (name) {
            "back" -> ctx.ok("pressed" to perform(AccessibilityService.GLOBAL_ACTION_BACK), "action" to "back")
            "home" -> ctx.ok("pressed" to perform(AccessibilityService.GLOBAL_ACTION_HOME), "action" to "home")
            "recents" -> ctx.ok("pressed" to perform(AccessibilityService.GLOBAL_ACTION_RECENTS), "action" to "recents")
            "global_action" -> {
                val action = args.getString("action")
                val id = when (action) {
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    "accessibility_menu" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
                    "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        else throw ToolException("lock_screen needs Android 9+.")
                    "take_screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                        else throw ToolException("take_screenshot needs Android 9+.")
                    "split_screen" -> if (Build.VERSION.SDK_INT >= 29) AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                        else throw ToolException("split_screen needs Android 10+.")
                    else -> throw ToolException("Unknown global action '$action'.")
                }
                ctx.ok("performed" to perform(id), "action" to action)
            }
            else -> ctx.fail("Unknown global tool '$name'.")
        }
    }
}
