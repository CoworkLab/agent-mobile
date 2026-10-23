package com.phoneagent.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.phoneagent.perception.AndroidPerceptionSource
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.SnapshotFailure
import com.phoneagent.safety.InjectionDetector
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.testing.ServiceRequirement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scenarios that need a real screen.
 *
 * Everything that is a decision is already covered in-process by `RequiredScenariosTest`, for
 * free and in milliseconds. What is left here is the part that cannot be faked: whether the
 * accessibility tree a real device produces is the tree the agent assumes. A custom-drawn
 * canvas, a `FLAG_SECURE` window and a keyboard that covers a field all behave differently on
 * a device than in a model of one, and these are the tests that notice.
 *
 * The testbed app must be installed; `e2e-emulator.yml` does that. If it is missing the tests
 * skip rather than fail, so a local run without it does not look like a regression.
 */
@RunWith(AndroidJUnit4::class)
class TestbedInstrumentedTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val testbedPackage = "com.phoneagent.testbed"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireService() = ServiceRequirement.requireRunning()

    /** Skips rather than fails when the testbed is not installed. */
    private fun requireTestbed() {
        val installed = runCatching {
            context.packageManager.getPackageInfo(testbedPackage, 0)
        }.isSuccess
        assumeTrue("The testbed app is not installed; see e2e-emulator.yml", installed)
    }

    /**
     * Opens a testbed screen and waits for it to be the foreground app.
     *
     * Waiting on the package rather than on a sleep is what keeps this from being flaky: the
     * screen is ready when the platform says it is, not when a fixed delay has passed.
     */
    private fun openScreen(activity: String) {
        requireTestbed()
        device.executeShellCommand("am start -n $testbedPackage/.$activity")
        device.wait(Until.hasObject(By.pkg(testbedPackage)), 10_000)
    }

    private fun capture(): ScreenSnapshot = runBlocking {
        val source = AndroidPerceptionSource()
        val cooldown = source.screenshotCooldownMs()
        if (cooldown > 0) kotlinx.coroutines.delay(cooldown)
        source.capture(PerceptionMode.TREE_ONLY)
    }

    private fun textOf(snapshot: ScreenSnapshot): String =
        snapshot.nodes.joinToString(" ") {
            it.text.orEmpty() + " " + it.contentDescription.orEmpty()
        }

    @Test
    fun `the chat screen exposes a Send button the agent can find`() {
        openScreen("ChatActivity")

        val snapshot = capture()

        assertTrue("the chat screen should be readable", snapshot.isUsable)
        assertTrue(
            "the agent must be able to find Send by name: ${textOf(snapshot)}",
            textOf(snapshot).contains("Send", ignoreCase = true),
        )
    }

    @Test
    fun `the deep link arrives with the message already typed`() {
        requireTestbed()

        // The one thing the JVM harness cannot model: an intent that carries data into the
        // screen. The composer must arrive with the text filled in and the Send button still
        // present and un-pressed. Reaching the screen is not permission to send.
        device.executeShellCommand(
            "am start -a android.intent.action.VIEW " +
                "-d 'testchat://compose?to=123&text=meeting%20at%206' " +
                "-n $testbedPackage/.ChatActivity",
        )
        device.wait(Until.hasObject(By.pkg(testbedPackage)), 10_000)

        val snapshot = capture()
        val text = textOf(snapshot)

        assertTrue("the chat must be readable", snapshot.isUsable)
        assertTrue(
            "the prefilled message must be on screen, not just the empty field: $text",
            text.contains("meeting at 6"),
        )
        assertTrue(
            "Send must still be there and untouched: $text",
            text.contains("Send", ignoreCase = true),
        )
    }

    @Test
    fun `the form exposes three fields and a Submit button`() {
        openScreen("FormActivity")

        val snapshot = capture()
        val editable = snapshot.nodes.count { it.editable }

        assertTrue("three fields should be editable, saw $editable", editable >= 3)
        assertTrue(
            "Submit must be findable: ${textOf(snapshot)}",
            textOf(snapshot).contains("Submit", ignoreCase = true),
        )
    }

    @Test
    fun `the custom-drawn editor exposes no per-handle nodes`() {
        openScreen("EditorActivity")

        val snapshot = capture()

        // This is the difficulty the editor exists for. If the handles were suddenly visible,
        // the tree-only path would look better than it is and the fallback would go untested.
        //
        // What must not appear is an *interactive* handle. The screen's own instruction text
        // says the word "handles", and that is a plain label a person reads, not something the
        // agent can act on; matching it would make this test fail on a correct screen.
        val handleLike = snapshot.nodes.filter {
            val named = it.text?.contains("handle", ignoreCase = true) == true ||
                it.contentDescription?.contains("handle", ignoreCase = true) == true
            named && (it.clickable || it.scrollable)
        }
        assertTrue(
            "the trim handles are drawn, not views, so no tappable handle may appear: $handleLike",
            handleLike.isEmpty(),
        )
    }

    @Test
    fun `the long list can be scrolled to reach the target item`() {
        openScreen("LongListActivity")

        val first = capture()
        assertTrue("the list should be readable", first.isUsable)

        // The target is far down, so it cannot be on the first screen.
        assertFalse(
            "the target must not already be visible, or this proves nothing",
            textOf(first).contains("Target item 47"),
        )

        repeat(12) {
            device.executeShellCommand("input swipe 540 1800 540 400 300")
        }
        device.waitForIdle()

        val after = capture()
        assertTrue(
            "scrolling should bring the target into view: ${textOf(after).take(300)}",
            textOf(after).contains("Target item 47"),
        )
    }

    @Test
    fun `the secure screen reports a failure rather than an empty success`() {
        openScreen("SecureActivity")

        val snapshot = capture()

        // FLAG_SECURE blocks the capture. The honest outcome is a named failure; an empty tree
        // with no failure would let the agent tap at nothing and call it progress.
        assertTrue(
            "a secure screen must be reported, not silently empty: $snapshot",
            snapshot.failure != null || !snapshot.isUsable,
        )
        assertTrue(
            "the failure should be one of the named ones, saw ${snapshot.failure}",
            snapshot.failure in setOf(
                SnapshotFailure.SECURE_SCREEN,
                SnapshotFailure.BLANK_CAPTURE,
                SnapshotFailure.UNKNOWN,
            ),
        )
    }

    @Test
    fun `the injection page is flagged by the shipped detector`() {
        openScreen("InjectionActivity")

        val snapshot = capture()
        val detector = InjectionDetector(
            SafetyConfigLoader.parseKeywords(
                context.assets.open("safety_keywords.json").bufferedReader().use { it.readText() },
            ),
        )

        val scan = detector.scan(textOf(snapshot))

        assertFalse("the injected page must be flagged as unclean", scan.clean)
        assertNotNull("the scan should say what matched", scan.matches)
    }

    @Test
    fun `the Hindi field and button are readable as Devanagari`() {
        openScreen("HindiActivity")

        val snapshot = capture()
        val text = textOf(snapshot)

        // A screenshot-only reader or a lossy encoder turns these into boxes. The tree should
        // carry them intact, which is what makes typing Hindi possible.
        assertTrue(
            "the Hindi label must survive: $text",
            text.contains("नाम") || text.contains("अपना"),
        )
    }

    @Test
    fun `the bank screen is readable so the guard can judge it`() {
        openScreen("BankActivity")

        val snapshot = capture()

        // The testbed's bank shares the testbed package, so this cannot assert on the denylist
        // itself. What it proves is that a bank-shaped screen reaches the guard with a package
        // name attached, which is what the refusal is decided from.
        assertTrue("the bank screen should be readable", snapshot.isUsable)
        assertNotNull("a package name must be attached", snapshot.packageName)
        assertTrue(
            "the account label must be visible: ${textOf(snapshot)}",
            textOf(snapshot).contains("Balance", ignoreCase = true) ||
                textOf(snapshot).contains("Test Bank", ignoreCase = true),
        )
    }

    @Test
    fun `the broken button leaves the screen unchanged`() {
        openScreen("StuckActivity")

        val before = capture()
        val broken = before.nodes.firstOrNull {
            it.text?.contains("Do the thing", ignoreCase = true) == true
        }
        assumeTrue("the broken button was not found", broken != null)

        val bounds = broken!!.bounds
        device.click(
            (bounds.left + bounds.right) / 2,
            (bounds.top + bounds.bottom) / 2,
        )
        device.waitForIdle()

        val after = capture()

        // The whole point of the screen: the tap lands and nothing moves. The stuck detector is
        // what turns this into a stop rather than an infinite loop.
        assertTrue(
            "the screen hash must not change, or the stuck case is not reproduced",
            before.screenHash == after.screenHash,
        )
    }
}
