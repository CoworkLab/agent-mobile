package com.phoneagent.eval

import com.phoneagent.agent.AgentOrchestrator
import com.phoneagent.agent.LoopLimits
import com.phoneagent.agent.StepEngine
import com.phoneagent.agent.StepOutcome
import com.phoneagent.brain.LlmProvider
import com.phoneagent.brain.OpenAiCompatibleProvider
import com.phoneagent.brain.ProviderConfig
import com.phoneagent.brain.ProviderRouter
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.safety.InjectionDetector
import com.phoneagent.safety.KeywordSets
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * One scenario, run against a real model.
 *
 * The eval harness is deliberately separate from the simulation suite. The simulation suite
 * proves the loop is correct when the model behaves; this proves the model, given the real
 * prompt and the real tool list, actually behaves on the testbed screens. A scenario that
 * passes in simulation and fails here is a prompt problem, not a loop problem.
 */
data class EvalScenario(
    val id: Int,
    val name: String,
    val goal: String,
    /** The model must end on one of these; anything else counts as a failure. */
    val expect: Set<EvalExpectation>,
    /** Steps are not scored, but a run that needs dozens is a prompt smell worth seeing. */
    val maxSteps: Int = 20,
)

enum class EvalExpectation { DONE, STOPPED, WAITING_USER, FAILED }

/** What one scenario did. */
data class EvalResult(
    val scenario: EvalScenario,
    val attempt: Int,
    val outcome: StepOutcome?,
    val steps: Int,
    val durationMs: Long,
    val error: String? = null,
) {
    val passed: Boolean
        get() = error == null && outcome != null && when (outcome) {
            is StepOutcome.Done -> EvalExpectation.DONE in scenario.expect
            is StepOutcome.Stopped -> EvalExpectation.STOPPED in scenario.expect
            is StepOutcome.WaitingUser -> EvalExpectation.WAITING_USER in scenario.expect
            is StepOutcome.Failed -> EvalExpectation.FAILED in scenario.expect
            is StepOutcome.Continue -> false
        }
}

/** The whole run, with the summary the report is written from. */
data class EvalReport(
    val model: String,
    val results: List<EvalResult>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
) {
    val passed: Int get() = results.count { it.passed }
    val total: Int get() = results.size
    val passRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total

    /** A scenario passes when any attempt passes; retries absorb a model's ordinary variance. */
    val passedScenarios: List<EvalScenario>
        get() = results.filter { it.passed }.map { it.scenario }.distinctBy { it.id }

    val failedScenarios: List<EvalScenario>
        get() = results.map { it.scenario }.distinctBy { it.id }
            .filterNot { s -> passedScenarios.any { it.id == s.id } }

    /** A Markdown row for `docs/EVAL_REPORT.md`. */
    fun markdownRow(date: String): String {
        val notes = if (failedScenarios.isEmpty()) {
            "All scenarios passed."
        } else {
            "Failed: " + failedScenarios.joinToString(", ") { "#${it.id} ${it.name}" }
        }
        return "| $date | $model | $total | $passed | " +
            "${"%.0f".format(passRate * 100)}% | $notes |"
    }
}

/**
 * Builds and runs eval scenarios.
 *
 * The orchestrator is injected rather than created here so the same runner can drive the
 * emulator (real perception, real actuator) or a JVM fake. That is what lets the report writer
 * be tested without a model or a device.
 */
class EvalRunner(
    private val orchestratorFactory: (EvalScenario) -> AgentOrchestrator,
    private val logger: Logger,
    private val maxAttempts: Int = 2,
) {

    suspend fun run(scenarios: List<EvalScenario>, modelName: String): EvalReport {
        val started = System.currentTimeMillis()
        val results = mutableListOf<EvalResult>()

        for (scenario in scenarios) {
            var passed = false
            for (attempt in 1..maxAttempts) {
                val result = runOnce(scenario, attempt)
                results += result
                if (result.passed) {
                    passed = true
                    break
                }
                logger.log(
                    LogLevel.WARN,
                    TAG,
                    "Scenario #${scenario.id} attempt $attempt did not pass; retrying.",
                )
            }
            if (!passed) {
                logger.log(LogLevel.ERROR, TAG, "Scenario #${scenario.id} failed all attempts.")
            }
        }

        return EvalReport(
            model = modelName,
            results = results,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun runOnce(scenario: EvalScenario, attempt: Int): EvalResult {
        val started = System.currentTimeMillis()
        var steps = 0
        return try {
            val orchestrator = orchestratorFactory(scenario)
            var outcome: StepOutcome = orchestrator.prepare(scenario.goal)
            while (outcome is StepOutcome.Continue && steps < scenario.maxSteps) {
                outcome = orchestrator.step(scenario.goal, steps)
                steps++
            }
            EvalResult(
                scenario = scenario,
                attempt = attempt,
                outcome = outcome,
                steps = steps,
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (error: Throwable) {
            EvalResult(
                scenario = scenario,
                attempt = attempt,
                outcome = null,
                steps = steps,
                durationMs = System.currentTimeMillis() - started,
                error = "${error::class.simpleName}: ${error.message}",
            )
        }
    }

    companion object {
        private const val TAG = "EvalRunner"
    }
}

/** Builds the real loop for one eval scenario, over real perception and a real model. */
object EvalAssembly {

    /**
     * The provider the eval run uses.
     *
     * The key comes from the environment so it is never committed; the eval workflow passes it
     * from a repository secret. A free tier is used on purpose: the eval suite is small and
     * only ever sends synthetic testbed screens, never a person's own.
     */
    fun providerConfig(
        apiKey: String,
        model: String = "gemini-2.0-flash",
        baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai",
    ): ProviderConfig = ProviderConfig(
        id = "eval",
        name = "Gemini (eval)",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        supportsVision = true,
        supportsTools = true,
    )

    fun provider(config: ProviderConfig, logger: Logger): LlmProvider =
        OpenAiCompatibleProvider(config, logger)

    fun router(config: ProviderConfig, logger: Logger): ProviderRouter =
        ProviderRouter(listOf(provider(config, logger)), logger)

    fun guard(denylistPatterns: List<String> = PackageDenylist.DEFAULT_PATTERNS): SafetyGuard {
        val denylist = PackageDenylist(denylistPatterns)
        return SafetyGuard(RiskClassifier(KeywordSets.EMPTY, denylist), denylist)
    }

    fun orchestrator(
        perception: PerceptionSource,
        registry: ToolRegistry,
        router: ProviderRouter,
        guard: SafetyGuard,
        gate: com.phoneagent.agent.ConfirmationGate,
        systemPrompt: String,
        logger: Logger,
        skills: com.phoneagent.memory.SkillLibrary = com.phoneagent.memory.SkillLibrary(),
        maxSteps: Int = 20,
    ): AgentOrchestrator = AgentOrchestrator(
        perception = perception,
        router = router,
        registry = registry,
        engine = StepEngine(registry, guard, gate),
        gate = gate,
        logger = logger,
        systemPrompt = systemPrompt,
        limits = LoopLimits(maxSteps = maxSteps),
        injectionDetector = InjectionDetector(KeywordSets.EMPTY),
        skills = skills,
        perceptionMode = { PerceptionMode.TREE_ONLY },
    )

    /** A registry of the given tools. */
    fun registry(logger: Logger, tools: List<Tool>): ToolRegistry = ToolRegistry(logger, tools)

    fun snapshotIsUsable(snapshot: ScreenSnapshot): Boolean = snapshot.isUsable
}
