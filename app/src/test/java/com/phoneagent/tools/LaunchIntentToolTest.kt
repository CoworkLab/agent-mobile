package com.phoneagent.tools

import com.phoneagent.safety.IntentPolicy
import com.phoneagent.safety.IntentRequest
import com.phoneagent.safety.IntentValidator
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentToolTest {

    private val policy = IntentPolicy(
        allowedLow = setOf(
            "android.intent.action.VIEW",
            "android.intent.action.DIAL",
            "android.intent.action.SENDTO",
            "android.intent.action.WEB_SEARCH",
            "android.intent.action.MAIN",
        ),
        allowedMedium = setOf(
            "android.intent.action.SET_ALARM",
            "android.intent.action.SET_TIMER",
            "android.intent.action.INSERT",
        ),
        alwaysBlocked = setOf(
            "android.intent.action.CALL",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.app.action.ADD_DEVICE_ADMIN",
        ),
        allowedUriSchemes = setOf("http", "https", "geo", "mailto", "sms", "smsto", "tel", "content"),
        blockedUriSchemes = setOf("file", "intent", "javascript"),
    )

    private val denylist = PackageDenylist(listOf("com.bank.app", "com.upi.*"))

    /** A launcher that reports whatever handler a test wants, without a device. */
    private class FakeIntentLauncher(
        private val resolution: IntentResolution,
        private val startResult: LaunchResult? = null,
    ) : IntentLauncher {
        val started = mutableListOf<IntentRequest>()

        override suspend fun resolve(request: IntentRequest): IntentResolution = resolution

        override suspend fun start(request: IntentRequest): LaunchResult {
            started += request
            return startResult
                ?: LaunchResult.Started(
                    (resolution as? IntentResolution.Handled)?.packageName ?: "an app",
                )
        }
    }

    private fun tool(
        launcher: IntentLauncher,
        audit: InMemoryIntentAuditLog = InMemoryIntentAuditLog(),
    ) = LaunchIntentTool(
        validator = IntentValidator(policy, denylist),
        launcher = launcher,
        auditLog = audit,
        logger = Logger(),
        denylist = denylist,
    )

    private fun context() = FakeToolContext(testSnapshot(emptyList()))

    @Test
    fun `an https link opens and is reported as not-sent`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.android.chrome"))
        val audit = InMemoryIntentAuditLog()

        val result = tool(launcher, audit).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://example.com"),
            ),
            context(),
        )

        assertTrue(result.message, result.success)
        assertTrue("the model must be told nothing was sent", result.message.contains("Nothing was sent"))
        assertEquals(1, launcher.started.size)
        assertEquals(1, audit.all().size)
        assertEquals(RiskLevel.LOW, audit.all()[0].riskLevel)
    }

    @Test
    fun `a missing handler names the problem so the model can switch route`() = runBlocking {
        val launcher = FakeIntentLauncher(
            IntentResolution.NoHandler("android.intent.action.VIEW", "https://wa.me/123"),
        )
        val audit = InMemoryIntentAuditLog()

        val result = tool(launcher, audit).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://wa.me/123"),
            ),
            context(),
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("Nothing on this phone"))
        assertTrue("the refused attempt is still audited", audit.all().isNotEmpty())
    }

    @Test
    fun `placing a call is refused and audited as blocked`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.android.dialer"))
        val audit = InMemoryIntentAuditLog()

        val result = tool(launcher, audit).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.CALL", "uri" to "tel:+911234567890"),
            ),
            context(),
        )

        assertFalse(result.success)
        assertTrue(launcher.started.isEmpty())
        assertEquals(RiskLevel.BLOCKED, audit.all()[0].riskLevel)
    }

    @Test
    fun `a upi payment link is refused`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.phonepe.app"))
        val result = tool(launcher).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "upi://pay?pa=x@upi"),
            ),
            context(),
        )
        assertFalse(result.success)
    }

    @Test
    fun `an intent resolving into a denied package is refused`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.bank.app"))
        val result = tool(launcher).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://example.com"),
            ),
            context(),
        )
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("does not act inside"))
    }

    @Test
    fun `an ambiguous handler asks which app instead of picking one`() = runBlocking {
        val launcher = FakeIntentLauncher(
            IntentResolution.Ambiguous(listOf("com.app.a", "com.app.b")),
        )
        val result = tool(launcher).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://example.com"),
            ),
            context(),
        )

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("com.app.a"))
        assertTrue(launcher.started.isEmpty())
    }

    @Test
    fun `setting an alarm is allowed and logged as medium`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.android.deskclock"))
        val audit = InMemoryIntentAuditLog()

        val result = tool(launcher, audit).execute(
            ToolCall("launch_intent", mapOf("action" to "android.intent.action.SET_ALARM")),
            context(),
        )

        assertTrue(result.message, result.success)
        assertEquals(RiskLevel.MEDIUM, audit.all()[0].riskLevel)
        assertTrue(result.message, result.message.contains("logged"))
    }

    @Test
    fun `a prefilled whatsapp message opens but does not send`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.whatsapp"))
        val text = IntentValidator.encodeQueryValue("Hello, how are you?")

        val result = tool(launcher).execute(
            ToolCall(
                "launch_intent",
                mapOf(
                    "action" to "android.intent.action.VIEW",
                    "uri" to "https://wa.me/919876543210?text=$text",
                ),
            ),
            context(),
        )

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("only open"))
    }

    @Test
    fun `the audit row records action, uri, package and risk`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.android.chrome"))
        val audit = InMemoryIntentAuditLog()

        tool(launcher, audit).execute(
            ToolCall(
                "launch_intent",
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://example.com/x"),
            ),
            context(),
        )

        val entry = audit.all().single()
        assertEquals("android.intent.action.VIEW", entry.action)
        assertEquals("https://example.com/x", entry.uri)
        assertEquals("com.android.chrome", entry.resolvedPackage)
        assertEquals(RiskLevel.LOW, entry.riskLevel)
        assertTrue(entry.timestampMs > 0)
    }

    @Test
    fun `a missing action is refused`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.NoHandler("", null))
        val result = tool(launcher).execute(ToolCall("launch_intent", emptyMap()), context())
        assertFalse(result.success)
    }

    @Test
    fun `extras are passed through as strings`() = runBlocking {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.app.mail"))
        val result = tool(launcher).execute(
            ToolCall(
                "launch_intent",
                mapOf(
                    "action" to "android.intent.action.SENDTO",
                    "uri" to "mailto:a@b.com",
                    "extras" to mapOf("android.intent.extra.SUBJECT" to "Hello"),
                ),
            ),
            context(),
        )

        assertTrue(result.message, result.success)
        assertEquals(
            "Hello",
            launcher.started[0].extras["android.intent.extra.SUBJECT"],
        )
    }

    @Test
    fun `launch_intent is never batchable`() {
        val launcher = FakeIntentLauncher(IntentResolution.Handled("com.android.chrome"))
        assertFalse(tool(launcher).allowedInBatch)
    }
}
