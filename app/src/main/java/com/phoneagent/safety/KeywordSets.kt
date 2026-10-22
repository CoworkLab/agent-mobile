package com.phoneagent.safety

/**
 * The multilingual keyword sets, parsed from `assets/safety_keywords.json`.
 *
 * Kept as a plain data class so tests can build one inline without touching assets.
 */
data class KeywordSets(
    val high: List<String>,
    val medium: List<String>,
    val blocked: List<String>,
    val injectionKeywords: List<String>,
    val paymentUriSchemes: List<String>,
) {

    private val highRegexes = high.map { it.toMatcher() }
    private val mediumRegexes = medium.map { it.toMatcher() }
    private val blockedRegexes = blocked.map { it.toMatcher() }

    fun firstHighMatch(text: String): String? = firstMatch(high, highRegexes, text)

    fun firstMediumMatch(text: String): String? = firstMatch(medium, mediumRegexes, text)

    fun firstBlockedMatch(text: String): String? = firstMatch(blocked, blockedRegexes, text)

    private fun firstMatch(
        keywords: List<String>,
        regexes: List<Regex>,
        text: String,
    ): String? {
        regexes.forEachIndexed { index, regex ->
            if (regex.containsMatchIn(text)) return keywords[index]
        }
        return null
    }

    /**
     * Devanagari is matched as a substring: it has no word boundaries, and a suffix like
     * "भेजो" carries the meaning on its own. Latin text is matched on word boundaries so
     * that "end" does not fire inside "sending".
     */
    private fun String.toMatcher(): Regex {
        val escaped = Regex.escape(this)
        val isLatin = all { it.code < 128 }
        return if (isLatin) {
            Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        } else {
            Regex(escaped, RegexOption.IGNORE_CASE)
        }
    }

    companion object {
        val EMPTY = KeywordSets(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
