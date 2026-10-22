package com.phoneagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataFilterTest {

    private val filter = SensitiveDataFilter

    // ---- Luhn ---------------------------------------------------------------------------

    @Test
    fun `a known valid card passes the checksum`() {
        // Standard test numbers, published by the card networks for testing.
        assertTrue(filter.luhnValid("4111111111111111"))
        assertTrue(filter.luhnValid("5555555555554444"))
        assertTrue(filter.luhnValid("378282246310005"))
        assertTrue(filter.luhnValid("6011111111111117"))
    }

    @Test
    fun `a mistyped card fails the checksum`() {
        assertFalse(filter.luhnValid("4111111111111112"))
    }

    @Test
    fun `a long non-card number fails the checksum`() {
        // This is the point of the checksum: most 16-digit numbers are not cards.
        assertFalse(filter.luhnValid("1234567890123456"))
    }

    @Test
    fun `a single digit is not a card`() {
        assertFalse(filter.luhnValid("4"))
        assertFalse(filter.luhnValid(""))
    }

    @Test
    fun `spaces in a card number do not affect the check`() {
        assertTrue(filter.luhnValid("4111 1111 1111 1111"))
    }

    // ---- card numbers -------------------------------------------------------------------

    @Test
    fun `a card number is removed`() {
        val result = filter.redact("My card is 4111 1111 1111 1111 for the order")

        assertFalse("the number must not survive", result.text.contains("4111"))
        assertTrue(result.text.contains(SensitiveDataFilter.REDACTED))
    }

    @Test
    fun `a card number with dashes is removed`() {
        val result = filter.redact("card 4111-1111-1111-1111")

        assertFalse(result.text.contains("4111"))
    }

    @Test
    fun `a card number with no separators is removed`() {
        val result = filter.redact("4111111111111111")

        assertFalse(result.text.contains("4111111111111111"))
    }

    @Test
    fun `a long number that fails the checksum is kept, because it is not a card`() {
        val orderNumber = "order 1234567890123456 confirmed"

        val result = filter.redact(orderNumber)

        assertTrue("a non-card number should not be mangled", result.text.contains("1234567890123456"))
        assertTrue(result.isClean)
    }

    @Test
    fun `an ordinary phone number is kept`() {
        val result = filter.redact("Call 9876543210 about the order")

        assertTrue(result.isClean)
        assertTrue(result.text.contains("9876543210"))
    }

    // ---- passwords ----------------------------------------------------------------------

    @Test
    fun `a password after the word password is removed`() {
        val result = filter.redact("password: hunter2secret")

        assertFalse(result.text.contains("hunter2secret"))
        assertTrue(result.text.contains(SensitiveDataFilter.REDACTED))
    }

    @Test
    fun `a password with an equals sign is removed`() {
        assertFalse(filter.redact("pwd=abc12345").text.contains("abc12345"))
    }

    @Test
    fun `a password written with the word is is removed`() {
        assertFalse(filter.redact("the password is Swordfish99").text.contains("Swordfish99"))
    }

    @Test
    fun `a quoted password is removed`() {
        assertFalse(filter.redact("""password "MySecret1"""").text.contains("MySecret1"))
    }

    @Test
    fun `the word password alone is not a secret`() {
        val result = filter.redact("I could not find the password field")

        assertTrue("there is no value to remove", result.isClean)
    }

    // ---- one-time codes -----------------------------------------------------------------

    @Test
    fun `a code after the word otp is removed`() {
        assertFalse(filter.redact("otp is 483920").text.contains("483920"))
    }

    @Test
    fun `a verification code is removed`() {
        assertFalse(filter.redact("verification code: 112233").text.contains("112233"))
    }

    @Test
    fun `a code on its own line is removed, because that is how it is copied from a message`() {
        val result = filter.redact("Your code is below:\n483920\nDo not share it.")

        assertFalse(result.text.contains("483920"))
        assertTrue(result.text.contains(SensitiveDataFilter.REDACTED))
    }

    @Test
    fun `a number that is part of a sentence is not treated as a standalone code`() {
        val result = filter.redact("I waited 45 seconds for the screen")

        assertTrue(result.isClean)
    }

    @Test
    fun `a pin is removed`() {
        assertFalse(filter.redact("my pin is 4821").text.contains("4821"))
    }

    @Test
    fun `a cvv is removed`() {
        assertFalse(filter.redact("cvv 123").text.contains("123"))
    }

    // ---- Hindi and Hinglish -------------------------------------------------------------

    @Test
    fun `a Devanagari otp line is removed`() {
        val result = filter.redact("ओटीपी: 998877")

        assertFalse(result.text.contains("998877"))
    }

    @Test
    fun `a Devanagari password line is removed`() {
        assertFalse(filter.redact("पासवर्ड: mysupersecret").text.contains("mysupersecret"))
    }

    // ---- properties ---------------------------------------------------------------------

    @Test
    fun `the redaction records what kind of secret was found`() {
        val result = filter.redact("password: abc12345")

        assertEquals(
            SensitiveDataFilter.Redaction.Kind.PASSWORD,
            result.redactions.single().kind,
        )
    }

    @Test
    fun `the redaction never includes the secret itself`() {
        val result = filter.redact("password: topsecretvalue")

        val described = result.redactions.joinToString { "${it.kind} ${it.context}" }
        assertFalse(
            "the log must not carry the secret it removed",
            described.contains("topsecretvalue"),
        )
    }

    @Test
    fun `a card redaction is reported as a card`() {
        val result = filter.redact("4111 1111 1111 1111")

        assertEquals(SensitiveDataFilter.Redaction.Kind.CARD, result.redactions.single().kind)
    }

    @Test
    fun `an empty or blank input is clean`() {
        assertTrue(filter.redact(null).isClean)
        assertTrue(filter.redact("").isClean)
        assertTrue(filter.redact("   ").isClean)
    }

    @Test
    fun `ordinary text is untouched`() {
        val text = "Opened WhatsApp and sent hello to Amit"

        val result = filter.redact(text)

        assertEquals(text, result.text)
        assertTrue(result.isClean)
    }

    @Test
    fun `several secrets in one line are all removed`() {
        val result = filter.redact("password: hunter2secret and card 4111 1111 1111 1111")

        assertFalse(result.text.contains("hunter2secret"))
        assertFalse(result.text.contains("4111"))
        assertEquals(2, result.redactions.size)
    }

    @Test
    fun `containsSensitive agrees with redact`() {
        assertTrue(filter.containsSensitive("otp 123456"))
        assertFalse(filter.containsSensitive("opened the app"))
    }

    @Test
    fun `redacting twice changes nothing more`() {
        val once = filter.redact("password: hunter2secret").text

        val twice = filter.redact(once)

        assertEquals(once, twice.text)
        assertTrue("the placeholder must not itself be treated as a secret", twice.isClean)
    }
}
