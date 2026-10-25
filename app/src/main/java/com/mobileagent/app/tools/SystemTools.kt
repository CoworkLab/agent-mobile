package com.mobileagent.app.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App & system control: launch apps, open URLs, deep-link into Settings pages,
 * read device state (battery, network, location), torch, DND, wake screen, APK management.
 */
object SystemTools {

    /**
     * Stable Settings intent contract ("android.settings.NOTIFICATION_SETTINGS", public since API 9).
     * The platform-34 compile stub omits this one constant, so it is mirrored here; the value is
     * fixed by Android's public intent contract and cannot drift across devices.
     */
    private const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"

    val NAMES: Set<String> = setOf(
        "list_installed_apps", "open_url", "send_intent", "open_settings", "set_dnd",
        "wake_screen", "install_apk", "uninstall_app", "get_battery", "get_network_status",
        "get_device_info", "get_location", "toggle_flashlight"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "list_installed_apps", "List installed apps (label + package).",
            ToolRegistry.p("query", "STRING", "filter by name or package")
        ),
        ToolRegistry.t("open_url", "Open a URL or deep link in the best matching app or browser.",
            ToolRegistry.p("url", "STRING", "http(s) URL or app deep link", req = true)),
        ToolRegistry.t(
            "send_intent", "Fire a custom Android intent (advanced).",
            ToolRegistry.p("action", "STRING", "e.g. android.intent.action.VIEW", req = true),
            ToolRegistry.p("data", "STRING", "URI to attach"),
            ToolRegistry.p("package", "STRING", "target package"),
            ToolRegistry.p("type", "STRING", "MIME type"),
            ToolRegistry.p("extras", "OBJECT", "string key/value extras")
        ),
        ToolRegistry.t(
            "open_settings", "Open a system Settings page (then use UI tools to toggle things).",
            ToolRegistry.p("page", "STRING",
                "main | wifi | bluetooth | display | sound | battery | apps | app_details | accessibility | notifications | location | storage | security | date_time | language | developer | all_apps | dnd_access",
                req = true),
            ToolRegistry.p("package", "STRING", "target package for app_details")
        ),
        ToolRegistry.t(
            "set_dnd", "Set Do Not Disturb mode. Needs notification-policy access; the tool reports how to grant it.",
            ToolRegistry.p("mode", "STRING", "off | priority | alarms | total_silence", req = true,
                enum = listOf("off", "priority", "alarms", "total_silence"))
        ),
        ToolRegistry.t("wake_screen", "Wake the screen and keep the CPU awake for a while.",
            ToolRegistry.p("duration_s", "INTEGER", "default 30")),
        ToolRegistry.t("install_apk", "Start installing an APK file (system dialog appears; handle it with UI tools).",
            ToolRegistry.p("path", "STRING", "absolute APK path or file:// URI", req = true)),
        ToolRegistry.t("uninstall_app", "Open the system uninstall dialog for a package.",
            ToolRegistry.p("package", "STRING", req = true)),
        ToolRegistry.t("get_battery", "Battery level and charging state.", listOf()),
        ToolRegistry.t("get_network_status", "Wi-Fi / mobile connectivity and type.", listOf()),
        ToolRegistry.t("get_device_info", "Model, Android version, screen size, locale, time zone, current time.", listOf()),
        ToolRegistry.t("get_location", "Get current device location (needs location permission + a location fix).",
            ToolRegistry.p("precise", "BOOLEAN", "high accuracy, default false")),
        ToolRegistry.t("toggle_flashlight", "Turn the flashlight on or off.",
            ToolRegistry.p("on", "BOOLEAN", req = true))
    )

    // ---- helpers -----------------------------------------------------------

    /** Start an activity and translate common launch failures into honest tool errors. */
    private fun launchOrExplain(context: Context, intent: Intent, toolName: String) {
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolException(
                "No installed app can handle this request ($toolName: ${intent.action ?: ""} ${intent.data ?: ""}). " +
                    "Use find_intent_handlers to check what is available, or open the target app first."
            )
        } catch (e: SecurityException) {
            throw ToolException("The system blocked $toolName: ${e.message}. Check the requested permission and try ask_user if the user must grant it.")
        }
    }

    /**
     * Modern APK install: stream the file into a PackageInstaller session. This works on
     * scoped-storage Android (a plain file:// Intent does not) and still shows the system
     * confirmation dialog the user (or the agent via UI tools) must accept.
     */
    private fun installApkSession(context: Context, apk: File): Pair<Boolean, String> {
        val packageInstaller = context.packageManager.packageInstaller
        return try {
            val sessionId = packageInstaller.createSession(
                android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            )
            val session = packageInstaller.openSession(sessionId)
            try {
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
                    session.fsync(out)
                }
                val pi = android.app.PendingIntent.getActivity(
                    context,
                    sessionId,
                    Intent(Intent.ACTION_INSTALL_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pi.intentSender)
                true to "session committed"
            } finally {
                session.close()
            }
        } catch (e: SecurityException) {
            false to "install permission was revoked (${e.message}); re-grant 'Install unknown apps'."
        } catch (e: Exception) {
            false to (e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val context = ctx.context
        return when (name) {
            "list_installed_apps" -> {
                val pm = context.packageManager
                val query = args.optString("query", "").lowercase()
                val apps = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { it.loadLabel(pm).toString() to it.packageName }
                    .filter { (label, pkg) -> query.isBlank() || label.lowercase().contains(query) || pkg.lowercase().contains(query) }
                    .sortedBy { it.first }
                    .take(60)
                val arr = JSONArray()
                apps.forEach { (label, pkg) -> arr.put(JSONObject().put("label", label).put("package", pkg)) }
                ctx.ok("count" to arr.length(), "apps" to arr)
            }
            "open_url" -> {
                val url = args.getString("url").trim()
                if (url.isBlank()) throw ToolException("open_url needs a 'url'.")
                // Keep known non-http schemes intact; treat everything else scheme-less as a domain.
                val hasScheme = url.contains("://") ||
                    listOf("content:", "geo:", "market:", "mailto:", "tel:", "sms:", "smsto:", "upi:", "intent:", "spotify:", "tg:")
                        .any { url.lowercase().startsWith(it) }
                val fixed = if (hasScheme) url else "https://$url"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixed))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchOrExplain(context, intent, "open_url")
                delay(600)
                ctx.ok("url" to fixed, "note" to "Verify the destination with get_current_app before interacting.")
            }
            "send_intent" -> {
                val action = args.getString("action")
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val data = args.optString("data", "").takeIf { it.isNotBlank() }
                val type = args.optString("type", "").takeIf { it.isNotBlank() }
                // setData and setType CLEAR each other; only setDataAndType keeps both.
                when {
                    data != null && type != null -> intent.setDataAndType(Uri.parse(data), type)
                    data != null -> intent.data = Uri.parse(data)
                    type != null -> intent.type = type
                }
                args.optString("package", "").takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
                args.optJSONObject("extras")?.let { extras ->
                    extras.keys().forEach { key ->
                        val v = extras.get(key)
                        when (v) {
                            is Int -> intent.putExtra(key, v)
                            is Boolean -> intent.putExtra(key, v)
                            is Long -> intent.putExtra(key, v)
                            is Double -> intent.putExtra(key, v)
                            else -> intent.putExtra(key, v.toString())
                        }
                    }
                }
                launchOrExplain(context, intent, "send_intent")
                ctx.ok("sent" to true, "action" to action, "note" to "Verify the result with get_current_app or wait_for_element.")
            }
            "open_settings" -> {
                val page = args.getString("page")
                val pkg = args.optString("package", "")
                val action = when (page) {
                    "main" -> Settings.ACTION_SETTINGS
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "app_details" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "notifications" -> ACTION_NOTIFICATION_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "security" -> Settings.ACTION_SECURITY_SETTINGS
                    "date_time" -> Settings.ACTION_DATE_SETTINGS
                    "language" -> Settings.ACTION_LOCALE_SETTINGS
                    "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    "all_apps" -> Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
                    "dnd_access" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    else -> throw ToolException("Unknown settings page '$page'.")
                }
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (page == "app_details") {
                    if (pkg.isBlank()) throw ToolException("app_details needs a 'package' argument.")
                    intent.data = Uri.parse("package:$pkg")
                }
                val launched = runCatching { context.startActivity(intent) }
                    .onFailure { e ->
                        throw ToolException(
                            "Could not open Settings page '$page': ${e.message}. " +
                                "Some OEMs rename settings pages; try send_intent with the exact Settings action instead."
                        )
                    }.isSuccess
                ctx.ok("opened" to launched, "page" to page)
            }
            "set_dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    return ctx.fail(
                        "Do-Not-Disturb control needs notification-policy access.",
                        JSONObject().put(
                            "fix",
                            "Call open_settings with page='dnd_access', have the user enable 'Mobile Agent', then retry."
                        )
                    )
                }
                val mode = args.getString("mode")
                val filter = when (mode) {
                    "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    else -> throw ToolException("Unknown DND mode '$mode'.")
                }
                val applied = runCatching { nm.setInterruptionFilter(filter) }.isSuccess
                ctx.ok("set" to applied, "mode" to mode)
            }
            "wake_screen" -> {
                val duration = args.optInt("duration_s", 30).coerceIn(5, 600)
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "MobileAgent:WakeScreen"
                )
                wl.acquire(duration * 1000L)
                ctx.ok("awake" to true, "duration_s" to duration)
            }
            "install_apk" -> {
                val path = args.getString("path")
                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    return ctx.fail(
                        "Install-permission missing.",
                        JSONObject().put("fix", "Call open_settings page='app_details' package='${context.packageName}' and enable 'Install unknown apps', then retry.")
                    )
                }
                val apkFile = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
                if (!apkFile.exists() || !apkFile.isFile) throw ToolException("APK not found: ${apkFile.absolutePath}")
                if (apkFile.length() == 0L) throw ToolException("APK file is empty: ${apkFile.absolutePath}")
                val result = installApkSession(context, apkFile)
                if (result.first) {
                    ctx.ok(
                        "install_started" to true,
                        "note" to "The system install confirmation will appear. Handle it with UI tools: wait_for_element for the Install button, click it, then wait and confirm the completion dialog."
                    )
                } else {
                    ctx.fail("APK install could not start: ${result.second}")
                }
            }
            "uninstall_app" -> {
                val pkg = args.getString("package")
                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                ctx.ok("uninstall_dialog" to launched, "note" to "Confirm the dialog with UI tools.")
            }
            "get_battery" -> {
                val bm = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = bm?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val pct = if (level >= 0) (level * 100) / scale else -1
                ctx.ok(
                    "percent" to pct,
                    "charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0)
                )
            }
            "get_network_status" -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                var connected = false
                var typeName = "none"
                var metered: Boolean? = null
                if (Build.VERSION.SDK_INT >= 23) {
                    val network = cm.activeNetwork
                    val caps = network?.let { cm.getNetworkCapabilities(it) }
                    if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        connected = true
                        typeName = when {
                            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile_data"
                            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                            else -> "other"
                        }
                        metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val info = cm.activeNetworkInfo
                    connected = info?.isConnected == true
                    typeName = info?.typeName?.lowercase(Locale.US) ?: "none"
                }
                ctx.ok(
                    "connected" to connected,
                    "type" to typeName,
                    "metered" to metered
                )
            }
            "get_device_info" -> {
                val metrics = context.resources.displayMetrics
                val tz = java.util.TimeZone.getDefault().id
                ctx.ok(
                    "model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "android_version" to Build.VERSION.RELEASE,
                    "sdk_int" to Build.VERSION.SDK_INT,
                    "screen" to "${metrics.widthPixels}x${metrics.heightPixels}",
                    "locale" to Locale.getDefault().toString(),
                    "timezone" to tz,
                    "current_time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
            }
            "get_location" -> {
                if (!ctx.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) ctx.permissionError("get_location", Manifest.permission.ACCESS_FINE_LOCATION)
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = if (args.optBoolean("precise", false) && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
                    android.location.LocationManager.GPS_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
                val last = withTimeoutOrNull(8_000L) {
                    kotlin.runCatching {
                        var loc = lm.getLastKnownLocation(provider)
                        if (loc == null) {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            val listener = android.location.LocationListener { l -> loc = l; latch.countDown() }
                            lm.requestSingleUpdate(provider, listener, null)
                            latch.await(7, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        loc
                    }.getOrNull()
                }
                if (last != null) {
                    ctx.ok(
                        "lat" to last.latitude, "lon" to last.longitude,
                        "accuracy_m" to last.accuracy.toDouble(), "provider" to last.provider
                    )
                } else {
                    ctx.fail(
                        "No location fix available (location may be off indoors).",
                        JSONObject().put("fix", "Ask the user to enable location, or rely on the Home weather card city via memory.")
                    )
                }
            }
            "toggle_flashlight" -> {
                val on = args.optBoolean("on", true)
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val torchId = cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: return ctx.fail("No flashlight-capable camera found.")
                runCatching { cm.setTorchMode(torchId, on) }
                    .onFailure { e ->
                        throw ToolException(
                            if (e is android.hardware.camera2.CameraAccessException) "Flashlight unavailable: the camera is in use by another app. Close it first."
                            else "Flashlight failed: ${e.message}"
                        )
                    }
                ctx.ok("flashlight" to if (on) "on" else "off")
            }
            else -> ctx.fail("Unknown system tool '$name'.")
        }
    }
}
