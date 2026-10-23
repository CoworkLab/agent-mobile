package com.phoneagent.eval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.agent.ConfirmationGate
import com.phoneagent.perception.AndroidPerceptionSource
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.service.PhoneAgentAccessibilityService
import com.phoneagent.tools.AndroidActuator
import com.phoneagent.tools.AndroidDeviceInfo
import com.phoneagent.tools.AndroidIntentLauncher
import com.phoneagent.tools.AndroidToolContext
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolFactory
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The LLM-in-the-loop evaluation.
 *
 * It needs three things a normal CI run does not have: an emulator with the testbed installed,
 * the accessibility service enabled, and a model API key. When any of them is missing the test
 * skips rather than fails, so a fork without the secret does not show a red build.
 *
 * The report is written to the app's external files directory, which the eval workflow pulls
 * and appends to `docs/EVAL_REPORT.md`. Writing it from the device keeps the workflow simple:
 * one artifact, already formatted, rather than parsing test output.
 */
@RunWith(AndroidJUnit4::class)
class EvalInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun logger() = Logger().apply { minLevel = LogLevel.DEBUG }

    private fun apiKey(): String? =
        InstrumentationRegistry.getArguments().getString("evalApiKey")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("EVAL_API_KEY")?.takeIf { it.isNotBlank() }

    private fun modelName(): String =
        InstrumentationRegistry.getArguments().getString("evalModel") ?: "gemini-2.0-flash"

    /**
     * A gate that records that it was asked and then denies.
     *
     * The eval must not sit for the real five-minute window on every risky action. What is
     * being measured is whether the loop paused at all, so the question is captured and the
     * answer is an immediate no.
     */
    private class DenyingGate(private val perception: AndroidPerceptionSource) : ConfirmationGate {
        @Volatile
        var asked: ToolCall? = null

        override fun request(call: ToolCall, reason: String) {
            asked = call
        }

        override fun pending(): ToolCall? = asked

        override fun answer(approved: Boolean): ToolCall? {
            val call = asked
            asked = null
            return call
        }

        override fun contextFor(snapshot: ScreenSnapshot): ToolContext = AndroidToolContext(
            perception = perception,
            mode = PerceptionMode.TREE_ONLY,
            snapshot = snapshot,
            actuator = AndroidActuator(Logger()),
        )
    }

    @Test
    fun `the eval suite runs against the real model`() = runBlocking {
        assumeTrue(
            "Accessibility service not enabled; the eval needs a real screen",
            PhoneAgentAccessibilityService.get() != null && PhoneAgentAccessibilityService.isRunning(),
        )
        val key = apiKey()
        assumeTrue("EVAL_API_KEY is not set; skipping the LLM evaluation", key != null)

        val log = logger()
        val perception = AndroidPerceptionSource()
        val actuator = AndroidActuator(log)
        val config = EvalAssembly.providerConfig(apiKey = key!!, model = modelName())
        val router = EvalAssembly.router(config, log)
        val guard = EvalAssembly.guard()
        val systemPrompt = context.assets.open("prompts/system.md")
            .bufferedReader().use { it.readText() }

        val runner = EvalRunner(
            orchestratorFactory = { scenario ->
                val registry = ToolFactory.create(
                    context = context,
                    logger = log,
                    deviceInfo = AndroidDeviceInfo(context),
                    screenSize = {
                        val m = context.resources.displayMetrics
                        m.widthPixels to m.heightPixels
                    },
                    intentLauncher = AndroidIntentLauncher(context, log),
                    actuator = actuator,
                )
                EvalAssembly.orchestrator(
                    perception = perception,
                    registry = registry,
                    router = router,
                    guard = guard,
                    gate = DenyingGate(perception),
                    systemPrompt = systemPrompt,
                    logger = log,
                    maxSteps = scenario.maxSteps,
                )
            },
            logger = log,
            maxAttempts = 2,
        )

        val report = runner.run(EvalScenarios.all, config.model)

        val file = File(context.getExternalFilesDir(null), "eval-report.md")
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("<!-- eval row -->")
                appendLine(report.markdownRow(java.time.LocalDate.now().toString()))
                appendLine()
                appendLine("### Detail")
                report.results.forEach { result ->
                    appendLine(
                        "- #${result.scenario.id} ${result.scenario.name}: attempt " +
                            "${result.attempt}, ${result.steps} steps, " +
                            "${result.durationMs}ms, " +
                            (result.error ?: result.outcome?.let { it::class.simpleName }),
                    )
                }
            },
        )
        log.log(LogLevel.INFO, "Eval", "Report written to ${file.absolutePath}")

        // The test does not fail on a low pass rate. The report is the deliverable, and a hard
        // gate here would make a model's ordinary variance look like a code regression. The
        // workflow decides what to do with the number.
    }
}
