package com.phoneagent.tools

import android.content.Context
import com.phoneagent.safety.IntentValidator
import com.phoneagent.safety.PackageDenylist
import com.phoneagent.safety.RiskClassifier
import com.phoneagent.safety.SafetyGuard
import com.phoneagent.safety.SafetyConfigLoader
import com.phoneagent.util.Logger

/**
 * Builds the registry once, from the safety data files.
 *
 * Kept in one place so the tool list cannot drift between the prompt, the batch allow-list
 * and the guard: all three read the same registry.
 */
object ToolFactory {

    fun create(
        context: Context,
        logger: Logger,
        deviceInfo: DeviceInfo,
        screenSize: () -> Pair<Int, Int>,
        intentLauncher: IntentLauncher,
        actuator: Actuator,
        auditLog: IntentAuditLog = InMemoryIntentAuditLog(),
        denylistPatterns: List<String> = PackageDenylist.DEFAULT_PATTERNS,
    ): ToolRegistry {
        val keywords = loadKeywords(context)
        val policy = loadIntentPolicy(context)
        val denylist = PackageDenylist(denylistPatterns)

        val guard = SafetyGuard(RiskClassifier(keywords, denylist), denylist)
        val validator = IntentValidator(policy, denylist)
        val batchValidator = BatchValidator(guard)

        // The registry is needed by batch_actions to run its inner calls, so it is created
        // empty first and the tools are added afterwards.
        val registry = MutableRegistry(logger)

        val tools = buildList {
            addAll(TouchTools(screenSize).all)
            addAll(TextTools(screenSize).all)
            addAll(NavigationTools(AndroidAppLauncher(context, denylist, logger)).all)
            addAll(InfoTools(deviceInfo).all)
            addAll(WaitTools().all)
            add(
                LaunchIntentTool(
                    validator = validator,
                    launcher = intentLauncher,
                    auditLog = auditLog,
                    logger = logger,
                    denylist = denylist,
                ),
            )
            add(BatchActionsTool(batchValidator, guard, registry, logger))
        }

        registry.setAll(tools)
        return registry
    }

    private fun loadKeywords(context: Context) = runCatching {
        SafetyConfigLoader.parseKeywords(readAsset(context, "safety_keywords.json"))
    }.getOrElse {
        // Without the keyword file the agent must not act at all, so the fallback keeps the
        // most dangerous words rather than failing open.
        SafetyConfigLoader.parseKeywords(FALLBACK_KEYWORDS)
    }

    private fun loadIntentPolicy(context: Context) = runCatching {
        SafetyConfigLoader.parseIntentPolicy(readAsset(context, "intent_policy.json"))
    }.getOrElse {
        SafetyConfigLoader.parseIntentPolicy(FALLBACK_INTENT_POLICY)
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    /** Minimal deny-by-default policy, used only if the asset cannot be read. */
    private const val FALLBACK_KEYWORDS = """
        {"high":{"en":["send","pay","delete","uninstall","confirm","buy","submit","transfer"]},
         "blocked":{"en":["factory reset","erase all data"]},
         "injection":{"en":["ignore previous instructions"]}}
    """

    private const val FALLBACK_INTENT_POLICY = """
        {"allowed_low":["android.intent.action.VIEW","android.intent.action.DIAL"],
         "allowed_uri_schemes":["http","https","tel","mailto","geo"],
         "blocked_uri_schemes":["file","intent"]}
    """
}

/**
 * A registry that can be filled after construction, so `batch_actions` can hold a reference
 * to the registry that will contain it.
 */
internal class MutableRegistry(
    private val logger: Logger,
) : ToolRegistry(logger, emptyList()) {

    private var delegate: ToolRegistry? = null

    fun setAll(tools: List<Tool>) {
        delegate = ToolRegistry(logger, tools)
    }

    override fun get(name: String): Tool? = delegate?.get(name)
    override fun names(): Set<String> = delegate?.names() ?: emptySet()
    override fun batchableNames(): Set<String> = delegate?.batchableNames() ?: emptySet()
    override fun definitions(): List<Map<String, Any?>> = delegate?.definitions() ?: emptyList()
    override val all: List<Tool> get() = delegate?.all ?: emptyList()

    override suspend fun execute(
        call: ToolCall,
        context: ToolContext,
        timeoutMs: Long,
    ): ToolResult = delegate?.execute(call, context, timeoutMs)
        ?: ToolResult.fail("The tools are not ready yet.")
}
