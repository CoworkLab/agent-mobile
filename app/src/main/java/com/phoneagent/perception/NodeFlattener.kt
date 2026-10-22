package com.phoneagent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream

/**
 * Flattens the live accessibility tree into the pure [UiNode] values [TreeShaper] works on.
 *
 * Kept separate from the shaping rules so those rules stay unit testable without a device.
 */
object NodeFlattener {

    /**
     * Depth-first walk of the active windows.
     *
     * Two windows are handled: the active one (the app in front) and any window that floats
     * above it, such as a dialog or the notification shade. Nodes are recycled only when we
     * created them; `recycle()` on nodes still owned by the framework is a crash on newer
     * API levels, so children returned by `getChild` are used without recycling.
     */
    fun flatten(service: AccessibilityService, screenBounds: Bounds): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        var nextId = 1

        val roots = runCatching {
            service.windows
                .sortedByDescending { it.isActive }
                .mapNotNull { it.root }
        }.getOrDefault(emptyList())

        roots.forEach { root ->
            val counter = intArrayOf(nextId)
            collect(root, null, 0, screenBounds, nodes, counter)
            nextId = counter[0]
        }

        // Cross-window duplicates: the same node can be reachable from two roots.
        return nodes.distinctBy { node ->
            listOf(
                node.className, node.text, node.contentDescription, node.viewId,
                node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom,
            ).joinToString("|")
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        parentId: Int?,
        depth: Int,
        screenBounds: Bounds,
        out: MutableList<UiNode>,
        counter: IntArray,
    ) {
        if (depth > TreeShaper.MAX_DEPTH) return
        if (out.size >= TreeShaper.MAX_NODES * 2) return

        val id = counter[0]++
        val rect = Rect()
        val hasBounds = runCatching {
            node.getBoundsInScreen(rect)
            true
        }.getOrDefault(false)

        val bounds = if (hasBounds) {
            Bounds(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            Bounds(0, 0, 0, 0)
        }

        out += UiNode(
            nodeId = id,
            parentId = parentId,
            depth = depth,
            className = node.className?.toString(),
            text = node.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            viewId = node.viewIdResourceName,
            bounds = bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            selected = node.isSelected,
            enabled = node.isEnabled,
            password = node.isPassword,
        )

        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            collect(child, id, depth + 1, screenBounds, out, counter)
        }
    }

    /** Screen bounds from the active window, falling back to the display size. */
    fun screenBounds(service: AccessibilityService): Bounds {
        val rect = Rect()
        val fromWindow = runCatching {
            val window = service.windows.firstOrNull { it.isActive }
            window?.getBoundsInScreen(rect)
            rect.width() > 0 && rect.height() > 0
        }.getOrDefault(false)

        if (fromWindow) return Bounds(rect.left, rect.top, rect.right, rect.bottom)

        val metrics = service.resources.displayMetrics
        return Bounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}

/** Draws numbered boxes for the set-of-marks screenshot. */
object MarkRenderer {

    private const val MAX_LONG_SIDE = 1080
    private const val JPEG_QUALITY = 70

    /**
     * Downscales to [MAX_LONG_SIDE] and encodes JPEG. Long screenshots of a phone display are
     * far larger than the model needs, and every extra pixel costs upload time and quota.
     */
    fun downscaleAndEncode(source: Bitmap): Pair<ByteArray, Pair<Int, Int>> {
        val scaled = downscale(source)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val result = out.toByteArray() to (scaled.width to scaled.height)
        if (scaled !== source) scaled.recycle()
        return result
    }

    fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_LONG_SIDE) return source
        val ratio = MAX_LONG_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Draws each marked node's number on the image.
     *
     * [scaleX]/[scaleY] map accessibility-tree coordinates (screen space) onto the image,
     * because the bitmap may be a different size than the display.
     */
    fun drawMarks(
        bitmap: Bitmap,
        nodes: List<UiNode>,
        scaleX: Float,
        scaleY: Float,
    ): Bitmap {
        val mutable = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(220, 255, 64, 64)
        }
        val labelBackground = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 211, 47, 47)
        }
        val labelText = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            isAntiAlias = true
            isFakeBoldText = true
        }

        nodes.forEach { node ->
            val mark = node.mark ?: return@forEach
            val left = node.bounds.left * scaleX
            val top = node.bounds.top * scaleY
            val right = node.bounds.right * scaleX
            val bottom = node.bounds.bottom * scaleY

            canvas.drawRect(left, top, right, bottom, stroke)

            val label = mark.toString()
            val textWidth = labelText.measureText(label)
            val boxLeft = left
            val boxTop = top
            canvas.drawRect(boxLeft, boxTop, boxLeft + textWidth + 12f, boxTop + 32f, labelBackground)
            canvas.drawText(label, boxLeft + 6f, boxTop + 24f, labelText)
        }
        return mutable
    }

    /**
     * True when the capture looks like a secure or failed screen: uniformly black or
     * uniformly one colour. Sampling a sparse grid is enough and is much cheaper than
     * reading every pixel.
     */
    fun looksBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var samples = 0
        var first = 0
        var identical = true

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (samples == 0) first = pixel
                if (pixel != first) identical = false
                samples++
                x += stepX
            }
            y += stepY
        }
        return samples == 0 || identical
    }

    /** Only meaningful on API 30+, which is our minimum, so no version branch is needed. */
    fun supportsScreenshot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
