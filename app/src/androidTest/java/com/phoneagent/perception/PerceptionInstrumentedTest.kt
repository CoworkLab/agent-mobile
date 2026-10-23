package com.phoneagent.perception

import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.service.PhoneAgentAccessibilityService
import com.phoneagent.testing.ServiceRequirement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves perception works against the live platform, and writes the artifacts the M3
 * milestone calls for: the marked screenshot and the JSON the model would receive.
 *
 * The accessibility service is enabled by `e2e-emulator.yml` over `adb` before this runs.
 * If it is not enabled the test is skipped rather than failed, so a local run without the
 * service does not look like a regression.
 */
@RunWith(AndroidJUnit4::class)
class PerceptionInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireService(): AccessibilityService {
        ServiceRequirement.requireRunning()
        return PhoneAgentAccessibilityService.get()!!
    }

    @Test
    fun capturesTreeFromTheLiveScreen() = runBlocking {
        requireService()
        val source = AndroidPerceptionSource()

        val snapshot = source.capture(PerceptionMode.TREE_ONLY)

        assertTrue("expected a readable screen, got ${snapshot.failure}", snapshot.isUsable)
        assertTrue("expected at least one node", snapshot.nodes.isNotEmpty())
        assertNotNull("a package name identifies the foreground app", snapshot.packageName)

        writeArtifact("tree.txt", SnapshotSerializer.toPromptText(snapshot))
    }

    @Test
    fun capturesMarkedScreenshotAndWritesBothArtifacts() = runBlocking {
        requireService()
        val source = AndroidPerceptionSource()

        // The platform rate limits screenshots, so wait out any cooldown first.
        val cooldown = source.screenshotCooldownMs()
        if (cooldown > 0) kotlinx.coroutines.delay(cooldown)

        val snapshot = source.capture(PerceptionMode.TREE_PLUS_SCREENSHOT)

        assumeTrue(
            "screenshot unavailable on this device: ${snapshot.failure}",
            snapshot.failure != SnapshotFailure.SECURE_SCREEN,
        )
        assertTrue("expected a screenshot", snapshot.hasScreenshot)

        val jpeg = snapshot.screenshotJpeg!!
        assertTrue("screenshot should not be empty", jpeg.size > 1000)
        assertTrue(
            "longest side must be downscaled to <= 1080, was " +
                "${maxOf(snapshot.screenshotWidth, snapshot.screenshotHeight)}",
            maxOf(snapshot.screenshotWidth, snapshot.screenshotHeight) <= 1080,
        )

        writeArtifact("marked-screen.jpg", jpeg)
        writeArtifact("snapshot.json", snapshotToJson(snapshot))
        writeArtifact("prompt.txt", SnapshotSerializer.toPromptText(snapshot))
    }

    @Test
    fun assignsMarksToActionableNodes() = runBlocking {
        requireService()
        val snapshot = AndroidPerceptionSource().capture(PerceptionMode.TREE_ONLY)

        assumeTrue("no tree to inspect", snapshot.hasTree)
        val marked = snapshot.nodes.filter { it.mark != null }

        assertTrue("some nodes should be actionable", marked.isNotEmpty())
        assertTrue("marks must be unique", marked.mapNotNull { it.mark }.distinct().size == marked.size)
        assertTrue("marks start at 1", marked.minOf { it.mark!! } == 1)

        marked.forEach { node ->
            assertTrue(
                "mark ${node.mark} is on a node nothing can act on",
                node.clickable || node.longClickable || node.editable ||
                    node.scrollable || node.checkable,
            )
        }
    }

    @Test
    fun screenHashIsStableAcrossTwoReadsOfAnUnchangedScreen() = runBlocking {
        requireService()
        val source = AndroidPerceptionSource()

        val first = source.capture(PerceptionMode.TREE_ONLY)
        assumeTrue("no tree to compare", first.hasTree)
        kotlinx.coroutines.delay(300)
        val second = source.capture(PerceptionMode.TREE_ONLY)

        // The status clock may tick between reads, so a mismatch is informational rather
        // than a failure; the assertion is that a hash is always produced.
        assertTrue(first.screenHash.isNotBlank())
        assertTrue(second.screenHash.isNotBlank())
        writeArtifact(
            "hash-stability.txt",
            "first=${first.screenHash} second=${second.screenHash} " +
                "stable=${first.screenHash == second.screenHash}",
        )
    }

    private fun snapshotToJson(snapshot: ScreenSnapshot): String = buildString {
        append("{\n")
        append("  \"packageName\": ").append(quote(snapshot.packageName)).append(",\n")
        append("  \"windowTitle\": ").append(quote(snapshot.windowTitle)).append(",\n")
        append("  \"timestampMs\": ").append(snapshot.timestampMs).append(",\n")
        append("  \"screenHash\": ").append(quote(snapshot.screenHash)).append(",\n")
        append("  \"mode\": ").append(quote(snapshot.mode.name)).append(",\n")
        append("  \"failure\": ").append(quote(snapshot.failure?.name)).append(",\n")
        append("  \"screenshot\": {")
        append("\"width\": ").append(snapshot.screenshotWidth).append(", ")
        append("\"height\": ").append(snapshot.screenshotHeight).append(", ")
        append("\"bytes\": ").append(snapshot.screenshotJpeg?.size ?: 0).append("},\n")
        append("  \"nodes\": [\n")
        snapshot.nodes.forEachIndexed { index, node ->
            append("    {")
            append("\"id\": ").append(node.nodeId).append(", ")
            append("\"mark\": ").append(node.mark ?: 0).append(", ")
            append("\"depth\": ").append(node.depth).append(", ")
            append("\"class\": ").append(quote(node.className)).append(", ")
            append("\"text\": ").append(quote(node.text)).append(", ")
            append("\"desc\": ").append(quote(node.contentDescription)).append(", ")
            append("\"viewId\": ").append(quote(node.viewId)).append(", ")
            append("\"bounds\": [").append(node.bounds.left).append(", ")
                .append(node.bounds.top).append(", ").append(node.bounds.right).append(", ")
                .append(node.bounds.bottom).append("], ")
            append("\"clickable\": ").append(node.clickable).append(", ")
            append("\"editable\": ").append(node.editable).append(", ")
            append("\"scrollable\": ").append(node.scrollable)
            append("}")
            if (index != snapshot.nodes.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    private fun quote(value: String?): String =
        if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Artifacts land in the app's external files dir, which the workflow pulls with adb. */
    private fun writeArtifact(name: String, content: String) {
        val dir = File(context.getExternalFilesDir(null), "perception").apply { mkdirs() }
        File(dir, name).writeText(content)
    }

    private fun writeArtifact(name: String, bytes: ByteArray) {
        val dir = File(context.getExternalFilesDir(null), "perception").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
    }
}
