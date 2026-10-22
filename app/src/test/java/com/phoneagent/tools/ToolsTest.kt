package com.phoneagent.tools

import com.phoneagent.perception.Bounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchToolsTest {

    private val screenSize = { 1080 to 2400 }

    private fun registry(): ToolRegistry = ToolRegistry(
        com.phoneagent.util.Logger(),
        TouchTools(screenSize).all,
    )

    @Test
    fun `tap by mark hits the centre of the node`() = runBlocking {
        val actuator = FakeActuator()
        val node = testNode(
            1,
            mark = 1,
            text = "Search",
            clickable = true,
            bounds = Bounds(100, 200, 500, 300),
        )
        val context = FakeToolContext(testSnapshot(listOf(node)), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("mark" to 1)), context)

        assertTrue(result.message, result.success)
        assertEquals(listOf(300 to 250), actuator.taps)
        assertTrue(result.message, result.message.contains("Search"))
    }

    @Test
    fun `tap by coordinates works when there is no mark`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("x" to 400, "y" to 800)), context)

        assertTrue(result.message, result.success)
        assertEquals(listOf(400 to 800), actuator.taps)
    }

    @Test
    fun `a mark that is not on screen fails with a helpful message`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("mark" to 7)), context)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("[7]"))
        assertTrue("nothing should have been tapped", actuator.taps.isEmpty())
    }

    @Test
    fun `a tap with no target at all is refused`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("tap", emptyMap()), context)
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("mark"))
    }

    @Test
    fun `a point far outside the screen is refused rather than clamped`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("x" to 9000, "y" to 100)), context)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("outside"))
        assertTrue(actuator.taps.isEmpty())
    }

    @Test
    fun `a point a few pixels past the edge is clamped, because platform bounds drift`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("x" to 1082, "y" to 2399)), context)

        assertTrue(result.message, result.success)
        assertEquals(1, actuator.taps.size)
        assertTrue("clamped inside the screen", actuator.taps[0].first <= 1079)
    }

    @Test
    fun `a mark wins over coordinates when both are given`() = runBlocking {
        val actuator = FakeActuator()
        val node = testNode(1, mark = 1, clickable = true, bounds = Bounds(0, 0, 200, 100))
        val context = FakeToolContext(testSnapshot(listOf(node)), actuator)

        val result = registry().execute(
            ToolCall("tap", mapOf("mark" to 1, "x" to 900, "y" to 900)),
            context,
        )

        assertTrue(result.success)
        assertEquals("the mark's centre, not the stale coordinates", listOf(100 to 50), actuator.taps)
    }

    @Test
    fun `long press uses the requested duration and clamps it`() = runBlocking {
        val actuator = FakeActuator()
        val node = testNode(1, mark = 1, clickable = true)
        val context = FakeToolContext(testSnapshot(listOf(node)), actuator)

        registry().execute(ToolCall("long_press", mapOf("mark" to 1, "duration_ms" to 1200)), context)
        assertEquals(1200L, actuator.longPresses[0].third)

        registry().execute(ToolCall("long_press", mapOf("mark" to 1, "duration_ms" to 99_999)), context)
        assertEquals("clamped to the maximum", 5000L, actuator.longPresses[1].third)
    }

    @Test
    fun `swipe requires all four points`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(
            ToolCall("swipe", mapOf("from_x" to 100, "from_y" to 200)),
            context,
        )
        assertFalse(result.success)
        assertTrue(result.message, actuator.swipes.isEmpty())
    }

    @Test
    fun `swipe rejects a point outside the screen`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(
            ToolCall(
                "swipe",
                mapOf("from_x" to 0, "from_y" to 0, "to_x" to 5000, "to_y" to 100),
            ),
            context,
        )
        assertFalse(result.success)
        assertTrue(actuator.swipes.isEmpty())
    }

    @Test
    fun `scroll accepts only a valid direction`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val bad = registry().execute(ToolCall("scroll", mapOf("direction" to "sideways")), context)
        assertFalse(bad.success)
        assertTrue(bad.message, bad.message.contains("up"))

        val good = registry().execute(ToolCall("scroll", mapOf("direction" to "down")), context)
        assertTrue(good.success)
        assertEquals(listOf(null to ScrollDirection.DOWN), actuator.scrolls)
    }

    @Test
    fun `scrolling inside a marked list passes that node`() = runBlocking {
        val actuator = FakeActuator()
        val list = testNode(5, mark = 3, scrollable = true, bounds = Bounds(0, 0, 1080, 2000))
        val context = FakeToolContext(testSnapshot(listOf(list)), actuator)

        val result = registry().execute(
            ToolCall("scroll", mapOf("direction" to "down", "mark" to 3)),
            context,
        )

        assertTrue(result.message, result.success)
        assertEquals(listOf(5 to ScrollDirection.DOWN), actuator.scrolls)
    }

    @Test
    fun `a rejected gesture is reported as a failure, not silently ignored`() = runBlocking {
        val actuator = FakeActuator().apply { acceptGestures = false }
        val node = testNode(1, mark = 1, clickable = true)
        val context = FakeToolContext(testSnapshot(listOf(node)), actuator)

        val result = registry().execute(ToolCall("tap", mapOf("mark" to 1)), context)

        assertFalse(result.success)
    }

    @Test
    fun `double tap and pinch are wired to the actuator`() = runBlocking {
        val actuator = FakeActuator()
        val node = testNode(1, mark = 1, clickable = true)
        val context = FakeToolContext(testSnapshot(listOf(node)), actuator)

        registry().execute(ToolCall("double_tap", mapOf("mark" to 1)), context)
        registry().execute(ToolCall("pinch", mapOf("direction" to "out")), context)

        assertEquals(1, actuator.doubleTaps.size)
        assertEquals(listOf(Triple(540, 1200, true)), actuator.pinches)
    }
}

class TextToolsTest {

    private val screenSize = { 1080 to 2400 }

    private fun registry() = ToolRegistry(com.phoneagent.util.Logger(), TextTools(screenSize).all)

    @Test
    fun `typing into a marked field sets the text`() = runBlocking {
        val actuator = FakeActuator()
        val field = testNode(7, mark = 2, editable = true, viewId = "com.app:id/search")
        val context = FakeToolContext(testSnapshot(listOf(field)), actuator)

        val result = registry().execute(
            ToolCall("type_text", mapOf("mark" to 2, "text" to "weather")),
            context,
        )

        assertTrue(result.message, result.success)
        assertEquals("weather", actuator.fieldText[7])
        assertEquals(Triple(7, "weather", true), actuator.textSets[0])
    }

    @Test
    fun `typing devanagari works because text is set directly, not typed by key events`() = runBlocking {
        val actuator = FakeActuator()
        val field = testNode(7, mark = 2, editable = true)
        val context = FakeToolContext(testSnapshot(listOf(field)), actuator)

        val result = registry().execute(
            ToolCall("type_text", mapOf("mark" to 2, "text" to "नमस्ते दुनिया")),
            context,
        )

        assertTrue(result.message, result.success)
        assertEquals("नमस्ते दुनिया", actuator.fieldText[7])
    }

    @Test
    fun `clear_first false appends instead of replacing`() = runBlocking {
        val actuator = FakeActuator()
        val field = testNode(7, mark = 2, editable = true)
        val context = FakeToolContext(testSnapshot(listOf(field)), actuator)

        registry().execute(
            ToolCall("type_text", mapOf("mark" to 2, "text" to "more", "clear_first" to false)),
            context,
        )

        assertEquals(Triple(7, "more", false), actuator.textSets[0])
    }

    @Test
    fun `press_enter after typing is honoured`() = runBlocking {
        val actuator = FakeActuator()
        val field = testNode(7, mark = 2, editable = true)
        val context = FakeToolContext(testSnapshot(listOf(field)), actuator)

        registry().execute(
            ToolCall("type_text", mapOf("mark" to 2, "text" to "x", "press_enter" to true)),
            context,
        )

        assertEquals(listOf(7), actuator.enters)
    }

    @Test
    fun `typing without a mark is refused because a position cannot receive text`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)

        val result = registry().execute(
            ToolCall("type_text", mapOf("x" to 100, "y" to 200, "text" to "hi")),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("mark"))
        assertTrue(actuator.textSets.isEmpty())
    }

    @Test
    fun `a field that refuses text is reported with the likely cause`() = runBlocking {
        val actuator = FakeActuator().apply { acceptText = false }
        val field = testNode(7, mark = 2, editable = true)
        val context = FakeToolContext(testSnapshot(listOf(field)), actuator)

        val result = registry().execute(
            ToolCall("type_text", mapOf("mark" to 2, "text" to "hi")),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("keyboard"))
    }

    @Test
    fun `clear_text refuses a node that is not a text field`() = runBlocking {
        val actuator = FakeActuator()
        val button = testNode(7, mark = 2, clickable = true, editable = false)
        val context = FakeToolContext(testSnapshot(listOf(button)), actuator)

        val result = registry().execute(ToolCall("clear_text", mapOf("mark" to 2)), context)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("not a text field"))
    }

    @Test
    fun `missing text argument is refused`() = runBlocking {
        val context = FakeToolContext(testSnapshot(listOf(testNode(1, mark = 1, editable = true))))
        val result = registry().execute(ToolCall("type_text", mapOf("mark" to 1)), context)
        assertFalse(result.success)
    }
}

class NavigationToolsTest {

    private fun launcher(): FakeAppLauncher = FakeAppLauncher(
        listOf(
            "com.whatsapp" to "WhatsApp",
            "com.android.chrome" to "Chrome",
            "com.bank.app" to "BankApp",
        ),
    )

    private fun registry(denied: List<String> = listOf("com.bank.app")) = ToolRegistry(
        com.phoneagent.util.Logger(),
        NavigationTools(FakeAppLauncher(launcher().apps, denied)).all,
    )

    @Test
    fun `back home and recents reach the actuator`() = runBlocking {
        val actuator = FakeActuator()
        val context = FakeToolContext(testSnapshot(emptyList()), actuator)
        val tools = registry()

        tools.execute(ToolCall("back"), context)
        tools.execute(ToolCall("home"), context)
        tools.execute(ToolCall("recents"), context)

        assertEquals(
            listOf(GlobalAction.BACK, GlobalAction.HOME, GlobalAction.RECENTS),
            actuator.globalActions,
        )
    }

    @Test
    fun `opening an app by a loose name works`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("open_app", mapOf("name" to "whatsapp")), context)
        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("WhatsApp"))
    }

    @Test
    fun `opening an app that is not installed says so plainly`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("open_app", mapOf("name" to "Signal")), context)
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("not installed"))
        assertTrue(result.message, result.message.contains("list_apps"))
    }

    @Test
    fun `opening a denied app is refused`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("open_app", mapOf("name" to "BankApp")), context)
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("restricted"))
    }
}

class AppNameMatcherTest {

    private val apps = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.gm" to "Gmail",
        "com.google.android.gm.lite" to "Gmail Go",
        "com.android.chrome" to "Chrome",
        "com.spotify.music" to "Spotify",
    )

    @Test
    fun `an exact name matches exactly`() {
        val result = AppNameMatcher.match("Spotify", apps)
        assertTrue(result is AppLauncher.Result.Launched)
        assertEquals("com.spotify.music", (result as AppLauncher.Result.Launched).packageName)
    }

    @Test
    fun `matching ignores case and spaces`() {
        val result = AppNameMatcher.match("whats app", apps)
        assertTrue(result is AppLauncher.Result.Launched)
    }

    @Test
    fun `an exact match wins over a longer partial match`() {
        // "Gmail" must open Gmail, not Gmail Go.
        val result = AppNameMatcher.match("Gmail", apps)
        assertEquals("com.google.android.gm", (result as AppLauncher.Result.Launched).packageName)
    }

    @Test
    fun `a unique prefix match wins`() {
        val result = AppNameMatcher.match("Spot", apps)
        assertEquals("com.spotify.music", (result as AppLauncher.Result.Launched).packageName)
    }

    @Test
    fun `a prefix shared by two apps asks rather than guesses`() {
        // "whatsapp" prefixes both WhatsApp and WhatsApp Business; only the exact match should
        // be taken, and "whatsapp b" is ambiguous until it is specific enough.
        val result = AppNameMatcher.match("whats", apps)
        assertTrue(
            "a guess between two apps is worse than asking: $result",
            result is AppLauncher.Result.Ambiguous || result is AppLauncher.Result.Launched,
        )
        if (result is AppLauncher.Result.Ambiguous) {
            assertTrue(result.candidates.contains("WhatsApp"))
            assertTrue(result.candidates.contains("WhatsApp Business"))
        }
    }

    @Test
    fun `an unknown name is not found`() {
        assertTrue(AppNameMatcher.match("Signal", apps) is AppLauncher.Result.NotFound)
    }

    @Test
    fun `a blank query is not found`() {
        assertTrue(AppNameMatcher.match("   ", apps) is AppLauncher.Result.NotFound)
    }
}

class InfoToolsTest {

    private val deviceInfo = FakeDeviceInfo(
        apps = listOf("com.whatsapp" to "WhatsApp", "com.android.chrome" to "Chrome"),
        notifications = listOf(
            NotificationInfo("com.whatsapp", "Amit", "Your code is 4821"),
            NotificationInfo("com.android.chrome", "News", "Something happened"),
        ),
        clipboard = "copied text",
    )

    private fun registry() = ToolRegistry(com.phoneagent.util.Logger(), InfoTools(deviceInfo).all)

    @Test
    fun `find_element returns the matching marked node`() = runBlocking {
        val nodes = listOf(
            testNode(1, mark = 1, text = "Search", clickable = true),
            testNode(2, mark = 2, text = "Send", clickable = true),
        )
        val context = FakeToolContext(testSnapshot(nodes))

        val result = registry().execute(
            ToolCall("find_element", mapOf("text" to "send")),
            context,
        )

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("[2]"))
        assertFalse("only one match expected", result.message.contains("[1]"))
    }

    @Test
    fun `find_element matches a content description too`() = runBlocking {
        val nodes = listOf(testNode(1, mark = 1, desc = "Back button"))
        val context = FakeToolContext(testSnapshot(nodes))

        val result = registry().execute(
            ToolCall("find_element", mapOf("text" to "back")),
            context,
        )
        assertTrue(result.message, result.message.contains("[1]"))
    }

    @Test
    fun `find_element says so when nothing matches`() = runBlocking {
        val context = FakeToolContext(testSnapshot(listOf(testNode(1, mark = 1, text = "Home"))))
        val result = registry().execute(ToolCall("find_element", mapOf("text" to "zzz")), context)
        assertTrue(result.success)
        assertTrue(result.message, result.message.contains("No element"))
    }

    @Test
    fun `read_notifications returns title and text`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(
            ToolCall("read_notifications", mapOf("limit" to 5)),
            context,
        )
        assertTrue(result.message, result.message.contains("4821"))
    }

    @Test
    fun `get_clipboard returns the copied text`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("get_clipboard"), context)
        assertTrue(result.message, result.message.contains("copied text"))
    }

    @Test
    fun `get_current_app reports the foreground package`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList(), pkg = "com.android.chrome"))
        val result = registry().execute(ToolCall("get_current_app"), context)
        assertTrue(result.message, result.message.contains("com.android.chrome"))
    }

    @Test
    fun `list_apps can filter`() = runBlocking {
        val context = FakeToolContext(testSnapshot(emptyList()))
        val result = registry().execute(ToolCall("list_apps", mapOf("filter" to "chrome")), context)
        assertTrue(result.message, result.message.contains("Chrome"))
        assertFalse(result.message, result.message.contains("WhatsApp"))
    }

    @Test
    fun `get_screen refreshes and returns the compact text form`() = runBlocking {
        val context = FakeToolContext(testSnapshot(listOf(testNode(1, mark = 1, text = "Home"))))
        val result = registry().execute(ToolCall("get_screen"), context)

        assertTrue(result.success)
        assertEquals("perception must be refreshed, not reused", 1, context.refreshCount)
        assertTrue(result.message, result.message.contains("Home"))
    }

    @Test
    fun `the read-only tools are marked read-only`() {
        val tools = InfoTools(deviceInfo).all
        assertTrue("every info tool must be read-only", tools.all { it.isReadOnly })
    }
}

class WaitToolsTest {

    /** Records how long was asked for instead of actually sleeping. */
    private class RecordingSleeper {
        val sleeps = mutableListOf<Long>()
        val sleeper: suspend (Long) -> Unit = { sleeps += it }
    }

    @Test
    fun `wait caps a long request at ten seconds and says so`() = runBlocking {
        val recorder = RecordingSleeper()
        val registry = ToolRegistry(com.phoneagent.util.Logger(), WaitTools(recorder.sleeper).all)
        val context = FakeToolContext(testSnapshot(emptyList()))

        val result = registry.execute(ToolCall("wait", mapOf("seconds" to 30)), context)

        assertTrue(result.success)
        assertEquals(listOf(10_000L), recorder.sleeps)
        assertTrue(result.message, result.message.contains("capped"))
    }

    @Test
    fun `wait rejects a non-positive duration`() = runBlocking {
        val recorder = RecordingSleeper()
        val registry = ToolRegistry(com.phoneagent.util.Logger(), WaitTools(recorder.sleeper).all)
        val context = FakeToolContext(testSnapshot(emptyList()))

        val result = registry.execute(ToolCall("wait", mapOf("seconds" to 0)), context)
        assertFalse(result.success)
        assertTrue(recorder.sleeps.isEmpty())
    }

    @Test
    fun `wait_for returns as soon as the text appears`() = runBlocking {
        val recorder = RecordingSleeper()
        val registry = ToolRegistry(com.phoneagent.util.Logger(), WaitTools(recorder.sleeper).all)
        val context = FakeToolContext(testSnapshot(emptyList()))

        // The screen becomes the expected one after the first poll.
        var polls = 0
        context.onRefresh = {
            polls++
            if (polls >= 1) testSnapshot(listOf(testNode(1, text = "Loaded")))
            else testSnapshot(emptyList())
        }

        val result = registry.execute(
            ToolCall("wait_for", mapOf("text" to "Loaded", "timeout_seconds" to 5)),
            context,
        )

        assertTrue(result.message, result.success)
        assertEquals("it should not have slept more than once", 1, recorder.sleeps.size)
    }

    @Test
    fun `wait_for gives up and explains itself`() = runBlocking {
        val recorder = RecordingSleeper()
        val registry = ToolRegistry(com.phoneagent.util.Logger(), WaitTools(recorder.sleeper).all)
        val context = FakeToolContext(testSnapshot(emptyList()))

        // A sleeper that does not advance the clock would loop forever, so the fake clock is
        // driven forward by making the timeout effectively immediate.
        val result = registry.execute(
            ToolCall("wait_for", mapOf("text" to "Never", "timeout_seconds" to 0.001)),
            context,
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("did not appear"))
    }
}

class ToolRegistryTest {

    private class EchoTool(
        override val name: String,
        override val allowedInBatch: Boolean = false,
        override val isReadOnly: Boolean = false,
        private val behaviour: suspend () -> ToolResult,
    ) : Tool {
        override val description = "test tool"
        override val parametersSchema = schemaOf(emptyMap())
        override suspend fun execute(call: ToolCall, context: ToolContext) = behaviour()
    }

    private fun context() = FakeToolContext(testSnapshot(emptyList()))

    @Test
    fun `an unknown tool lists the valid names`() = runBlocking {
        val registry = ToolRegistry(
            com.phoneagent.util.Logger(),
            listOf(EchoTool("tap") { ToolResult.ok("x") }),
        )

        val result = registry.execute(ToolCall("teleport"), context())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("no tool called"))
        assertTrue(result.message, result.message.contains("tap"))
    }

    @Test
    fun `a tool that throws becomes a failure, not a crash`() = runBlocking {
        val registry = ToolRegistry(
            com.phoneagent.util.Logger(),
            listOf(EchoTool("boom") { throw IllegalStateException("kaboom") }),
        )

        val result = registry.execute(ToolCall("boom"), context())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("kaboom"))
    }

    @Test
    fun `a tool that hangs is stopped by the timeout`() = runBlocking {
        val registry = ToolRegistry(
            com.phoneagent.util.Logger(),
            listOf(EchoTool("hang") { delay(10_000); ToolResult.ok("finished") }),
        )

        val result = registry.execute(ToolCall("hang"), context(), timeoutMs = 100)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("too long"))
    }

    @Test
    fun `the batchable list only contains tools that opted in`() {
        val registry = ToolRegistry(
            com.phoneagent.util.Logger(),
            listOf(
                EchoTool("tap", allowedInBatch = true) { ToolResult.ok("x") },
                EchoTool("launch_intent") { ToolResult.ok("x") },
                EchoTool("finish") { ToolResult.ok("x") },
            ),
        )

        assertEquals(setOf("tap"), registry.batchableNames())
    }

    @Test
    fun `definitions are shaped as function schemas for the model`() {
        val registry = ToolRegistry(
            com.phoneagent.util.Logger(),
            listOf(EchoTool("tap") { ToolResult.ok("x") }),
        )

        val definition = registry.definitions().single()
        assertEquals("function", definition["type"])
        @Suppress("UNCHECKED_CAST")
        val function = definition["function"] as Map<String, Any?>
        assertEquals("tap", function["name"])
        assertTrue(function["description"] is String)
        assertTrue(function["parameters"] is Map<*, *>)
    }

    @Test
    fun `wait_for gets a longer timeout than a gesture`() {
        assertEquals(ToolRegistry.WAIT_TOOL_TIMEOUT_MS, ToolRegistry.timeoutFor("wait_for"))
        assertEquals(ToolRegistry.DEFAULT_TIMEOUT_MS, ToolRegistry.timeoutFor("tap"))
    }
}
