package com.phoneagent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskerTest {

    @Test
    fun `google api keys are masked`() {
        val out = SecretMasker.mask("key=AIzaSyB1234567890abcdefghijklmnop")
        assertFalse(out.contains("AIzaSyB1234567890abcdefghijklmnop"))
    }

    @Test
    fun `gemini AQ style keys are masked`() {
        // Built at runtime so no real-looking key literal lives in the repository.
        val key = "AQ." + "FakeTestValue" + "NotARealSecret" + "0123456789"
        val out = SecretMasker.mask("value $key end")
        assertFalse(out.contains(key))
    }

    @Test
    fun `github tokens are masked`() {
        val out = SecretMasker.mask("token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        assertFalse(out.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
    }

    @Test
    fun `bearer headers are masked but keep the scheme`() {
        val out = SecretMasker.mask("Authorization: Bearer sk-abcdefghijklmnop")
        assertFalse(out.contains("sk-abcdefghijklmnop"))
        assertTrue(out.contains("Bearer"))
    }

    @Test
    fun `ordinary text is untouched`() {
        val text = "Opening Settings and toggling Wi-Fi"
        assertEquals(text, SecretMasker.mask(text))
    }
}
