package com.mobileagent.app.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import com.mobileagent.app.automation.accessibility.AgentAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * All touch/gesture tools, implemented with AccessibilityService.dispatchGesture (API 24+).
 * These give the agent the same physical reach a human finger has: long press, drag, pinch,
 * multi-stroke pattern drawing, and everything the accessibility tree alone cannot do.
 */
object GestureTools {

    val NAMES: Set<String> = setOf(
        "tap", "double_tap", "long_press", "swipe", "fling",
        "scroll", "scroll_until_found", "drag_and_drop", "pinch", "custom_gesture"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t("tap", "Tap at screen coordinates.", ToolRegistry.i("x"), ToolRegistry.i("y")),
        ToolRegistry.t("double_tap", "Double tap at coordinates.", ToolRegistry.i("x"), ToolRegistry.i("y")),
        ToolRegistry.t(
            "long_press", "Long press at coordinates (opens context menus, selection modes, app shortcuts).",
            ToolRegistry.i("x"), ToolRegistry.i("y"),
            ToolRegistry.p("duration_ms", "INTEGER", "Press duration, default 800")
        ),
        ToolRegistry.t(
            "swipe", "Swipe between two points.",
            ToolRegistry.i("x1"), ToolRegistry.i("y1"), ToolRegistry.i("x2"), ToolRegistry.i("y2"),
            ToolRegistry.p("duration_ms", "INTEGER", "Gesture duration, default 300")
        ),
        ToolRegistry.t(
            "fling", "Fast swipe in a direction from screen centre.",
            ToolRegistry.p("direction", "STRING", "Fling direction", req = true, enum = listOf("up", "down", "left", "right"))
        ),
        ToolRegistry.t(
            "scroll", "Scroll screen content in a direction using a touch gesture (use scroll_node when you know a scrollable node).",
            ToolRegistry.p("direction", "STRING", "Scroll direction", req = true, enum = listOf("up", "down", "left", "right")),
            ToolRegistry.p("amount", "STRING", "small | medium | large, default medium", enum = listOf("small", "medium", "large"))
        ),
        ToolRegistry.t(
            "scroll_until_found", "Keep scrolling until an element becomes visible or max scrolls reached.",
            ToolRegistry.p("query", "STRING", "text/desc to find", req = true),
            ToolRegistry.p("direction", "STRING", "default down", enum = listOf("up", "down", "left", "right")),
            ToolRegistry.p("max_scrolls", "INTEGER", "default 10")
        ),
        ToolRegistry.t(
            "drag_and_drop", "Press, hold and drag from one point to another (reorder lists, move icons).",
            ToolRegistry.i("x1"), ToolRegistry.i("y1"), ToolRegistry.i("x2"), ToolRegistry.i("y2"),
            ToolRegistry.p("hold_ms", "INTEGER", "Initial hold before moving, default 500"),
            ToolRegistry.p("duration_ms", "INTEGER", "Drag duration, default 600")
        ),
        ToolRegistry.t(
            "pinch", "Two-finger pinch/zoom (maps, photos, galleries).",
            ToolRegistry.i("x"), ToolRegistry.i("y"),
            ToolRegistry.p("mode", "STRING", "in = zoom in, out = zoom out", req = true, enum = listOf("in", "out")),
            ToolRegistry.p("distance", "INTEGER", "Finger travel in px, default 300")
        ),
        ToolRegistry.t(
            "custom_gesture", "Multi-stroke gesture from point paths (pattern unlock, drawing, signatures). Strokes run one after another.",
            ToolRegistry.p(
                "strokes", "ARRAY", "Ordered strokes: [{points:[{x,y}...], duration_ms}]",
                req = true, items = "OBJECT"
            )
        )
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val svc = ctx.requireAccessibility()
        return when (name) {
            "tap" -> {
                val (x, y) = point2(args)
                dispatch(svc, stroke(pathOf(x, y, x, y), 50))
                ctx.ok("action" to "tap", "x" to x, "y" to y)
            }
            "double_tap" -> {
                val (x, y) = point2(args)
                dispatch(svc, chainStrokes(listOf(pathOf(x, y, x, y) to 40L, pathOf(x, y, x, y) to 40L), gapMs = 90L))
                ctx.ok("action" to "double_tap", "x" to x, "y" to y)
            }
            "long_press" -> {
                val (x, y) = point2(args)
                val dur = args.optInt("duration_ms", 800).coerceIn(300, 30_000)
                dispatch(svc, stroke(pathOf(x, y, x, y), dur.toLong()))
                ctx.ok("action" to "long_press", "x" to x, "y" to y, "duration_ms" to dur)
            }
            "swipe" -> {
                val x1 = args.optInt("x1"); val y1 = args.optInt("y1")
                val x2 = args.optInt("x2"); val y2 = args.optInt("y2")
                val p = Path()
                p.moveTo(x1.toFloat(), y1.toFloat()); p.lineTo(x2.toFloat(), y2.toFloat())
                dispatch(svc, stroke(p, args.optLong("duration_ms", 300L).coerceIn(50, 15_000)))
                ctx.ok("action" to "swipe")
            }
            "fling" -> {
                val dir = args.optString("direction", "up")
                val b = ctx.screenBounds()
                val c = Point(b.centerX(), b.centerY())
                val reach = (minOf(b.width(), b.height()) * 0.9f).toInt()
                val target = when (dir) {
                    "up" -> Point(c.x, c.y - reach)
                    "down" -> Point(c.x, c.y + reach)
                    "left" -> Point(c.x - reach, c.y)
                    else -> Point(c.x + reach, c.y)
                }
                dispatch(svc, stroke(pathOf(c.x, c.y, target.x, target.y), 120L))
                ctx.ok("action" to "fling", "direction" to dir)
            }
            "scroll" -> {
                val dir = args.optString("direction", "down")
                val amount = args.optString("amount", "medium")
                val b = ctx.screenBounds()
                val frac = when (amount) { "small" -> 0.25f; "large" -> 0.7f; else -> 0.45f }
                val c = Point(b.centerX(), b.centerY())
                val dx = (b.width() * frac).toInt()
                val dy = (b.height() * frac).toInt()
                val (p1, p2) = when (dir) {
                    "up" -> c to Point(c.x, c.y - dy)
                    "down" -> c to Point(c.x, c.y + dy)
                    "left" -> c to Point(c.x - dx, c.y)
                    else -> c to Point(c.x + dx, c.y)
                }
                dispatch(svc, stroke(pathOf(p1.x, p1.y, p2.x, p2.y), 400L))
                delay(350)
                ctx.ok("action" to "scroll", "direction" to dir, "amount" to amount)
            }
            "scroll_until_found" -> {
                val query = args.getString("query")
                val dir = args.optString("direction", "down")
                val max = args.optInt("max_scrolls", 10).coerceIn(1, 40)
                var found: NodeQuery.Hit? = null
                var scrolls = 0
                while (scrolls < max) {
                    val r = ctx.root
                    if (r != null) {
                        found = NodeQuery.find(r, query).firstOrNull()
                        if (found != null) break
                    }
                    scrolls++
                    val b = ctx.screenBounds()
                    val c = Point(b.centerX(), b.centerY())
                    val dy = (b.height() * 0.45f).toInt()
                    val dx = (b.width() * 0.45f).toInt()
                    val (p1, p2) = when (dir) {
                        "up" -> c to Point(c.x, c.y - dy)
                        "down" -> c to Point(c.x, c.y + dy)
                        "left" -> c to Point(c.x - dx, c.y)
                        else -> c to Point(c.x + dx, c.y)
                    }
                    dispatch(svc, stroke(pathOf(p1.x, p1.y, p2.x, p2.y), 350L))
                    delay(600)
                }
                if (found != null) ctx.ok("found" to true, "scrolls" to scrolls, "element" to NodeQuery.nodeJson(found.node, found.ordinal))
                else ctx.fail("Element '$query' not found after $scrolls scrolls.", JSONObject().put("found", false))
            }
            "drag_and_drop" -> {
                val x1 = args.optInt("x1"); val y1 = args.optInt("y1")
                val x2 = args.optInt("x2"); val y2 = args.optInt("y2")
                val hold = args.optLong("hold_ms", 500L).coerceIn(100, 10_000)
                val dur = args.optLong("duration_ms", 600L).coerceIn(100, 15_000)
                dispatch(svc, chainStrokes(listOf(pathOf(x1, y1, x1, y1) to hold, pathOf(x1, y1, x2, y2) to dur), gapMs = 0L))
                delay(250)
                ctx.ok("action" to "drag_and_drop")
            }
            "pinch" -> {
                val cx = args.optInt("x"); val cy = args.optInt("y")
                val mode = args.optString("mode", "in")
                val dist = args.optInt("distance", 300).coerceIn(50, 2000)
                val half = dist / 2
                val p1 = Path(); val p2 = Path()
                if (mode == "in") {
                    p1.moveTo((cx - half).toFloat(), cy.toFloat()); p1.lineTo((cx + half / 3).toFloat(), cy.toFloat())
                    p2.moveTo((cx + half).toFloat(), cy.toFloat()); p2.lineTo((cx - half / 3).toFloat(), cy.toFloat())
                } else {
                    p1.moveTo((cx + half / 3).toFloat(), cy.toFloat()); p1.lineTo((cx - half).toFloat(), cy.toFloat())
                    p2.moveTo((cx - half / 3).toFloat(), cy.toFloat()); p2.lineTo((cx + half).toFloat(), cy.toFloat())
                }
                val b = GestureDescription.Builder()
                b.addStroke(stroke(p1, 450L))
                b.addStroke(stroke(p2, 450L))
                dispatch(svc, b)
                delay(250)
                ctx.ok("action" to "pinch", "mode" to mode)
            }
            "custom_gesture" -> {
                val strokes = args.optJSONArray("strokes")
                    ?: throw ToolException("custom_gesture requires a 'strokes' array.")
                if (strokes.length() == 0) throw ToolException("strokes array is empty.")
                if (strokes.length() > 10) throw ToolException("Maximum 10 strokes per gesture.")
                val segments = ArrayList<Pair<Path, Long>>()
                for (i in 0 until strokes.length()) {
                    val s = strokes.getJSONObject(i)
                    val pts = s.optJSONArray("points") ?: throw ToolException("stroke[$i] is missing 'points'.")
                    if (pts.length() < 2) throw ToolException("stroke[$i] needs at least 2 points.")
                    val dur = s.optLong("duration_ms", (pts.length() * 60L)).coerceIn(50, 20_000)
                    val path = Path()
                    pts.getJSONObject(0).let { path.moveTo(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()) }
                    for (j in 1 until pts.length()) {
                        pts.getJSONObject(j).let { path.lineTo(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()) }
                    }
                    segments.add(path to dur)
                }
                dispatch(svc, chainStrokes(segments, gapMs = 80L))
                delay(200)
                ctx.ok("action" to "custom_gesture", "strokes" to strokes.length())
            }
            else -> ctx.fail("Unknown gesture tool '$name'.")
        }
    }

    // ---- gesture primitives -------------------------------------------------

    private fun point2(args: JSONObject): Pair<Int, Int> =
        args.optInt("x") to args.optInt("y")

    private fun pathOf(x1: Int, y1: Int, x2: Int, y2: Int): Path =
        Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }

    private fun stroke(path: Path, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

    /** Sequential strokes: each begins after the previous one ends (plus gapMs). */
    private fun chainStrokes(segments: List<Pair<Path, Long>>, gapMs: Long): GestureDescription {
        val builder = GestureDescription.Builder()
        var offset = 0L
        var previous: GestureDescription.StrokeDescription? = null
        for ((path, dur) in segments) {
            previous = if (previous == null) {
                val s = GestureDescription.StrokeDescription(path, 0, dur, true)
                builder.addStroke(s); s
            } else {
                val s = previous.continueStroke(path, offset + gapMs, dur, true)
                builder.addStroke(s); s
            }
            offset += dur + gapMs
        }
        return builder.build()
    }

    private fun dispatch(svc: AgentAccessibilityService, gesture: GestureDescription): Boolean {
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        val done = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?, callback: android.os.CancellationSignal?) {
                ok.set(true); latch.countDown()
            }
            override fun onCancelled(g: GestureDescription?, callback: android.os.CancellationSignal?) {
                ok.set(false); latch.countDown()
            }
        }, null)
        if (!done) throw ToolException("System rejected the gesture (another gesture may be in progress). Wait a moment and retry.")
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return ok.get()
    }
}
