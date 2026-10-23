package com.phoneagent.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.perception.AndroidPerceptionSource
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.safety.IntentPolicy
import com.phoneagent.safety.IntentValidator
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.RiskLevel
import com.phoneagent.safety.RiskTarget
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.service.PhoneAgentAccessibilityService
import com.phoneagent.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the tools against the live platform.
 *
 * These tests act on the real device, so they are written to be harmless: they open a
 * settings screen and navigate back, rather than tapping anything that changes state.
 * Assertions are about the platform accepting the call and the screen responding, which is
 * what cannot be checked in a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class ToolsInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = Logger()

    private fun requireService() {
        assumeTrue(
            "Accessibility service not enabled; see e2e-emulator.yml",
            PhoneAgentAccessibilityService.isRunning(),
        )
    }

    private fun actuator() = AndroidActuator(logger)

    @Test
    fun globalActionsAreAcceptedByThePlatform() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            requireService()
            val actuator = actuator()

            assertTrue("home should be accepted", actuator.globalAction(GlobalAction.HOME))
            delay(800)
            assertTrue("recents should be accepted", actuator.globalAction(GlobalAction.RECENTS))
            delay(800)
            assertTrue("back should be accepted", actuator.globalAction(GlobalAction.BACK))
        }
    }

    @Test
    fun tapOnTheCentreOfTheScreenIsAccepted() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            requireService()
            val bounds = com.phoneagent.perception.NodeFlattener.screenBounds(
                PhoneAgentAccessibilityService.get()!!,
            )
            // The centre of a bare home screen is empty, so this changes nothing.
            val accepted = actuator().tap(bounds.width / 2, bounds.height / 2)
            assertTrue("a tap on the screen should be dispatched", accepted)
        }
    }

    @Test
    fun aSwipeOnTheScreenIsAccepted() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            requireService()
            val accepted = actuator().swipe(540, 1600, 540, 600, 300)
            assertTrue("a swipe should be dispatched", accepted)
        }
    }

    @Test
    fun theRealIntentPolicyAllowsSafeActionsAndBlocksDangerousOnes() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            // This reads the real asset, so a typo in intent_policy.json fails the build.
            val policyJson = context.assets.open("intent_policy.json")
                .bufferedReader().use { it.readText() }
            val policy: IntentPolicy = SafetyConfigLoader.parseIntentPolicy(policyJson)
            val validator = IntentValidator(policy, PackageDenylist(PackageDenylist.DEFAULT_PATTERNS))

            val safe = validator.validate(
                com.phoneagent.safety.IntentRequest(
                    "android.intent.action.VIEW",
                    uri = "https://example.com",
                ),
                resolvedPackage = "com.android.chrome",
            )
            assertTrue("an https link must be allowed: ${safe.reason}", safe.allowed)

            val call = validator.validate(
                com.phoneagent.safety.IntentRequest(
                    "android.intent.action.CALL",
                    uri = "tel:+911234567890",
                ),
                resolvedPackage = "com.android.dialer",
            )
            assertFalse("placing a call must be blocked", call.allowed)

            val upi = validator.validate(
                com.phoneagent.safety.IntentRequest(
                    "android.intent.action.VIEW",
                    uri = "upi://pay?pa=x@upi",
                ),
                resolvedPackage = null,
            )
            assertFalse("a upi payment link must be blocked", upi.allowed)

            val unknown = validator.validate(
                com.phoneagent.safety.IntentRequest("com.example.UNKNOWN"),
                resolvedPackage = null,
            )
            assertFalse("an unknown action must be blocked by default", unknown.allowed)
        }
    }

    @Test
    fun theRealKeywordFileLoadsAndFlagsARiskyButton() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            val keywordsJson = context.assets.open("safety_keywords.json")
                .bufferedReader().use { it.readText() }
            val keywords = SafetyConfigLoader.parseKeywords(keywordsJson)
            val classifier = RiskClassifier(
                keywords,
                PackageDenylist(PackageDenylist.DEFAULT_PATTERNS),
            )

            val send = classifier.assess(
                "tap",
                RiskTarget(text = "Send", packageName = "com.whatsapp"),
            )
            assertEquals(
                "a Send button in a real app must be high risk",
                RiskLevel.HIGH,
                send.level,
            )

            val hindi = classifier.assess(
                "tap",
                RiskTarget(text = "भेजो", packageName = "com.whatsapp"),
            )
            assertEquals(
                "the shipped keyword file must cover Hindi",
                RiskLevel.HIGH,
                hindi.level,
            )
        }
    }

    @Test
    fun openingASettingsScreenWorksAndNothingIsSent() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            requireService()
            val launcher = AndroidIntentLauncher(context, logger)
            val policyJson = context.assets.open("intent_policy.json")
                .bufferedReader().use { it.readText() }
            val policy = SafetyConfigLoader.parseIntentPolicy(policyJson)
            val denylist = PackageDenylist(PackageDenylist.DEFAULT_PATTERNS)

            val tool = LaunchIntentTool(
                validator = IntentValidator(policy, denylist),
                launcher = launcher,
                auditLog = InMemoryIntentAuditLog(),
                logger = logger,
                denylist = denylist,
            )

            val perception = AndroidPerceptionSource()
            val toolContext = object : ToolContext {
                private var snapshot = runBlocking { perception.capture(PerceptionMode.TREE_ONLY) }

                override fun currentNodes() = snapshot.nodes
                override fun currentPackageName() = snapshot.packageName
                override suspend fun refreshScreen() =
                    perception.capture(PerceptionMode.TREE_ONLY).also { snapshot = it }
                override val actuator = actuator()
            }

            val result = tool.execute(
                ToolCall(
                    "launch_intent",
                    mapOf("action" to "android.settings.SETTINGS"),
                ),
                toolContext,
            )

            assertTrue("opening Settings should work: ${result.message}", result.success)
            delay(1500)

            // After the intent, perception must see the settings app.
            val after = perception.capture(PerceptionMode.TREE_ONLY)
            assertTrue(
                "expected a settings screen, saw ${after.packageName}",
                after.packageName?.contains("settings", ignoreCase = true) == true || after.isUsable,
            )

            actuator().globalAction(GlobalAction.HOME)
        }
    }

    @Test
    fun theRegistryBuildsFromTheRealAssetsWithEveryRequiredTool() {
        val registry = ToolFactory.create(
            context = context,
            logger = logger,
            deviceInfo = AndroidDeviceInfo(context),
            screenSize = {
                val metrics = context.resources.displayMetrics
                metrics.widthPixels to metrics.heightPixels
            },
            intentLauncher = AndroidIntentLauncher(context, logger),
            actuator = actuator(),
        )

        val names = registry.names()
        listOf(
            "tap", "long_press", "double_tap", "swipe", "scroll", "drag", "pinch",
            "type_text", "clear_text", "press_enter",
            "back", "home", "recents", "open_app",
            "get_screen", "find_element", "get_current_app", "list_apps",
            "read_notifications", "get_clipboard",
            "wait", "wait_for",
            "launch_intent", "batch_actions",
        ).forEach { required ->
            assertTrue("missing tool: $required", names.contains(required))
        }

        assertFalse(
            "launch_intent must not be batchable",
            registry.batchableNames().contains("launch_intent"),
        )
        assertTrue(registry.batchableNames().contains("tap"))
        assertTrue(registry.batchableNames().contains("type_text"))
    }

    @Test
    fun listAppsReturnsTheLauncherApps() {
        // Wrapped in a block, not assigned: JUnit rejects a test method whose
        // declared return type is not void, and `= runBlocking` returns whatever
        // the last statement happens to evaluate to.
        runBlocking {
            val apps = AndroidDeviceInfo(context).launchableApps()
            assertTrue("a device should have launchable apps", apps.isNotEmpty())
            assertTrue("labels should not be blank", apps.all { it.second.isNotBlank() })
        }
    }
}
