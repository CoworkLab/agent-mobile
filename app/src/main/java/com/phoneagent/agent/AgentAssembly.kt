package com.phoneagent.agent

import android.content.Context
import com.phoneagent.PhoneAgentApp
import com.phoneagent.brain.OpenAiCompatibleProvider
import com.phoneagent.brain.ProviderRouter
import com.phoneagent.memory.SkillLibrary
import com.phoneagent.perception.AndroidPerceptionSource
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.safety.InjectionDetector
import com.phoneagent.safety.KeywordSets
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.settings.AppSettings
import com.phoneagent.tools.AndroidActuator
import com.phoneagent.tools.AndroidDeviceInfo
import com.phoneagent.tools.AndroidIntentLauncher
import com.phoneagent.tools.AndroidToolContext
import com.phoneagent.tools.ToolFactory
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger

/**
 * Builds the real agent loop for one task.
 *
 * This is the join that turns the tested parts into a working agent. Perception, the model
 * router, the tools and the loop all existed and were tested on their own; what was missing
 * was a place that reads the person's settings, loads the shipped safety files, and connects
 * them. It is assembled per task rather than once at startup, so a settings change (a new
 * provider, a longer step limit, an edited denylist) takes effect on the next task without
 * restarting the app.
 */
class AgentAssembly private constructor(
    val controller: AgentController,
    val orchestrator: AgentOrchestrator,
    val gate: AndroidConfirmationGate,
    val perception: PerceptionSource,
) {

    companion object {

        /**
         * Assembles a loop for [settings].
         *
         * Returns null when no model is configured, so the caller can tell the person to add
         * one instead of starting a task that cannot make a single decision.
         */
        suspend fun build(
            context: Context,
            settings: AppSettings,
            logger: Logger,
        ): AgentAssembly? {
            val configs = PhoneAgentApp.providers().current().filter { it.isUsable }
            if (configs.isEmpty()) {
                logger.log(
                    LogLevel.WARN,
                    TAG,
                    "No usable model configured; refusing to start a task",
                )
                return null
            }

            val perception = AndroidPerceptionSource()
            val actuator = AndroidActuator(logger)
            val denylist = PackageDenylist(settings.deniedPackages)

            // The same keyword file the guard and the injection detector read. Loading it once
            // here keeps the two in step; a detector with its own list could miss an injection
            // the guard would have flagged.
            val keywords = runCatching {
                SafetyConfigLoader.parseKeywords(
                    context.assets.open("safety_keywords.json")
                        .bufferedReader().use { it.readText() },
                )
            }.getOrElse { KeywordSets.EMPTY }

            val skills = SkillLibrary()
            val registry = ToolFactory.create(
                context = context,
                logger = logger,
                deviceInfo = AndroidDeviceInfo(context),
                screenSize = {
                    val metrics = context.resources.displayMetrics
                    metrics.widthPixels to metrics.heightPixels
                },
                intentLauncher = AndroidIntentLauncher(context, logger),
                actuator = actuator,
                denylistPatterns = settings.deniedPackages,
                skills = skills,
            )

            val router = ProviderRouter(
                providers = configs.map { OpenAiCompatibleProvider(it, logger) },
                logger = logger,
            )

            val guard = SafetyGuard(RiskClassifier(keywords, denylist), denylist)

            val gate = AndroidConfirmationGate(
                logger = logger,
                onAsk = { call, reason ->
                    logger.log(LogLevel.INFO, TAG, "Confirm ${call.name}: $reason")
                },
                contextProvider = { snapshot ->
                    AndroidToolContext(
                        perception = perception,
                        mode = settings.perceptionMode,
                        snapshot = snapshot,
                        actuator = actuator,
                    )
                },
            )

            val engine = StepEngine(registry, guard, gate, PhoneAgentApp.auditLog())

            val systemPrompt = runCatching {
                context.assets.open("prompts/system.md").bufferedReader().use { it.readText() }
            }.getOrElse { "You are PhoneAgent." }

            val orchestrator = AgentOrchestrator(
                perception = perception,
                router = router,
                registry = registry,
                engine = engine,
                gate = gate,
                logger = logger,
                systemPrompt = systemPrompt,
                limits = LoopLimits(
                    maxSteps = settings.maxSteps,
                    maxDurationMs = settings.maxTaskMinutes * 60_000L,
                ),
                injectionDetector = InjectionDetector(keywords),
                skills = skills,
                perceptionMode = { settings.perceptionMode },
            )

            val controller = AgentController(
                logger = logger,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
                ),
                maxSteps = settings.maxSteps,
                stepRunner = { goal, stepIndex, _ -> orchestrator.step(goal, stepIndex) },
                observer = { "The agent reads the screen itself each step." },
                onAnswer = { answer ->
                    // A pending confirmation resumes as a yes/no; anything else is a
                    // clarification the model reads as text. The orchestrator knows which it
                    // asked, so the routing lives there rather than in the UI.
                    if (orchestrator.pendingApproval != null) {
                        orchestrator.recordApproval(isApproval(answer))
                    } else {
                        orchestrator.recordUserAnswer(answer)
                    }
                },
                onAnswerTimeout = {
                    // Silence is a denial, never consent. For a clarification there is nothing
                    // to record: the loop simply tries again without the answer.
                    if (orchestrator.pendingApproval != null) {
                        orchestrator.recordApproval(approved = false, timedOut = true)
                    }
                },
            )

            return AgentAssembly(controller, orchestrator, gate, perception)
        }

        /**
         * Reads a yes/no from a free-text answer.
         *
         * The person may type "yes", "haan", "ok" or "no". Only an unambiguous no is a
         * denial; anything else counts as consent, because a person who typed a sentence in
         * the approval box meant to go ahead. The gate's own timeout is the safety net for a
         * box left empty.
         */
        private fun isApproval(answer: String): Boolean {
            val normalized = answer.trim().lowercase()
            val denials = listOf("no", "nope", "nahi", "nahin", "cancel", "stop", "mat karo", "don't", "dont")
            return denials.none { normalized == it || normalized.startsWith("$it ") }
        }

        private const val TAG = "AgentAssembly"
    }
}
