package com.phoneagent.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

enum class PermissionId {
    ACCESSIBILITY,
    OVERLAY,
    NOTIFICATIONS,
    NOTIFICATION_LISTENER,
    BATTERY_OPTIMIZATION,
    INSTALL_PACKAGES,
}

data class PermissionStatus(
    val id: PermissionId,
    val granted: Boolean,
    val title: String,
    val why: String,
)

/**
 * Pure-ish permission probing. Every Android call is wrapped so a missing API, a vendor
 * ROM quirk or a SecurityException degrades to "not granted" instead of crashing.
 */
object PermissionChecks {

    fun accessibilityServiceComponent(context: Context): ComponentName =
        ComponentName(context, "com.phoneagent.service.PhoneAgentAccessibilityService")

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = accessibilityServiceComponent(context)
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false

        if (enabled.isBlank()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component == expected) return true
        }
        return false
    }

    fun isOverlayGranted(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun areNotificationsGranted(context: Context): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }.getOrDefault(false)

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = ComponentName(context, "com.phoneagent.service.PhoneAgentNotificationListener")
        val flat = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
        }.getOrNull() ?: return false
        if (flat.isBlank()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component.packageName == expected.packageName &&
                component.className == expected.className
            ) {
                return true
            }
        }
        return false
    }

    /**
     * True when the app is exempt from battery optimisation, or on API levels where the
     * concept does not exist. Never throws.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE)
                as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    fun canInstallPackages(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun snapshot(context: Context): List<PermissionStatus> = listOf(
        PermissionStatus(
            id = PermissionId.ACCESSIBILITY,
            granted = isAccessibilityEnabled(context),
            title = "Accessibility service",
            why = "Lets PhoneAgent see the screen and tap, swipe and type for you.",
        ),
        PermissionStatus(
            id = PermissionId.OVERLAY,
            granted = isOverlayGranted(context),
            title = "Display over other apps",
            why = "Shows the STOP button and asks you questions while other apps are open.",
        ),
        PermissionStatus(
            id = PermissionId.NOTIFICATIONS,
            granted = areNotificationsGranted(context),
            title = "Notifications",
            why = "Shows progress and a STOP action you can tap any time.",
        ),
        PermissionStatus(
            id = PermissionId.NOTIFICATION_LISTENER,
            granted = isNotificationListenerEnabled(context),
            title = "Notification access",
            why = "Reads notifications such as one-time codes when a task needs them.",
        ),
        PermissionStatus(
            id = PermissionId.BATTERY_OPTIMIZATION,
            granted = isIgnoringBatteryOptimizations(context),
            title = "Battery optimisation off",
            why = "Stops Android from freezing a long task mid-way.",
        ),
        PermissionStatus(
            id = PermissionId.INSTALL_PACKAGES,
            granted = canInstallPackages(context),
            title = "Install updates",
            why = "Needed only for the in-app updater. You still tap Install yourself.",
        ),
    )

    /** All permissions needed for normal agent operation (install permission excluded). */
    fun corePermissions(): List<PermissionId> = listOf(
        PermissionId.ACCESSIBILITY,
        PermissionId.OVERLAY,
        PermissionId.NOTIFICATIONS,
    )

    fun allCoreGranted(context: Context): Boolean {
        val snapshot = snapshot(context).associateBy { it.id }
        return corePermissions().all { snapshot[it]?.granted == true }
    }

    /**
     * The settings screen that fixes [id]. Returns a plain Intent so it is unit testable and
     * so callers can add flags before starting it.
     */
    fun settingsIntent(context: Context, id: PermissionId): Intent = when (id) {
        PermissionId.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        PermissionId.OVERLAY ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri(),
            )

        PermissionId.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        PermissionId.NOTIFICATION_LISTENER ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        PermissionId.BATTERY_OPTIMIZATION ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        PermissionId.INSTALL_PACKAGES ->
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Android 13+ hides "Allow restricted settings" for sideloaded apps. Without it the
     * accessibility toggle stays greyed out. The fix is a hidden app-info page; we surface
     * the plain app details screen and the exact steps in the UI instead of guessing.
     */
    fun restrictedSettingsAppInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun needsRestrictedSettingsHelp(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isAccessibilityEnabled(context)
}
