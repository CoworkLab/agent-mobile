package com.phoneagent.eval

import com.phoneagent.agent.AgentOrchestrator
import com.phoneagent.agent.ConfirmationGate
import com.phoneagent.agent.LoopLimits
import com.phoneagent.agent.StepEngine
import com.phoneagent.agent.StepOutcome
import com.phoneagent.brain.LlmProvider
import com.phoneagent.brain.LlmRequest
import com.phoneagent.brain.LlmResponse
import com.phoneagent.brain.ProviderConfig
import com.phoneagent.brain.ProviderRouter
import com.phoneagent.brain.ToolCallRequest
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.safety.InjectionDetector
import com.phoneagent.safety.KeywordSets
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eval harness itself, without a model or a device.
 *
 * The harness is what turns a model's behaviour into a pass rate, so it has to be right in the
 * same way the loop does. These tests drive it with a scripted provider: the interesting cases
 * are a run that finishes, a run that ends the wrong way, a run that throws, and the retry
 * rule, because each is a different shape in the report.
 */
class EvalHarnessTest {

    private fun logger() = Logger().apply { minLevel = LogLevel.WARN }

    private fun snapshot(hash: String = "h") = ScreenSnapshot(
        packageName = "com.phoneagent.testbed",
        windowTitle = null,
        timestampMs = 0,
        nodes = emptyList(),
        screenHash = hash,
    )

    private class ScriptedPerception : PerceptionSource {
        override suspend fun capture(mode: PerceptionMode): ScreenSnapshot = ScreenSnapshot(
            packageName = "com.phoneagent.testbed",
            windowTitle = null,
            timestampMs = 0,
            nodes = emptyList(),
            screenHash = "h",
        )

        override fun isAvailable(): Boolean = true
        override fun screenshotCooldownMs(): Long = 0
    }

    /** A provider that replays a fixed list of replies, repeating the last one forever. */
    private class ScriptedProvider(private val replies: List<LlmResponse>) : LlmProvider {
        override val config: ProviderConfig = ProviderConfig(
            id = "script",
            name = "Script",
            baseUrl = "http://localhost",
            apiKey = "x",
            model = "m",
        )

        override suspend fun chat(request: LlmRequest): LlmResponse =
            replies.first()
    }

    /** Replays one reply per call, in order, then repeats the last. */
    private class ReplayProvider(private val replies: List<LlmResponse>) : LlmProvider {
        private var index = 0
        override val config: ProviderConfig = ProviderConfig(
            id = "replay",
            name = "Replay",
            baseUrl = "http://localhost",
            apiKey = "x",
            model = "m",
        )

        override suspend fun chat(request: LlmRequest): LlmResponse {
            val reply = replies.getOrElse(index) { replies.last() }
            index++
            return reply
        }
    }

    private fun toolReply(name: String, args: String = "{}") = LlmResponse(
        text = null,
        toolCalls = listOf(ToolCallRequest(name = name, argumentsJson = args)),
    )

    private fun textReply(text: String = "ok") = LlmResponse(text = text)

    private class DenyGate : ConfirmationGate {
        override fun request(call: ToolCall, reason: String) = Unit
        override fun pending(): ToolCall? = null
        override fun answer(approved: Boolean): ToolCall? = null
        override fun contextFor(snapshot: ScreenSnapshot): ToolContext = object : ToolContext {
            override val actuator: com.phoneagent.tools.Actuator =
                com.phoneagent.tools.FakeActuator()
            override fun currentNodes() = emptyList<com.phoneagent.perception.UiNode>()
            override fun currentPackageName(): String? = null
            override suspend fun refreshScreen(): ScreenSnapshot = snapshot
        }
    }

    private fun orchestrator(provider: LlmProvider): AgentOrchestrator {
        val gate = DenyGate()
        val registry = ToolRegistry(logger(), emptyList())
        val denylist = PackageDenylist(emptyList())
        return AgentOrchestrator(
            perception = ScriptedPerception(),
            router = ProviderRouter(listOf(provider), logger()),
            registry = registry,
            engine = StepEngine(
                registry,
                SafetyGuard(RiskClassifier(KeywordSets.EMPTY, denylist), denylist),
                gate,
            ),
            gate = gate,
            logger = logger(),
            systemPrompt = "You are PhoneAgent.",
            limits = LoopLimits(maxSteps = 5),
            injectionDetector = InjectionDetector(KeywordSets.EMPTY),
            perceptionMode = { PerceptionMode.TREE_ONLY },
        )
    }

    private fun scenario(
        id: Int = 1,
        expect: Set<EvalExpectation> = setOf(EvalExpectation.DONE),
    ) = EvalScenario(id = id, name = "s$id", goal = "do it", expect = expect, maxSteps = 5)

    @Test
    fun `a run that finishes counts as a pass`() = runTest {
        val provider = ReplayProvider(
            listOf(textReply("plan"), toolReply("finish", """{"summary":"done"}""")),
        )
        val runner = EvalRunner(orchestratorFactory = { orchestrator(provider) }, logger = logger())

        val report = runner.run(listOf(scenario()), "test-model")

        assertEquals(1, report.passed)
        assertEquals(1, report.total)
        assertTrue(report.failedScenarios.isEmpty())
    }

    @Test
    fun `a run that ends the wrong way is a failure`() = runTest {
        val provider = ReplayProvider(
            listOf(textReply("plan"), toolReply("fail", """{"reason":"no network"}""")),
        )
        val runner = EvalRunner(orchestratorFactory = { orchestrator(provider) }, logger = logger())

        val report = runner.run(listOf(scenario(expect = setOf(EvalExpectation.DONE))), "test-model")

        assertEquals(0, report.passed)
        assertEquals(1, report.failedScenarios.size)
        assertEquals(1, report.failedScenarios.first().id)
    }

    @Test
    fun `a pausing run matches the waiting-user expectation`() = runTest {
        val provider = ReplayProvider(
            listOf(textReply("plan"), toolReply("ask_user", """{"question":"Which account?"}""")),
        )
        val runner = EvalRunner(orchestratorFactory = { orchestrator(provider) }, logger = logger())

        val report = runner.run(
            listOf(scenario(expect = setOf(EvalExpectation.WAITING_USER))),
            "test-model",
        )

        assertEquals(1, report.passed)
    }

    @Test
    fun `a throwing orchestrator is recorded as an error, not a crash`() = runTest {
        val runner = EvalRunner(
            orchestratorFactory = { throw IllegalStateException("boom") },
            logger = logger(),
        )

        val report = runner.run(listOf(scenario()), "test-model")

        assertEquals(0, report.passed)
        assertTrue(report.results.first().error!!.contains("boom"))
    }

    @Test
    fun `a failing scenario is retried and a later pass counts`() = runTest {
        var attempt = 0
        val runner = EvalRunner(
            orchestratorFactory = {
                attempt++
                if (attempt == 1) throw IllegalStateException("transient")
                orchestrator(
                    ReplayProvider(
                        listOf(textReply("plan"), toolReply("finish", """{"summary":"done"}""")),
                    ),
                )
            },
            logger = logger(),
            maxAttempts = 2,
        )

        val report = runner.run(listOf(scenario()), "test-model")

        assertEquals(1, report.passed)
        assertTrue("the retry should be visible in the results", report.total >= 2)
    }

    @Test
    fun `the markdown row names the failures and not the passes`() {
        val failed = EvalResult(
            scenario = EvalScenario(7, "broken thing", "goal", setOf(EvalExpectation.DONE)),
            attempt = 1,
            outcome = StepOutcome.Failed("nope"),
            steps = 3,
            durationMs = 10,
        )
        val passed = EvalResult(
            scenario = EvalScenario(1, "good thing", "goal", setOf(EvalExpectation.DONE)),
            attempt = 1,
            outcome = StepOutcome.Done("ok"),
            steps = 2,
            durationMs = 10,
        )
        val report = EvalReport("m", listOf(passed, failed), 0, 0)

        val row = report.markdownRow("2026-01-01")

        assertTrue(row.contains("| 2026-01-01 | m | 2 | 1 | 50% |"))
        assertTrue(row.contains("#7 broken thing"))
        assertFalse(row.contains("#1 good thing"))
    }

    @Test
    fun `the eval scenarios are well formed`() {
        // A scenario whose expectation can never be met is a silent always-fail, and the point
        // of a small suite is that a failure means something.
        assertTrue(EvalScenarios.all.isNotEmpty())
        EvalScenarios.all.forEach { s ->
            assertTrue("scenario ${s.id} expects nothing", s.expect.isNotEmpty())
            assertTrue("scenario ${s.id} has no goal", s.goal.isNotBlank())
            assertTrue("scenario ${s.id} has no steps", s.maxSteps > 0)
        }
        assertEquals(
            "scenario ids must be unique",
            EvalScenarios.all.size,
            EvalScenarios.all.map { it.id }.distinct().size,
        )
    }
}
