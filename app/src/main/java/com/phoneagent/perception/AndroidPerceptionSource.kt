package com.phoneagent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import com.phoneagent.PhoneAgentApp
import com.phoneagent.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The real perception source, reading the live screen through [AccessibilityService].
 *
 * Two platform rules shape this class:
 *  1. `takeScreenshot` is rate limited. Android 14 enforces a minimum interval and returns
 *     `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` if asked too soon, so the last capture time
 *     is tracked and callers are told how long to wait.
 *  2. Screenshot capture is asynchronous and must not block the service's main thread.
 */
class AndroidPerceptionSource(
    private val serviceProvider: () -> AccessibilityService? = {
        com.phoneagent.service.PhoneAgentAccessibilityService.get()
    },
) : PerceptionSource {

    @Volatile
    private var lastScreenshotAtMs = 0L

    override fun isAvailable(): Boolean =
        com.phoneagent.service.PhoneAgentAccessibilityService.isRunning()

    override fun screenshotCooldownMs(): Long {
        val elapsed = System.currentTimeMillis() - lastScreenshotAtMs
        return (MIN_INTERVAL_MS - elapsed).coerceAtLeast(0)
    }

    override suspend fun capture(mode: PerceptionMode): ScreenSnapshot {
        val service = serviceProvider()
            ?: return failureSnapshot(mode, SnapshotFailure.NO_WINDOW)

        val screenBounds = NodeFlattener.screenBounds(service)

        // Tree first: it is cheap, and in TREE_ONLY mode it is all we need.
        val rawTree = runCatching { NodeFlattener.flatten(service, screenBounds) }
            .getOrElse {
                log(LogLevel.WARN, "Tree read failed: ${it.message}")
                emptyList()
            }
        val shaped = TreeShaper.shape(rawTree, screenBounds)
        val effectiveMode = resolveMode(mode, shaped, screenBounds)
        val marked = TreeShaper.assignMarks(shaped)

        val packageName = runCatching {
            service.windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
        }.getOrNull()
        val windowTitle = runCatching {
            service.windows.firstOrNull { it.isActive }?.title?.toString()
        }.getOrNull()

        var jpeg: ByteArray? = null
        var width = 0
        var height = 0
        var failure: SnapshotFailure? = null

        if (effectiveMode != PerceptionMode.TREE_ONLY) {
            val capture = captureScreenshot(service)
            when (capture) {
                is ScreenshotResult.Ok -> {
                    val bitmap = capture.bitmap

                    // Blankness is judged on the raw capture, before the marks are drawn on it.
                    // A secure window is redacted by the platform rather than refused: the
                    // capture comes back as a flat black frame with no error code, and this check
                    // is what turns that into a named failure. Testing the marked bitmap instead
                    // would paint red boxes and labels over the black and make it look like a
                    // perfectly good screenshot — the agent would then be told it could see a
                    // screen it cannot, and would tap at coordinates that mean nothing.
                    if (MarkRenderer.looksBlank(bitmap)) {
                        failure = SnapshotFailure.BLANK_CAPTURE
                        bitmap.recycle()
                    } else {
                        val withMarks = if (effectiveMode == PerceptionMode.TREE_PLUS_SCREENSHOT) {
                            val scaleX = bitmap.width.toFloat() / screenBounds.width.coerceAtLeast(1)
                            val scaleY = bitmap.height.toFloat() / screenBounds.height.coerceAtLeast(1)
                            MarkRenderer.drawMarks(bitmap, marked, scaleX, scaleY)
                        } else {
                            bitmap
                        }
                        val (bytes, size) = MarkRenderer.downscaleAndEncode(withMarks)
                        jpeg = bytes
                        width = size.first
                        height = size.second
                        lastScreenshotAtMs = System.currentTimeMillis()
                        withMarks.recycle()
                    }
                }

                is ScreenshotResult.Secure -> failure = SnapshotFailure.SECURE_SCREEN
                is ScreenshotResult.RateLimited -> failure = SnapshotFailure.RATE_LIMITED
                is ScreenshotResult.Error -> failure = SnapshotFailure.UNKNOWN
            }
        }

        // A tree we cannot read and a screenshot we cannot take is the secure-screen case,
        // and the user needs to be told rather than left watching a spinner.
        if (failure == null && marked.isEmpty() && jpeg == null) {
            failure = SnapshotFailure.NO_WINDOW
        }

        return ScreenSnapshot(
            packageName = packageName,
            windowTitle = windowTitle,
            timestampMs = System.currentTimeMillis(),
            nodes = marked,
            screenHash = SnapshotSerializer.screenHash(marked, packageName, windowTitle),
            screenshotJpeg = jpeg,
            screenshotWidth = width,
            screenshotHeight = height,
            mode = effectiveMode,
            failure = failure,
        )
    }

    /**
     * Escalates to a screenshot when the tree cannot support action, and falls back to
     * tree-only when the caller asked for a screenshot on a tree-rich screen to save quota.
     */
    private fun resolveMode(
        requested: PerceptionMode,
        nodes: List<UiNode>,
        screenBounds: Bounds,
    ): PerceptionMode = when {
        nodes.isEmpty() -> PerceptionMode.SCREENSHOT_ONLY
        TreeShaper.needsScreenshot(nodes, screenBounds) -> PerceptionMode.TREE_PLUS_SCREENSHOT
        else -> requested
    }

    private sealed interface ScreenshotResult {
        class Ok(val bitmap: Bitmap) : ScreenshotResult
        data object Secure : ScreenshotResult
        data object RateLimited : ScreenshotResult
        data object Error : ScreenshotResult
    }

    /**
     * Captures one frame. The platform hands back either a result or an error code, and the
     * error code is the only way to tell "this screen is protected" from "you asked too soon".
     */
    private suspend fun captureScreenshot(service: AccessibilityService): ScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ScreenshotResult.Error

        val cooldown = screenshotCooldownMs()
        if (cooldown > 0) {
            log(LogLevel.DEBUG, "Screenshot cooldown ${cooldown}ms")
            return ScreenshotResult.RateLimited
        }

        var errorCode = ERROR_NONE
        val bitmap = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        // Hardware bitmaps cannot be read pixel by pixel, which both the
                        // blank check and the mark drawing need, so copy to software memory.
                        val copied = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { buffer.close() }
                        if (continuation.isActive) continuation.resume(copied)
                    }

                    override fun onFailure(failureErrorCode: Int) {
                        errorCode = failureErrorCode
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                runCatching {
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        { runnable -> runnable.run() },
                        callback,
                    )
                }.onFailure {
                    log(LogLevel.WARN, "takeScreenshot call failed: ${it.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

        return when {
            bitmap != null -> ScreenshotResult.Ok(bitmap)
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                ScreenshotResult.Secure
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                ScreenshotResult.RateLimited
            else -> ScreenshotResult.Error
        }
    }

    private fun failureSnapshot(mode: PerceptionMode, failure: SnapshotFailure) = ScreenSnapshot(
        packageName = null,
        windowTitle = null,
        timestampMs = System.currentTimeMillis(),
        nodes = emptyList(),
        screenHash = "",
        mode = mode,
        failure = failure,
    )

    private fun log(level: LogLevel, message: String) =
        PhoneAgentApp.safeLog(level, TAG, message)

    private companion object {
        const val TAG = "Perception"

        /** Android 14 enforces roughly this interval between screenshots. */
        const val MIN_INTERVAL_MS = 1000L
        const val ERROR_NONE = 0
    }
}
