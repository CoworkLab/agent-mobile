package com.phoneagent.tools

/**
 * Navigation and information tools.
 *
 * The navigation tools are deliberately *not* batchable: `back`, `home` and `recents` change
 * the whole screen, so a batch that contains one cannot predict its own later steps.
 */
class NavigationTools(
    private val appLauncher: AppLauncher,
) {

    val all: List<Tool> = listOf(
        GlobalActionTool(GlobalAction.BACK, "back", "Go back to the previous screen."),
        GlobalActionTool(GlobalAction.HOME, "home", "Go to the home screen."),
        GlobalActionTool(GlobalAction.RECENTS, "recents", "Open the recent apps list."),
        GlobalActionTool(
            GlobalAction.NOTIFICATIONS,
            "open_notifications",
            "Pull down the notification shade.",
        ),
        GlobalActionTool(
            GlobalAction.QUICK_SETTINGS,
            "open_quick_settings",
            "Pull down the quick settings tiles.",
        ),
        OpenAppTool(appLauncher),
    )

    private class GlobalActionTool(
        private val action: GlobalAction,
        override val name: String,
        override val description: String,
    ) : Tool {
        override val parametersSchema = schemaOf(emptyMap())

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val ok = context.actuator.globalAction(action)
            return if (ok) ToolResult.ok("Did ${name}") else ToolResult.fail("${name} failed.")
        }
    }

    private class OpenAppTool(private val launcher: AppLauncher) : Tool {
        override val name = "open_app"
        override val description =
            "Open an installed app by name. The name is matched loosely, so " +
                "\"whatsapp\" and \"WhatsApp\" both work."

        override val parametersSchema = schemaOf(
            mapOf("name" to stringProperty("App name as a person would say it")),
            required = listOf("name"),
        )

        override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult {
            val name = call.string("name")?.trim()
                ?: return ToolResult.fail("name is required.")

            return when (val result = launcher.launch(name)) {
                is AppLauncher.Result.Launched ->
                    ToolResult.ok("Opened ${result.label}", screenChanged = true)

                is AppLauncher.Result.NotFound ->
                    ToolResult.fail(
                        "\"$name\" is not installed. " +
                            "Use list_apps to see what is available, or do the task in the browser.",
                    )

                is AppLauncher.Result.Ambiguous ->
                    ToolResult.fail(
                        "More than one app matches \"$name\": " +
                            result.candidates.joinToString(", ") + ". Say which one.",
                    )

                is AppLauncher.Result.Denied ->
                    ToolResult.fail(
                        "PhoneAgent does not open ${result.packageName}; it is on the restricted list.",
                    )

                is AppLauncher.Result.Failed ->
                    ToolResult.fail("Could not open \"$name\": ${result.reason}")
            }
        }
    }
}

/** Opens apps by fuzzy label match. The Android implementation queries `PackageManager`. */
interface AppLauncher {
    suspend fun launch(name: String): Result

    sealed interface Result {
        data class Launched(val packageName: String, val label: String) : Result
        data class NotFound(val name: String) : Result
        data class Ambiguous(val name: String, val candidates: List<String>) : Result
        data class Denied(val packageName: String) : Result
        data class Failed(val reason: String) : Result
    }
}

/**
 * Fuzzy app-name matching, kept pure so it can be tested without a device.
 *
 * Matching is deliberately forgiving in one direction only: it accepts a query that is a
 * prefix, an abbreviation, or a case-insensitive substring of a label, but it never guesses
 * between two equally good candidates. Guessing which app to open is worse than asking.
 */
object AppNameMatcher {

    fun match(query: String, labels: List<Pair<String, String>>): AppLauncher.Result {
        val needle = query.trim().lowercase().replace(" ", "")
        if (needle.isEmpty()) return AppLauncher.Result.NotFound(query)

        // Exact match wins outright, even if other labels also contain the query.
        labels.firstOrNull { (_, label) -> label.lowercase().replace(" ", "") == needle }
            ?.let { (pkg, label) -> return AppLauncher.Result.Launched(pkg, label) }

        val candidates = labels.filter { (_, label) ->
            val normalized = label.lowercase().replace(" ", "")
            normalized.startsWith(needle) || normalized.contains(needle)
        }

        return when (candidates.size) {
            0 -> AppLauncher.Result.NotFound(query)
            1 -> AppLauncher.Result.Launched(candidates[0].first, candidates[0].second)
            else -> {
                // A single candidate that starts with the query is a good enough winner;
                // "gma" -> Gmail should not be blocked by "Gmail Go".
                val prefixMatches = candidates.filter { (_, label) ->
                    label.lowercase().replace(" ", "").startsWith(needle)
                }
                if (prefixMatches.size == 1) {
                    AppLauncher.Result.Launched(prefixMatches[0].first, prefixMatches[0].second)
                } else {
                    AppLauncher.Result.Ambiguous(query, candidates.map { it.second })
                }
            }
        }
    }
}
