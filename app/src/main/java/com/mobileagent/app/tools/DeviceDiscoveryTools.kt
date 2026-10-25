package com.mobileagent.app.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device discovery: lets the agent learn what THIS phone can do instead of guessing.
 *
 * - get_capabilities     full device profile (call once per session, cache the summary)
 * - check_permission     is a runtime/special-access permission granted right now
 * - request_permission   open the correct system screen so the user can grant it
 * - find_intent_handlers which installed apps can handle an intent or URL
 * - get_app_info         deep details of one installed app (version, permissions, deep links)
 *
 * Permission and service states are ALWAYS probed live (never cached) because the user can
 * revoke them at any moment. Static facts (SDK, screen, sensors) are safe to cache.
 */
object DeviceDiscoveryTools {

    val NAMES: Set<String> = setOf(
        "get_capabilities", "check_permission", "request_permission",
        "find_intent_handlers", "get_app_info"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "get_capabilities",
            "Full device profile: Android version, manufacturer/ROM, screen, RAM, hardware features, " +
                "which agent features work here (screenshot API, gesture dispatch, notification access, overlay) " +
                "and live permission/service states. Call once at the start of a session (or when unsure what this " +
                "phone supports) and rely on the summary afterwards.",
            listOf()
        ),
        ToolRegistry.t(
            "check_permission",
            "Check whether a permission or special access is granted right now. Examples: READ_CONTACTS, SEND_SMS, " +
                "CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, READ_CALL_LOG, READ_SMS, READ_CALENDAR, " +
                "ACCESSIBILITY, NOTIFICATION_LISTENER, OVERLAY, USAGE_ACCESS, INSTALL_PACKAGES, DND_ACCESS, " +
                "MANAGE_EXTERNAL_STORAGE.",
            ToolRegistry.p("permission", "STRING", "permission or special-access name", req = true)
        ),
        ToolRegistry.t(
            "request_permission",
            "Open the correct system screen or dialog so the user can grant a missing permission or special access. " +
                "Use after check_permission reports it as missing. Tell the user what to toggle.",
            ToolRegistry.p("permission", "STRING", "permission or special-access name", req = true)
        ),
        ToolRegistry.t(
            "find_intent_handlers",
            "Ask Android which installed apps can handle an intent or URL. Use BEFORE send_intent/open_url to confirm " +
                "a recipe works on this phone and to pick the right app.",
            ToolRegistry.p("action", "STRING", "intent action, e.g. android.intent.action.SEND"),
            ToolRegistry.p("data", "STRING", "URI or URL, e.g. https://wa.me/919999999999 or upi://pay?..."),
            ToolRegistry.p("type", "STRING", "MIME type, e.g. text/plain")
        ),
        ToolRegistry.t(
            "get_app_info",
            "Details of one installed app: version, install/update time, granted permissions, launcher activity, " +
                "declared URL schemes and deep links, enabled state. Use its deep links to reach app features fast.",
            ToolRegistry.p("package", "STRING", "package name", req = true)
        )
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val context = ctx.context
        return when (name) {
            "get_capabilities" -> ctx.ok("device" to capabilitiesJson(context))
            "check_permission" -> {
                val perm = args.getString("permission").trim()
                val granted = isGranted(context, perm)
                val o = JSONObject()
                    .put("permission", perm)
                    .put("granted", granted.first)
                    .put("kind", granted.second)
                if (!granted.first) o.put("fix", "Call request_permission(permission='$perm') so the user can grant it.")
                ctx.ok("check" to o)
            }
            "request_permission" -> {
                val perm = args.getString("permission").trim()
                val intent = requestIntent(context, perm)
                    ?: throw ToolException(
                        "Unknown permission '$perm'. Use a runtime permission (e.g. CAMERA, READ_CONTACTS, SEND_SMS, " +
                            "ACCESS_FINE_LOCATION) or a special access name (ACCESSIBILITY, NOTIFICATION_LISTENER, " +
                            "OVERLAY, USAGE_ACCESS, INSTALL_PACKAGES, DND_ACCESS, MANAGE_EXTERNAL_STORAGE, BATTERY_OPTIMIZATION)."
                    )
                val granted = isGranted(context, perm)
                if (granted.first) {
                    return ctx.ok("already_granted" to true, "permission" to perm)
                }
                val opened = try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: android.content.ActivityNotFoundException) {
                    throw ToolException("This ROM has no screen for '$perm' (${e.message}). Grant it manually in Settings.")
                } catch (e: SecurityException) {
                    throw ToolException("The system blocked opening '$perm' settings: ${e.message}")
                }
                ctx.ok(
                    "settings_opened" to opened,
                    "permission" to perm,
                    "note" to "The user must toggle it. Wait, then re-check with check_permission before retrying the task."
                )
            }
            "find_intent_handlers" -> {
                val action = args.optString("action", "").takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW
                val intent = Intent(action)
                args.optString("data", "").takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
                args.optString("type", "").takeIf { it.isNotBlank() }?.let { intent.type = it }
                val pm = context.packageManager
                val resolved = runCatching {
                    if (intent.data != null && intent.type != null) {
                        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    } else {
                        pm.queryIntentActivities(intent, 0)
                    }
                }.getOrElse { emptyList() }
                val arr = JSONArray()
                resolved.take(20).forEach { info ->
                    arr.put(JSONObject()
                        .put("package", info.activityInfo.packageName)
                        .put("label", try { info.loadLabel(pm).toString() } catch (_: Exception) { info.activityInfo.packageName })
                        .put("activity", info.activityInfo.name))
                }
                ctx.ok(
                    "count" to arr.length(),
                    "handlers" to arr,
                    "note" to (if (arr.length() == 0) "No app on this phone can handle that intent." else "")
                )
            }
            "get_app_info" -> {
                val pkg = args.getString("package").trim()
                val pm = context.packageManager
                val info: PackageInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                    }
                }.getOrElse { throw ToolException("Package '$pkg' is not installed. Use list_installed_apps to see what is available.") }
                val appInfo = info.applicationInfo ?: throw ToolException("Package '$pkg' has no application info.")
                val requested = info.requestedPermissions?.toList() ?: emptyList()
                val grantedPerms = requested.filter { ctx.hasPermission(it) }
                val launchActivity = pm.getLaunchIntentForPackage(pkg)?.component?.className
                // Public deep-link discovery: list the package's own browsable VIEW handlers.
                // (Arbitrary custom schemes cannot be enumerated via public API; these are the
                // https/app-link handlers the app declares, which cover most real deep links.)
                val deepLinks = JSONArray()
                runCatching {
                    pm.queryIntentActivities(
                        Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE)
                            .setData(Uri.parse("https://deep-link-probe")).setPackage(pkg),
                        PackageManager.MATCH_ALL
                    ).take(15).forEach { act ->
                        deepLinks.put(JSONObject()
                            .put("activity", act.activityInfo.name)
                            .put("label", try { act.loadLabel(pm).toString() } catch (_: Exception) { "" }))
                    }
                }
                val o = JSONObject()
                    .put("package", pkg)
                    .put("label", pm.getApplicationLabel(appInfo).toString())
                    .put("version", info.versionName ?: "?")
                    .put("version_code", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
                    .put("installed_at", info.firstInstallTime)
                    .put("updated_at", info.lastUpdateTime)
                    .put("enabled", appInfo.enabled)
                    .put("launcher_activity", launchActivity)
                    .put("granted_permissions", JSONArray(grantedPerms))
                    .put("requested_permissions", JSONArray(requested))
                    .put("deep_link_activities", deepLinks)
                ctx.ok("app" to o)
            }
            else -> ctx.fail("Unknown device-discovery tool '$name'.")
        }
    }

    // ---- capability probe ---------------------------------------------------

    fun capabilitiesJson(context: Context): JSONObject {
        val pm = context.packageManager
        val metrics = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

        // Live service states - never cached.
        val accessibilityOn = com.mobileagent.app.automation.accessibility.AgentAccessibilityService.instance != null
        val notificationsOn = com.mobileagent.app.notification.AgentNotificationListener.isBound
        val overlayOn = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val dndAccess = nm.isNotificationPolicyAccessGranted
        val installAllowed = Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()
        val storageManager = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

        val features = JSONObject()
            .put("camera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA))
            .put("camera_flash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            .put("nfc", pm.hasSystemFeature(PackageManager.FEATURE_NFC))
            .put("fingerprint", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
            .put("telephony", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            .put("wifi", pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
            .put("bluetooth", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            .put("location_gps", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))

        val agentFeatures = JSONObject()
            .put("ui_tree_and_gestures", accessibilityOn)
            .put("screenshot_api", Build.VERSION.SDK_INT >= 30)
            .put("screen_capture", com.mobileagent.app.vision.ScreenCaptureManager.getInstance().hasRecentFrame())
            .put("notification_listener", notificationsOn)
            .put("overlay", overlayOn)
            .put("dnd_control", dndAccess)
            .put("apk_install", installAllowed)
            .put("full_storage", storageManager)

        val granted: (String) -> Boolean = { perm ->
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
        val permissions = JSONObject()
            .put("contacts", granted(Manifest.permission.READ_CONTACTS))
            .put("sms", granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_SMS))
            .put("call_phone", granted(Manifest.permission.CALL_PHONE))
            .put("call_log", granted(Manifest.permission.READ_CALL_LOG))
            .put("calendar", granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR))
            .put("camera", granted(Manifest.permission.CAMERA))
            .put("microphone", granted(Manifest.permission.RECORD_AUDIO))
            .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION))

        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("screen", "${metrics.widthPixels}x${metrics.heightPixels} @${metrics.densityDpi}dpi")
            .put("ram_total_mb", memInfo.totalMem / (1024 * 1024))
            .put("ram_available_mb", memInfo.availMem / (1024 * 1024))
            .put("hardware_features", features)
            .put("agent_features", agentFeatures)
            .put("permissions", permissions)
    }

    // ---- permission helpers --------------------------------------------------

    /** Special-access names are Android conventions; runtime permissions go through checkSelfPermission. */
    fun isGranted(context: Context, permRaw: String): Pair<Boolean, String> {
        val perm = permRaw.trim().uppercase().replace(' ', '_')
        return when (perm) {
            "ACCESSIBILITY" -> (com.mobileagent.app.automation.accessibility.AgentAccessibilityService.instance != null) to "service"
            "NOTIFICATION_LISTENER" -> com.mobileagent.app.notification.AgentNotificationListener.isBound to "service"
            "OVERLAY", "SYSTEM_ALERT_WINDOW" -> (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)) to "special"
            "USAGE_ACCESS" -> {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                ) else @Suppress("DEPRECATION") appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                )
                (mode == android.app.AppOpsManager.MODE_ALLOWED) to "special"
            }
            "DND_ACCESS", "NOTIFICATION_POLICY" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.isNotificationPolicyAccessGranted to "special"
            }
            "INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES" ->
                (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) to "special"
            "MANAGE_EXTERNAL_STORAGE", "ALL_FILES_ACCESS" ->
                (Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) to "special"
            "BATTERY_OPTIMIZATION", "IGNORE_BATTERY_OPTIMIZATIONS" -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName) to "special"
            }
            "WRITE_SETTINGS" -> (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)) to "special"
            else -> {
                // Try a literal runtime permission first; fall back to treating it as a class suffix
                // (e.g. "CONTACTS" -> android.permission.READ_CONTACTS-style guesses are NOT attempted:
                // unknown names must fail honestly).
                if (perm.startsWith("ANDROID.PERMISSION.") || perm.contains('.')) {
                    (context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) to "runtime"
                } else {
                    false to "unknown"
                }
            }
        }
    }

    /** The exact system screen or dialog for a missing permission/special access, or null when unknown. */
    fun requestIntent(context: Context, permRaw: String): Intent? {
        val perm = permRaw.trim().uppercase().replace(' ', '_')
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (perm) {
            "ACCESSIBILITY" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "NOTIFICATION_LISTENER" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "OVERLAY", "SYSTEM_ALERT_WINDOW" ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            "USAGE_ACCESS" ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "DND_ACCESS", "NOTIFICATION_POLICY" ->
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES" ->
                if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri) else null
            "MANAGE_EXTERNAL_STORAGE", "ALL_FILES_ACCESS" ->
                if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            "BATTERY_OPTIMIZATION", "IGNORE_BATTERY_OPTIMIZATIONS" ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "WRITE_SETTINGS" ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            else -> {
                // Runtime permission: jump straight to the app details page where the toggle lives.
                if (perm.startsWith("ANDROID.PERMISSION.") || perm.contains('.')) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                } else null
            }
        }
    }
}
