package com.phoneagent.safety

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the safety data files. Kept apart from the logic so the logic stays pure and the
 * parsing is the only thing that needs a file.
 */
object SafetyConfigLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class KeywordFile(
        val high: Map<String, List<String>> = emptyMap(),
        val medium: Map<String, List<String>> = emptyMap(),
        val blocked: Map<String, List<String>> = emptyMap(),
        val injection: Map<String, List<String>> = emptyMap(),
        @SerialName("payment_uri_schemes") val paymentUriSchemes: List<String> = emptyList(),
    )

    @Serializable
    private data class IntentPolicyFile(
        @SerialName("allowed_low") val allowedLow: List<String> = emptyList(),
        @SerialName("allowed_medium") val allowedMedium: List<String> = emptyList(),
        @SerialName("always_blocked") val alwaysBlocked: List<String> = emptyList(),
        @SerialName("allowed_uri_schemes") val allowedUriSchemes: List<String> = emptyList(),
        @SerialName("blocked_uri_schemes") val blockedUriSchemes: List<String> = emptyList(),
        @SerialName("allowed_settings_prefix") val allowedSettingsPrefix: String = "android.settings.",
        @SerialName("max_uri_length") val maxUriLength: Int = 2048,
        @SerialName("max_extra_bytes") val maxExtraBytes: Int = 8192,
    )

    fun parseKeywords(source: String): KeywordSets {
        val file = json.decodeFromString<KeywordFile>(source)
        return KeywordSets(
            high = file.high.flatten(),
            medium = file.medium.flatten(),
            blocked = file.blocked.flatten(),
            injectionKeywords = file.injection.flatten(),
            paymentUriSchemes = file.paymentUriSchemes,
        )
    }

    fun parseIntentPolicy(source: String): IntentPolicy {
        val file = json.decodeFromString<IntentPolicyFile>(source)
        return IntentPolicy(
            allowedLow = file.allowedLow.toSet(),
            allowedMedium = file.allowedMedium.toSet(),
            alwaysBlocked = file.alwaysBlocked.toSet(),
            allowedUriSchemes = file.allowedUriSchemes.map { it.lowercase() }.toSet(),
            blockedUriSchemes = file.blockedUriSchemes.map { it.lowercase() }.toSet(),
            allowedSettingsPrefix = file.allowedSettingsPrefix,
            maxUriLength = file.maxUriLength,
            maxExtraBytes = file.maxExtraBytes,
        )
    }

    private fun Map<String, List<String>>.flatten(): List<String> =
        values.flatten().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
