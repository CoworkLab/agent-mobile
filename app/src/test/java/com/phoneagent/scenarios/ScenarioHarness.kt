package com.phoneagent.scenarios

import com.phoneagent.agent.AgentOrchestrator
import com.phoneagent.agent.ConfirmationGate
import com.phoneagent.agent.LoopLimits
import com.phoneagent.agent.StepEngine
import com.phoneagent.brain.LlmError
import com.phoneagent.brain.LlmProvider
import com.phoneagent.brain.LlmRequest
import com.phoneagent.brain.LlmResponse
import com.phoneagent.brain.ProviderConfig
import com.phoneagent.brain.ProviderRouter
import com.phoneagent.brain.ToolCallRequest
import com.phoneagent.memory.SkillLibrary
import com.phoneagent.perception.Bounds
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.perception.PerceptionSource
import com.phoneagent.perception.ScreenSnapshot
import com.phoneagent.perception.SnapshotFailure
import com.phoneagent.perception.UiNode
import com.phoneagent.safety.InjectionDetector
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.tools.Actuator
import com.phoneagent.tools.FakeActuator
import com.phoneagent.tools.Tool
import com.phoneagent.tools.ToolCall
import com.phoneagent.tools.ToolContext
import com.phoneagent.tools.ToolRegistry
import com.phoneagent.util.Logger

/**
 * The shared harness for the required scenarios.
 *
 * The brief asks for 23 scenarios verified on an emulator. A full emulator matrix takes hours,
 * so everything that is a decision rather than a rendering is driven here first: a scripted
 * model, a scripted screen and the real step engine. The emulator suite then only has to prove
 * that the same paths survive contact with a real device, on a few representative cases.
 *
 * What is NOT faked: the orchestrator, the step engine, the safety guard, the tool registry and
 * the audit log are the shipped ones. Only the two things outside the app are scripted, which
 * is the model and the screen. A scenario that passes here and fails on a device is a real
 * finding about the device, not about the harness.
 */
class ScenarioHarness(
    tools: List<Tool> = emptyList(),
    deniedPackages: List<String> = emptyList(),
    limits: LoopLimits = LoopLimits.DEFAULT,
    skills: SkillLibrary = SkillLibrary(),
    /**
     * A prebuilt registry, for a scenario that needs the shipped tools wired to each other.
     *
     * `batch_actions` holds a reference to the registry that contains it, so it cannot be built
     * from a plain tool list. Passing the registry in lets those scenarios use the real thing.
     */
    suppliedRegistry: ToolRegistry? = null,
) {

    val logger = Logger()
    val perception = ScriptedPerception()
    val model = ScriptedModel()
    val actuator = FakeActuator()

    val gate = RecordingGate(ScriptedToolContext(perception, actuator))

    /** The shipped detector, so a scenario can assert on what it flags. */
    val injectionDetector = InjectionDetector(
        SafetyConfigLoader.parseKeywords(
            java.io.File("src/main/assets/safety_keywords.json").readText(),
        ),
    )
    val registry = suppliedRegistry ?: ToolRegistry(logger, tools)
    val guard: SafetyGuard
    val engine: StepEngine
    val orchestrator: AgentOrchestrator

    init {
        // The shipped keyword file, so a scenario cannot pass against rules the app does not
        // actually ship. A test that used its own keywords would prove nothing.
        val keywords = java.io.File("src/main/assets/safety_keywords.json").readText()
        val denylist = PackageDenylist(deniedPackages)
        guard = SafetyGuard(
            RiskClassifier(SafetyConfigLoader.parseKeywords(keywords), denylist),
            denylist,
        )
        engine = StepEngine(registry, guard, gate)
        orchestrator = AgentOrchestrator(
            perception = perception,
            router = ProviderRouter(listOf(model), logger),
            registry = registry,
            engine = engine,
            gate = gate,
            logger = logger,
            systemPrompt = "You are PhoneAgent.",
            limits = limits,
            injectionDetector = injectionDetector,
            skills = skills,
        )
    }

    /** Sets the screen the next observation will see. */
    fun screen(
        hash: String,
        packageName: String?,
        nodes: List<UiNode> = listOf(button("Search")),
        failure: SnapshotFailure? = null,
    ) = apply {
        perception.snapshot = snapshotOf(hash, packageName, nodes, failure)
    }

    companion object {
        fun snapshotOf(
            hash: String,
            packageName: String?,
            nodes: List<UiNode>,
            failure: SnapshotFailure? = null,
            jpeg: ByteArray? = null,
        ) = ScreenSnapshot(
            packageName = packageName,
            windowTitle = null,
            timestampMs = 0,
            nodes = nodes,
            screenHash = hash,
            screenshotJpeg = jpeg,
            mode = PerceptionMode.TREE_ONLY,
            failure = failure,
        )

        fun button(text: String, mark: Int = 1, id: String? = null) = UiNode(
            nodeId = mark,
            parentId = null,
            depth = 1,
            className = "android.widget.Button",
            text = text,
            contentDescription = null,
            viewId = id,
            bounds = Bounds(0, mark * 100, 500, mark * 100 + 80),
            clickable = true,
            mark = mark,
        )

        fun field(hint: String, mark: Int = 1, text: String? = null) = UiNode(
            nodeId = mark,
            parentId = null,
            depth = 1,
            className = "android.widget.EditText",
            text = text ?: hint,
            contentDescription = null,
            viewId = null,
            bounds = Bounds(0, mark * 100, 500, mark * 100 + 80),
            editable = true,
            clickable = true,
            mark = mark,
        )

        fun text(value: String, mark: Int = 1) = UiNode(
            nodeId = mark,
            parentId = null,
            depth = 1,
            className = "android.widget.TextView",
            text = value,
            contentDescription = null,
            viewId = null,
            bounds = Bounds(0, mark * 100, 500, mark * 100 + 80),
            mark = mark,
        )
    }
}

/**
 * The tool context a scripted run sees.
 *
 * A named class rather than an anonymous object: an anonymous object built during construction
 * cannot reference the fields it needs, and a lazy delegate only moves the problem.
 */
class ScriptedToolContext(
    private val perception: ScriptedPerception,
    override val actuator: Actuator,
) : ToolContext {
    override fun currentNodes(): List<UiNode> = perception.snapshot.nodes
    override fun currentPackageName(): String? = perception.snapshot.packageName

    /**
     * Goes through [ScriptedPerception.capture] rather than reading the field.
     *
     * A tool that refreshes the screen — the batch validator does, twice per action — must see
     * a screen the scenario swapped in. Reading the field directly would quietly ignore the
     * swap, so a test of "the screen changed under the batch" could never fail.
     */
    override suspend fun refreshScreen(): ScreenSnapshot =
        perception.capture(PerceptionMode.TREE_ONLY)
}

/** A screen whose content the scenario decides. */
class ScriptedPerception : PerceptionSource {
    // A readable default. An empty snapshot is "unusable" by design, so a test that forgets to
    // set a screen would otherwise have every step return early and assert nothing.
    var snapshot: ScreenSnapshot = ScenarioHarness.snapshotOf(
        "start", "com.example.app", listOf(ScenarioHarness.button("Search")),
    )

    /** How many captures fail with a blank screen before the real one is returned. */
    var failures = 0

    /**
     * A screen to switch to after the next successful capture.
     *
     * This is how a mid-batch layout change is modelled: the batch re-reads the screen between
     * actions, so swapping it here is exactly what a popup appearing would look like.
     */
    var next: ScreenSnapshot? = null

    override suspend fun capture(mode: PerceptionMode): ScreenSnapshot {
        if (failures > 0) {
            failures--
            return ScenarioHarness.snapshotOf(
                "failed", null, emptyList(), SnapshotFailure.BLANK_CAPTURE,
            )
        }
        next?.let {
            next = null
            snapshot = it
        }
        return snapshot
    }

    override fun isAvailable(): Boolean = true

    override fun screenshotCooldownMs(): Long = 0
}

/** A model that replays what the scenario tells it to, then repeats the last reply. */
class ScriptedModel(
    private val replies: MutableList<() -> LlmResponse> = mutableListOf(),
) : LlmProvider {

    override val config = ProviderConfig(
        id = "script", name = "Script",
        baseUrl = "https://script/v1", apiKey = "k", model = "m",
    )

    val requests = mutableListOf<LlmRequest>()

    fun willReply(response: LlmResponse) = apply { replies += { response } }

    fun willFail(error: LlmError) = apply { replies += { throw error } }

    fun willReplyWithTool(name: String, args: String = "{}", text: String? = null) = apply {
        replies += {
            LlmResponse(
                text = text,
                toolCalls = listOf(ToolCallRequest(name = name, argumentsJson = args)),
                providerName = "Script",
            )
        }
    }

    /** A reply that calls several tools at once, which is how a batch arrives. */
    fun willReplyWithTools(vararg calls: Pair<String, String>) = apply {
        replies += {
            LlmResponse(
                text = null,
                toolCalls = calls.map { (name, args) ->
                    ToolCallRequest(name = name, argumentsJson = args)
                },
                providerName = "Script",
            )
        }
    }

    override suspend fun chat(request: LlmRequest): LlmResponse {
        requests += request

        // The orchestrator asks for a plan before the first step, and that is a real model
        // call. Answering it from the queue would shift every scripted reply by one, so it is
        // answered here and the queue is left for the actions the scenario actually cares about.
        if (request.messages.lastOrNull()?.text?.contains("Write a short plan") == true) {
            return LlmResponse(text = "1. Look at the screen\n2. Do the thing\n3. Check it worked")
        }

        val reply = if (replies.size > 1) replies.removeAt(0) else replies.firstOrNull()
        return reply?.invoke() ?: LlmResponse(text = "{}")
    }
}

/** A gate that records requests and answers as the scenario says. */
class RecordingGate(private val toolContext: ToolContext) : ConfirmationGate {
    data class Request(val call: ToolCall, val reason: String)

    val requests = mutableListOf<Request>()
    private var pending: ToolCall? = null

    override fun request(call: ToolCall, reason: String) {
        pending = call
        requests += Request(call, reason)
    }

    override fun pending(): ToolCall? = pending

    override fun answer(approved: Boolean): ToolCall? = pending.also { pending = null }

    override fun contextFor(snapshot: ScreenSnapshot): ToolContext = toolContext

    /** True when the agent is waiting on a person right now. */
    fun isWaiting(): Boolean = pending != null

    fun approve() {
        pending = null
    }

    fun deny() {
        pending = null
    }
}
