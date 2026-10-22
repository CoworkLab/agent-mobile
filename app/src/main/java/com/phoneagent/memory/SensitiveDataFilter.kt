package com.phoneagent.memory

/**
 * Finds the three things that must never be written to memory: passwords, one-time codes, and
 * card numbers.
 *
 * Two rules shaped the design.
 *
 * First, redaction rather than refusal. An episode summary such as "sent the code to Amit"
 * is worth keeping; only the code itself is not. Refusing the whole sentence would throw away
 * the useful part, so the value is replaced and the structure survives.
 *
 * Second, a checksum for card numbers. A plain "16 digits" rule matches order numbers, phone
 * numbers written without spaces, and timestamps. Running the Luhn check that every real card
 * satisfies removes almost all of those false positives, so a legitimate number is not
 * mangled. The cost is that a mistyped card number is not caught, which is the right trade:
 * a mistyped number is not a secret worth protecting, and a mangled order number is a real
 * annoyance.
 */
object SensitiveDataFilter {

    const val REDACTED = "[removed]"

    /** Words that introduce a secret. Latin and Devanagari, matching the safety keywords. */
    private val PASSWORD_WORDS = listOf(
        "password", "passwd", "pass word", "pwd", "passcode",
        "पासवर्ड", "पासवर्ड", "गुप्त शब्द",
    )

    private val OTP_WORDS = listOf(
        "otp", "o.t.p", "one time password", "one-time password", "verification code",
        "verification number", "security code", "auth code", "2fa code",
        "ओटीपी", "कोड", "सत्यापन कोड",
    )

    private val PIN_WORDS = listOf("pin", "पिन")

    private val CVV_WORDS = listOf("cvv", "cvc", "cvv2", "security digits")

    /**
     * A value that follows a word like "password".
     *
     * Two shapes are accepted, and the difference between them is what stops ordinary text
     * from being mangled:
     *
     *  1. An explicit separator: a colon, an equals sign, "is" or "equals". Anything after it
     *     is treated as the value, so "password: swordfish" is caught.
     *  2. A bare word or quoted string, but only when it contains a digit or is quoted. This is
     *     what catches "cvv 123" and "otp 123456" without also catching "the password field",
     *     where the following word is neither.
     *  3. A value after "to", but only when it contains a digit. This catches "set my pin to
     *     4821" without catching "the password to the account".
     *
     * Devanagari has no word separators, so `\b` is not used for it: the assertion would never
     * fire and the pattern would never match.
     */
    private fun valueAfter(word: String): Regex {
        val boundary = if (word.all { it.code < 128 }) "\\b" else ""
        val escaped = Regex.escape(word)
        val value = "[^\\s\"',;]{3,}"
        val explicit = "\\s*(?::|=|is\\s+|equals\\s+)\\s*[\"']?(" + value + ")[\"']?"
        val implicit = "\\s+[\"'](" + value + ")[\"']|\\s+([^\\s\"',;]*\\d[^\\s\"',;]*)"
        val afterTo = "\\s+to\\s+[\"']?([^\\s\"',;]*\\d[^\\s\"',;]*)[\"']?"
        return Regex(
            "(?i)" + boundary + escaped + boundary +
                "(?:" + explicit + "|" + implicit + "|" + afterTo + ")",
        )
    }

    private val passwordPatterns = PASSWORD_WORDS.map(::valueAfter)
    private val otpPatterns = OTP_WORDS.map(::valueAfter)
    private val pinPatterns = PIN_WORDS.map(::valueAfter)
    private val cvvPatterns = CVV_WORDS.map(::valueAfter)

    /**
     * A run of 13 to 19 digits, optionally in groups separated by spaces or dashes.
     *
     * The separator form matters because that is how a card is printed and how people type
     * it: "4111 1111 1111 1111".
     */
    private val CARD_CANDIDATE = Regex("\\b(?:\\d[ -]?){12,18}\\d\\b")

    /** A six-digit code on its own line, which is how an SMS code often appears. */
    private val BARE_CODE_LINE = Regex("^\\s*\\d{4,8}\\s*$")

    data class Result(
        val text: String,
        val redactions: List<Redaction>,
    ) {
        val isClean: Boolean get() = redactions.isEmpty()
    }

    /** What was removed, for the log. The value itself is deliberately not included. */
    data class Redaction(val kind: Kind, val context: String) {
        enum class Kind { PASSWORD, OTP, PIN, CARD, CVV }
    }

    /** Replaces every secret with [REDACTED]. */
    fun redact(input: String?): Result {
        if (input.isNullOrBlank()) return Result(input.orEmpty(), emptyList())

        val redactions = mutableListOf<Redaction>()
        var out = input

        // Passwords first: a password field may also contain digits that would otherwise look
        // like a code or a card number, and the more specific rule should win.
        out = applyPatterns(out, passwordPatterns, Redaction.Kind.PASSWORD, redactions)
        out = applyPatterns(out, cvvPatterns, Redaction.Kind.CVV, redactions)
        out = applyPatterns(out, otpPatterns, Redaction.Kind.OTP, redactions)
        out = applyPatterns(out, pinPatterns, Redaction.Kind.PIN, redactions)

        // Card numbers are found by shape, then confirmed by checksum.
        out = replaceCardNumbers(out, redactions)

        // A line that is nothing but a short code, which is how an SMS code is usually copied.
        out = replaceBareCodes(out, redactions)

        return Result(out, redactions)
    }

    /** True when the text contains anything that must not be stored. */
    fun containsSensitive(input: String?): Boolean = !redact(input).isClean

    private fun applyPatterns(
        input: String,
        patterns: List<Regex>,
        kind: Redaction.Kind,
        redactions: MutableList<Redaction>,
    ): String {
        var out = input
        patterns.forEach { pattern ->
            out = pattern.replace(out) { match ->
                // Whichever alternative matched, the value is in one of the groups.
                val value = match.groupValues
                    .drop(1)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()

                // A word with no value after it is just a word, not a secret.
                if (value.isBlank() || value.equals(REDACTED, ignoreCase = true)) {
                    match.value
                } else {
                    redactions += Redaction(kind, context = match.value.substringBefore(value).trim())
                    match.value.replace(value, REDACTED)
                }
            }
        }
        return out
    }

    private fun replaceCardNumbers(
        input: String,
        redactions: MutableList<Redaction>,
    ): String = CARD_CANDIDATE.replace(input) { match ->
        val digits = match.value.filter { it.isDigit() }
        if (digits.length in 13..19 && luhnValid(digits)) {
            redactions += Redaction(Redaction.Kind.CARD, context = "card number")
            REDACTED
        } else {
            match.value
        }
    }

    private fun replaceBareCodes(
        input: String,
        redactions: MutableList<Redaction>,
    ): String = input.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (BARE_CODE_LINE.matches(trimmed) && !looksLikeACard(trimmed)) {
            redactions += Redaction(Redaction.Kind.OTP, context = "standalone code")
            REDACTED
        } else {
            line
        }
    }

    private fun looksLikeACard(digits: String): Boolean =
        digits.length in 13..19 && luhnValid(digits)

    /**
     * The check every real card number passes.
     *
     * Working from the right, every second digit is doubled; a doubled digit above nine has
     * nine subtracted. The total must be a multiple of ten.
     */
    fun luhnValid(digits: String): Boolean {
        val clean = digits.filter { it.isDigit() }
        if (clean.length < 2) return false

        var sum = 0
        var double = false
        for (index in clean.indices.reversed()) {
            var value = clean[index] - '0'
            if (double) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }
}
