package com.phoneagent.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.phoneagent.safety.IntentRequest
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Starts intents from the app.
 *
 * The background-activity-launch rule from Android 10 shapes this: an app in the background
 * cannot normally start an activity. Two things make it work here, and both are needed:
 *  - the intent is started from an accessibility-service context, which is exempt, and
 *  - `FLAG_ACTIVITY_NEW_TASK` is always set, because the target activity is not in this task.
 *
 * `resolveActivity` is also subject to package-visibility filtering on Android 11+. The
 * manifest declares a `<queries>` block for the schemes the policy allows, so resolution
 * returns a real answer instead of a silent null.
 */
class AndroidIntentLauncher(
    private val context: Context,
    private val logger: Logger,
) : IntentLauncher {

    override suspend fun resolve(request: IntentRequest): IntentResolution {
        val intent = buildIntent(request) ?: return IntentResolution.NoHandler(
            request.action,
            request.uri,
        )

        val matches = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrElse { emptyList() }

        // Drop our own package: the agent must not try to open itself.
        val candidates = matches
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()

        return when {
            candidates.isEmpty() -> IntentResolution.NoHandler(request.action, request.uri)
            candidates.size == 1 -> IntentResolution.Handled(candidates.first())
            else -> IntentResolution.Ambiguous(candidates)
        }
    }

    override suspend fun start(request: IntentRequest): LaunchResult {
        val intent = buildIntent(request)
            ?: return LaunchResult.Failed("That intent could not be built.")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            val pkg = intent.`package` ?: intent.component?.packageName
                ?: runCatching {
                    context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
                }.getOrNull()
                ?: "an app"
            LaunchResult.Started(pkg)
        } catch (denied: SecurityException) {
            // Android 10+ background-activity-launch denial surfaces as a SecurityException
            // on some OEM builds. The accessibility-service context normally avoids it, but
            // when it does happen the honest answer is "use the UI route".
            logger.log(LogLevel.WARN, TAG, "Background launch denied: ${denied.message}")
            LaunchResult.Blocked(
                "Android refused to open that screen from the background. " +
                    "Open the app and do it through its own screens instead.",
            )
        } catch (notFound: android.content.ActivityNotFoundException) {
            LaunchResult.Failed("Nothing on this phone can open that.")
        } catch (error: Throwable) {
            logger.log(LogLevel.ERROR, TAG, "startActivity failed: ${error.message}")
            LaunchResult.Failed(error.message ?: error::class.simpleName ?: "Unknown error")
        }
    }

    private fun buildIntent(request: IntentRequest): Intent? = runCatching {
        Intent(request.action).apply {
            request.uri?.let { setData(Uri.parse(it)) }
            request.mimeType?.let { type = it }
            request.categories.forEach { addCategory(it) }
            request.packageName?.let { setPackage(it) }
            request.extras.forEach { (key, value) -> putExtra(key, value) }

            // A URI without a MIME type must not be cleared, or the chooser widens.
            if (request.mimeType != null && request.uri != null) {
                setDataAndType(Uri.parse(request.uri), request.mimeType)
            }

            // Explicitly target the package when one was named, so the user is not asked.
            request.packageName?.let { pkg ->
                runCatching {
                    val resolveInfo = context.packageManager
                        .queryIntentActivities(this, 0)
                        .firstOrNull { it.activityInfo?.packageName == pkg }
                    resolveInfo?.let {
                        component = ComponentName(
                            it.activityInfo.packageName,
                            it.activityInfo.name,
                        )
                    }
                }
            }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "IntentLauncher"
    }
}

/**
 * Device facts for the read-only tools.
 *
 * `launchableApps` filters to activities that actually appear in the launcher, because
 * opening a package that has no launcher entry fails in a way the user cannot understand.
 */
class AndroidDeviceInfo(
    private val context: Context,
    private val notificationReader: NotificationReader? = null,
) : DeviceInfo {

    override suspend fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrElse { emptyList() }

        return resolved
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val label = runCatching {
                    info.loadLabel(context.packageManager).toString()
                }.getOrNull() ?: activity.packageName
                activity.packageName to label
            }
            .distinctBy { it.second.lowercase() }
    }

    override suspend fun recentNotifications(limit: Int): List<NotificationInfo> =
        notificationReader?.recent(limit) ?: emptyList()

    override suspend fun clipboardText(): String? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        clip.getItemAt(0).coerceToText(context).toString().takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Reads the notifications the listener has collected. Implemented by the service. */
interface NotificationReader {
    fun recent(limit: Int): List<NotificationInfo>
}

/**
 * Opens apps by label. The launcher query is the same one `list_apps` uses, so the name the
 * model sees in the list is the name it can open.
 */
class AndroidAppLauncher(
    private val context: Context,
    private val denylist: PackageDenylist,
    private val logger: Logger,
) : AppLauncher {

    override suspend fun launch(name: String): AppLauncher.Result {
        val apps = AndroidDeviceInfo(context).launchableApps()
        return when (val match = AppNameMatcher.match(name, apps)) {
            is AppLauncher.Result.Launched -> {
                if (denylist.isDenied(match.packageName)) {
                    logger.log(LogLevel.WARN, TAG, "Refused to open denied app ${match.packageName}")
                    AppLauncher.Result.Denied(match.packageName)
                } else {
                    startApp(match.packageName, match.label)
                }
            }
            else -> match
        }
    }

    private fun startApp(packageName: String, label: String): AppLauncher.Result {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLauncher.Result.Failed("$label has no launchable screen.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            AppLauncher.Result.Launched(packageName, label)
        }.getOrElse {
            logger.log(LogLevel.WARN, TAG, "Could not open $packageName: ${it.message}")
            AppLauncher.Result.Failed(it.message ?: "Could not open $label.")
        }
    }

    private companion object {
        const val TAG = "AppLauncher"
    }
}
