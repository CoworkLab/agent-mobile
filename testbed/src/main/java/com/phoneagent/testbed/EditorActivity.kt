package com.phoneagent.testbed

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View

/**
 * A video editor whose timeline is drawn by hand.
 *
 * The whole point is that the trim handles are not `View`s. A tree-only agent sees one
 * undifferentiated canvas and cannot tap a handle, which is the real difficulty this stands in
 * for: a screen where the accessible structure simply does not describe what is on it. The
 * scenario asserts that the agent either fails cleanly or falls back to coordinates, and never
 * claims success without the values actually changing.
 *
 * The final trim values are published through [EditorResultProvider] so a test can assert on
 * them directly rather than reading them off the screen.
 */
class EditorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val timeline = TimelineView(this)
        timeline.onTrimChanged = { start, end -> EditorResult.set(start, end) }

        val column = column()
        column.addView(label("Trim the clip"))
        column.addView(
            timeline,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                320,
            ),
        )
        column.addView(label("Drag the handles to set the start and end."))

        show(column)
    }
}

/**
 * The hand-drawn timeline.
 *
 * Two draggable handles on a bar. Nothing here is a `View`, so nothing here is in the
 * accessibility tree beyond this single node.
 */
class TimelineView(context: android.content.Context) : View(context) {

    var onTrimChanged: ((Int, Int) -> Unit)? = null

    private val barPaint = Paint().apply { color = Color.parseColor("#BDBDBD") }
    private val selectionPaint = Paint().apply { color = Color.parseColor("#42A5F5") }
    private val handlePaint = Paint().apply { color = Color.parseColor("#1565C0") }

    private var startFraction = 0.2f
    private var endFraction = 0.8f

    /** Which handle the current gesture is moving, or null. */
    private var dragging: Handle? = null

    private enum class Handle { START, END }

    private val handleRadius get() = height * 0.5f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val midY = height / 2f
        val top = midY - height * 0.15f
        val bottom = midY + height * 0.15f

        canvas.drawRect(0f, top, width.toFloat(), bottom, barPaint)
        canvas.drawRect(
            startFraction * width,
            top,
            endFraction * width,
            bottom,
            selectionPaint,
        )
        canvas.drawCircle(startFraction * width, midY, handleRadius, handlePaint)
        canvas.drawCircle(endFraction * width, midY, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    near(event.x, startFraction * width) -> Handle.START
                    near(event.x, endFraction * width) -> Handle.END
                    else -> null
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val fraction = (event.x / width).coerceIn(0f, 1f)
                when (dragging) {
                    Handle.START -> startFraction = fraction.coerceAtMost(endFraction - 0.05f)
                    Handle.END -> endFraction = fraction.coerceAtLeast(startFraction + 0.05f)
                    null -> return false
                }
                onTrimChanged?.invoke(frames(startFraction), frames(endFraction))
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }

    private fun near(x: Float, target: Float) = kotlin.math.abs(x - target) <= handleRadius * 1.5f

    private fun frames(fraction: Float) = (fraction * TOTAL_FRAMES).toInt()

    companion object {
        const val TOTAL_FRAMES = 1000
    }
}

/** The editor's published result, read by tests through [EditorResultProvider]. */
object EditorResult {
    @Volatile
    var startFrame: Int = -1
        private set

    @Volatile
    var endFrame: Int = -1
        private set

    fun set(start: Int, end: Int) {
        startFrame = start
        endFrame = end
    }

    fun reset() {
        startFrame = -1
        endFrame = -1
    }
}

/**
 * Publishes the editor's trim values.
 *
 * A test could read the numbers off the screen, but then it would be testing the screen rather
 * than the drag. Reading them from here answers the question that matters: did the gesture
 * actually change the clip?
 */
class EditorResultProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf("start", "end")).apply {
        addRow(arrayOf(EditorResult.startFrame, EditorResult.endFrame))
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.testbed.editor.result"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
