package com.phoneagent.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentValidatorTest {

    private val policy = IntentPolicy(
        allowedLow = setOf(
            "android.intent.action.VIEW",
            "android.intent.action.DIAL",
            "android.intent.action.SENDTO",
            "android.intent.action.SEND",
            "android.intent.action.WEB_SEARCH",
            "android.intent.action.MAIN",
            "android.intent.action.SHOW_ALARMS",
        ),
        allowedMedium = setOf(
            "android.intent.action.SET_ALARM",
            "android.intent.action.SET_TIMER",
            "android.intent.action.INSERT",
        ),
        alwaysBlocked = setOf(
            "android.intent.action.CALL",
            "android.intent.action.CALL_PRIVILEGED",
            "android.intent.action.DELETE",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.app.action.ADD_DEVICE_ADMIN",
        ),
        allowedUriSchemes = setOf("http", "https", "geo", "mailto", "sms", "smsto", "tel", "market", "content"),
        blockedUriSchemes = setOf("file", "intent", "javascript", "data"),
    )

    private val denylist = PackageDenylist(listOf("com.bank.app", "com.upi.*"))
    private val validator = IntentValidator(policy, denylist)

    @Test
    fun `opening an https link is allowed`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "https://example.com"),
            resolvedPackage = "com.android.chrome",
        )
        assertTrue(verdict.reason, verdict.allowed)
        assertEquals(RiskLevel.LOW, verdict.riskLevel)
    }

    @Test
    fun `opening the dialer is allowed`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.DIAL", uri = "tel:+919876543210"),
            resolvedPackage = "com.android.dialer",
        )
        assertTrue(verdict.reason, verdict.allowed)
    }

    @Test
    fun `placing a call is blocked because it completes the action`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.CALL", uri = "tel:+919876543210"),
            resolvedPackage = "com.android.dialer",
        )
        assertFalse(verdict.allowed)
        assertEquals(RiskLevel.BLOCKED, verdict.riskLevel)
    }

    @Test
    fun `uninstalling is blocked`() {
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.UNINSTALL_PACKAGE",
                uri = "package:com.example.app",
            ),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a device admin intent is blocked`() {
        val verdict = validator.validate(
            IntentRequest("android.app.action.ADD_DEVICE_ADMIN"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a upi payment scheme is blocked`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "upi://pay?pa=someone@upi&am=500"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason, verdict.reason.contains("upi"))
    }

    @Test
    fun `a file uri is blocked in favour of content uris`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "file:///sdcard/secret.txt"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a content uri is allowed`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "content://media/external/images/1"),
            resolvedPackage = "com.google.android.apps.photos",
        )
        assertTrue(verdict.reason, verdict.allowed)
    }

    @Test
    fun `an intent wrapper uri is blocked because it hides the real target`() {
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.VIEW",
                uri = "intent://evil#Intent;scheme=upi;end",
            ),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `an unknown action is blocked by default`() {
        val verdict = validator.validate(
            IntentRequest("com.example.SECRET_ACTION"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason, verdict.reason.contains("default deny"))
    }

    @Test
    fun `an intent resolving to a denied package is blocked`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "https://example.com"),
            resolvedPackage = "com.bank.app",
        )
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason, verdict.reason.contains("does not act inside"))
    }

    @Test
    fun `an explicitly named denied package is blocked before resolving`() {
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.VIEW",
                uri = "https://example.com",
                packageName = "com.upi.wallet",
            ),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a settings screen is allowed without being listed`() {
        val verdict = validator.validate(
            IntentRequest("android.settings.WIFI_SETTINGS"),
            resolvedPackage = "com.android.settings",
        )
        assertTrue(verdict.reason, verdict.allowed)
    }

    @Test
    fun `setting an alarm is allowed but logged as medium`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.SET_ALARM"),
            resolvedPackage = "com.android.deskclock",
        )
        assertTrue(verdict.reason, verdict.allowed)
        assertEquals(RiskLevel.MEDIUM, verdict.riskLevel)
        assertTrue(verdict.reason, verdict.reason.contains("logged"))
    }

    @Test
    fun `a scheme-less uri is rejected as malformed`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "www.example.com"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `an over-long uri is rejected`() {
        val long = "https://example.com/" + "a".repeat(policy.maxUriLength)
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = long),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `too many extras is rejected`() {
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.SEND",
                extras = mapOf("body" to "x".repeat(policy.maxExtraBytes + 1)),
            ),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `blind copy recipients are rejected`() {
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.SENDTO",
                uri = "mailto:a@b.com",
                extras = mapOf("android.intent.extra.BCC" to "secret@b.com"),
            ),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a view intent with nothing to open is rejected`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `an empty action is rejected`() {
        val verdict = validator.validate(IntentRequest("   "), resolvedPackage = null)
        assertFalse(verdict.allowed)
    }

    @Test
    fun `encoding a prefill keeps unicode and uses percent-twenty for spaces`() {
        val encoded = IntentValidator.encodeQueryValue("नमस्ते दुनिया")
        assertFalse("spaces must not become plus signs", encoded.contains('+'))
        assertTrue("devanagari must survive", encoded.contains("%"))
        assertEquals("a%20b", IntentValidator.encodeQueryValue("a b"))
    }

    @Test
    fun `a whatsapp prefill link is allowed and the send tap is separate`() {
        // The intent only opens the chat with text prefilled; it never sends.
        val text = IntentValidator.encodeQueryValue("Hello there")
        val verdict = validator.validate(
            IntentRequest(
                "android.intent.action.VIEW",
                uri = "https://wa.me/919876543210?text=$text",
            ),
            resolvedPackage = "com.whatsapp",
        )
        assertTrue(verdict.reason, verdict.allowed)
        assertEquals("the prefill itself is not risky", RiskLevel.LOW, verdict.riskLevel)
    }

    @Test
    fun `a tel uri without digits is rejected`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.DIAL", uri = "tel:abc"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a mailto uri without an at sign is rejected`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.SENDTO", uri = "mailto:not-an-email"),
            resolvedPackage = null,
        )
        assertFalse(verdict.allowed)
    }

    @Test
    fun `a geo uri is allowed`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "geo:28.6139,77.2090?q=India+Gate"),
            resolvedPackage = "com.google.android.apps.maps",
        )
        assertTrue(verdict.reason, verdict.allowed)
    }

    @Test
    fun `the verdict carries the detail the audit log needs`() {
        val verdict = validator.validate(
            IntentRequest("android.intent.action.VIEW", uri = "https://example.com"),
            resolvedPackage = "com.android.chrome",
        )
        assertEquals("android.intent.action.VIEW", verdict.action)
        assertEquals("https://example.com", verdict.uri)
        assertEquals("com.android.chrome", verdict.resolvedPackage)
        assertNotNull(verdict.reason)
    }
}

class SafetyConfigLoaderTest {

    @Test
    fun `parses the keyword file and flattens every language`() {
        val source = """
            {
              "high": { "en": ["send"], "hi": ["भेजो"], "hinglish": ["bhejo"] },
              "medium": { "en": ["next"] },
              "blocked": { "en": ["factory reset"] },
              "injection": { "en": ["ignore previous instructions"] },
              "payment_uri_schemes": ["upi"]
            }
        """.trimIndent()

        val sets = SafetyConfigLoader.parseKeywords(source)
        assertTrue(sets.high.containsAll(listOf("send", "भेजो", "bhejo")))
        assertEquals(listOf("next"), sets.medium)
        assertEquals(listOf("factory reset"), sets.blocked)
        assertEquals(listOf("ignore previous instructions"), sets.injectionKeywords)
        assertEquals(listOf("upi"), sets.paymentUriSchemes)
    }

    @Test
    fun `keyword parsing deduplicates and trims`() {
        val source = """{ "high": { "en": ["send", " send ", "send"] } }"""
        val sets = SafetyConfigLoader.parseKeywords(source)
        assertEquals(listOf("send"), sets.high)
    }

    @Test
    fun `parses the intent policy file`() {
        val source = """
            {
              "allowed_low": ["android.intent.action.VIEW"],
              "allowed_medium": ["android.intent.action.SET_ALARM"],
              "always_blocked": ["android.intent.action.CALL"],
              "allowed_uri_schemes": ["HTTPS"],
              "blocked_uri_schemes": ["FILE"],
              "allowed_settings_prefix": "android.settings.",
              "max_uri_length": 100,
              "max_extra_bytes": 200
            }
        """.trimIndent()

        val policy = SafetyConfigLoader.parseIntentPolicy(source)
        assertTrue(policy.allowedLow.contains("android.intent.action.VIEW"))
        assertTrue(policy.alwaysBlocked.contains("android.intent.action.CALL"))
        assertTrue("schemes are normalised", policy.allowedUriSchemes.contains("https"))
        assertTrue("schemes are normalised", policy.blockedUriSchemes.contains("file"))
        assertEquals(100, policy.maxUriLength)
        assertEquals(200, policy.maxExtraBytes)
    }

    @Test
    fun `an empty policy file yields a deny-everything policy`() {
        val policy = SafetyConfigLoader.parseIntentPolicy("{}")
        assertTrue(policy.allowedLow.isEmpty())
        assertFalse(policy.classifyAction("android.intent.action.VIEW") is IntentDecision.Allowed)
    }
}
