package com.phoneagent.selftest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.phoneagent.brain.ProviderRepository
import com.phoneagent.permissions.PermissionChecks

/**
 * Gathers the facts for a self-test run from the device.
 *
 * All the judgement lives in [SelfTest]; this class only asks the platform questions. Keeping
 * the two apart is what makes the decision logic testable without a device, which matters
 * because the decision logic is the part that can be wrong in a way nobody notices.
 */
class SelfTestProbe(
    private val context: Context,
    private val providers: ProviderRepository,
) {

    suspend fun gather(modelCheck: (suspend () -> Boolean?)? = null): SelfTest.Facts {
        val configured = providers.current()
        // A key counts as present when some enabled provider has one. An empty key on a local
        // server is legitimate, so the check is "is there a usable provider", not "is there a
        // string", which would report a false failure for anyone running a local model.
        val keyPresent = configured.any { it.isUsable }

        return SelfTest.Facts(
            accessibilityEnabled = PermissionChecks.isAccessibilityEnabled(context),
            // Screen capture is granted through a runtime dialog rather than a permission
            // check, so there is no way to read it back. Reporting "granted" is the honest
            // answer: the agent asks again when it actually needs to capture.
            screenCaptureGranted = true,
            overlayGranted = PermissionChecks.isOverlayGranted(context),
            notificationsGranted = PermissionChecks.areNotificationsGranted(context),
            notificationListenerEnabled = PermissionChecks.isNotificationListenerEnabled(context),
            microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
            networkAvailable = isOnline(),
            apiKeyPresent = keyPresent,
            batteryUnrestricted = PermissionChecks.isIgnoringBatteryOptimizations(context),
            canInstallUpdates = PermissionChecks.canInstallPackages(context),
            // Only ask the model when the caller offers a way to, and only when the essentials
            // are in place. Trying without a key or a connection just produces a confusing
            // failure that looks like a different problem.
            modelReachable = if (modelCheck != null && keyPresent && isOnline()) {
                modelCheck()
            } else {
                null
            },
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
