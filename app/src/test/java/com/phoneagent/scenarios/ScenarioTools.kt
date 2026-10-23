package com.phoneagent.scenarios

import com.phoneagent.tools.BatchValidator
import com.phoneagent.tools.BatchActionsTool
import com.phoneagent.tools.InMemoryIntentAuditLog
import com.phoneagent.tools.IntentLauncher
import com.phoneagent.tools.IntentResolution
import com.phoneagent.tools.LaunchIntentTool
import com.phoneagent.tools.LaunchResult
import com.phoneagent.tools.FakeAppLauncher
import com.phoneagent.tools.FakeDeviceInfo
import com.phoneagent.tools.InfoTools
import com.phoneagent.tools.MutableRegistry
import com.phoneagent.tools.NavigationTools
import com.phoneagent.tools.TextTools
import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.tools.TouchTools
import com.phoneagent.tools.WaitTools
import com.phoneagent.safety.IntentPolicy
import com.phoneagent.safety.IntentRequest
import com.phoneagent.safety.IntentValidator
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.util.Logger

/**
 * The real tool set, assembled without an Android `Context`.
 *
 * `ToolFactory` needs a `Context` for the keyword file and the intent policy, so it cannot run
 * on the JVM. This builds the same tools from the same classes with fake device information,
 * which is what lets the batch scenarios exercise the shipped `batch_actions` rather than a
 * stand-in. Only the two things that need a real device are faked: the app list and the
 * notification source.
 */
object ScenarioTools {

    /**
     * A launcher that reports a fixed answer instead of touching a device.
     *
     * `handled` is the package that will accept every intent, so a test can drive the whole
     * policy path — allow, refuse, missing handler — without an emulator.
     */
    class ScriptedIntentLauncher(
        private val handled: String? = "com.example.handler",
    ) : IntentLauncher {
        val started = mutableListOf<IntentRequest>()

        override suspend fun resolve(request: IntentRequest): IntentResolution =
            handled?.let { IntentResolution.Handled(it) }
                ?: IntentResolution.NoHandler(request.action, request.uri)

        override suspend fun start(request: IntentRequest): LaunchResult {
            started += request
            return handled?.let { LaunchResult.Started(it) }
                ?: LaunchResult.Failed("No handler.")
        }
    }

    fun standard(
        guard: com.phoneagent.safety.SafetyGuard,
        logger: Logger,
        screenSize: Pair<Int, Int> = 1080 to 2400,
        apps: List<Pair<String, String>> = emptyList(),
        deniedPackages: List<String> = emptyList(),
        policy: IntentPolicy? = null,
        intentLauncher: IntentLauncher = ScriptedIntentLauncher(),
    ): ToolRegistry {
        val registry = MutableRegistry(logger)
        val size = { screenSize }

        val tools = buildList {
            addAll(TouchTools(size).all)
            addAll(TextTools(size).all)
            addAll(WaitTools().all)
            addAll(InfoTools(FakeDeviceInfo(apps = apps)).all)
            addAll(NavigationTools(FakeAppLauncher(apps, deniedPackages)).all)
            add(BatchActionsTool(BatchValidator(guard), guard, registry, logger))
            policy?.let {
                val deny = PackageDenylist(deniedPackages)
                add(
                    LaunchIntentTool(
                        validator = IntentValidator(it, deny),
                        launcher = intentLauncher,
                        auditLog = InMemoryIntentAuditLog(),
                        logger = logger,
                        denylist = deny,
                    ),
                )
            }
        }

        registry.setAll(tools)
        return registry
    }

    /** The shipped intent policy, read from assets so a test cannot drift from it. */
    fun shippedPolicy(): IntentPolicy = com.phoneagent.safety.SafetyConfigLoader.parseIntentPolicy(
        java.io.File("src/main/assets/intent_policy.json").readText(),
    )

    /** The names the standard set provides, for a test that wants to assert on coverage. */
    fun names(): List<String> = listOf(
        "tap", "long_press", "double_tap", "swipe", "scroll", "drag", "pinch",
        "type_text", "clear_text", "press_enter",
        "wait", "wait_for",
        "get_screen", "get_current_app", "list_apps", "find_element",
        "read_notifications", "get_clipboard",
        "open_app", "launch_intent",
        "batch_actions",
    )
}
