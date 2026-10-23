package com.phoneagent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceErrorsTest {

    @Test
    fun `no match asks the person to try again`() {
        val explained = VoiceErrors.explain(VoiceErrors.Codes.NO_MATCH)

        assertEquals(VoiceErrors.Recovery.RETRY, explained.recovery)
        assertTrue(explained.message!!, explained.message.contains("did not catch"))
    }

    @Test
    fun `a speech timeout explains how to hold the button`() {
        val explained = VoiceErrors.explain(VoiceErrors.Codes.SPEECH_TIMEOUT)

        assertTrue(explained.message!!, explained.message.contains("Hold the button"))
    }

    @Test
    fun `a missing permission asks for the microphone, not for a retry`() {
        val explained = VoiceErrors.explain(VoiceErrors.Codes.INSUFFICIENT_PERMISSIONS)

        assertEquals(VoiceErrors.Recovery.NEEDS_PERMISSION, explained.recovery)
        assertTrue(explained.message!!, explained.message.contains("microphone"))
    }

    @Test
    fun `network errors are reported as needing a connection`() {
        listOf(
            VoiceErrors.Codes.NETWORK,
            VoiceErrors.Codes.NETWORK_TIMEOUT,
            VoiceErrors.Codes.SERVER,
            VoiceErrors.Codes.SERVER_DISCONNECTED,
        ).forEach { code ->
            val explained = VoiceErrors.explain(code)
            assertEquals("code $code", VoiceErrors.Recovery.NEEDS_NETWORK, explained.recovery)
        }
    }

    @Test
    fun `a busy recogniser asks the person to wait, not to retry at once`() {
        val explained = VoiceErrors.explain(VoiceErrors.Codes.RECOGNIZER_BUSY)

        assertTrue(explained.message!!, explained.message.contains("Wait"))
    }

    @Test
    fun `an unsupported language points at the settings screen`() {
        val explained = VoiceErrors.explain(VoiceErrors.Codes.LANGUAGE_NOT_SUPPORTED)

        assertTrue(explained.message!!, explained.message.contains("Settings"))
    }

    @Test
    fun `an unknown code is silent rather than alarming`() {
        val explained = VoiceErrors.explain(9999)

        assertNull(explained.message)
        assertEquals(VoiceErrors.Recovery.SILENT, explained.recovery)
    }

    @Test
    fun `no message ever contains the word error`() {
        // The person is holding a phone; "error 7" tells them nothing and worries them.
        (0..15).forEach { code ->
            val message = VoiceErrors.explain(code).message
            if (message != null) {
                assertFalse("code $code: $message", message.contains("error", ignoreCase = true))
            }
        }
    }

    @Test
    fun `every message is a sentence a person can act on`() {
        (0..15).forEach { code ->
            val message = VoiceErrors.explain(code).message ?: return@forEach
            assertTrue("code $code should end with a full stop", message.trim().endsWith("."))
            assertTrue("code $code is too long", message.length < 120)
        }
    }
}

class PushToTalkControllerTest {

    private val transcripts = mutableListOf<Pair<String, Boolean>>()
    private val messages = mutableListOf<Pair<String, VoiceErrors.Recovery>>()
    private var permissionRequests = 0

    private fun controller() = PushToTalkController(
        onTranscript = { text, final -> transcripts += text to final },
        onMessage = { text, recovery -> messages += text to recovery },
        requestMicrophonePermission = { permissionRequests++ },
    )

    @Test
    fun `a press starts listening`() {
        val controller = controller()

        assertTrue(controller.start())
        assertTrue(controller.isListening)
    }

    @Test
    fun `a second press while listening is ignored`() {
        val controller = controller()
        controller.start()

        assertFalse("a second start would cut off the first", controller.start())
    }

    @Test
    fun `a partial result is shown as not final`() {
        val controller = controller()
        controller.start()

        controller.onPartialResult("send a message")

        assertEquals(listOf("send a message" to false), transcripts)
        assertTrue(controller.isListening)
    }

    @Test
    fun `a blank partial result is ignored, because it would clear what was said`() {
        val controller = controller()
        controller.start()
        controller.onPartialResult("hello")

        controller.onPartialResult("   ")

        assertEquals("hello", controller.transcript)
    }

    @Test
    fun `a final result is marked final and stops listening`() {
        val controller = controller()
        controller.start()

        controller.onFinalResult("set an alarm")

        assertEquals(listOf("set an alarm" to true), transcripts)
        assertFalse(controller.isListening)
    }

    @Test
    fun `releasing the button hands over the partial result rather than discarding it`() {
        val controller = controller()
        controller.start()
        controller.onPartialResult("call Amit")

        controller.stop()

        assertEquals(listOf("call Amit" to false, "call Amit" to true), transcripts)
        assertFalse(controller.isListening)
    }

    @Test
    fun `releasing with nothing said produces nothing`() {
        val controller = controller()
        controller.start()

        controller.stop()

        assertTrue(transcripts.isEmpty())
    }

    @Test
    fun `releasing without a press does nothing`() {
        val controller = controller()

        controller.stop()

        assertTrue(transcripts.isEmpty())
    }

    @Test
    fun `cancelling discards the result`() {
        val controller = controller()
        controller.start()
        controller.onPartialResult("something private")

        controller.cancel()

        // The partial was already shown, because partials are shown live so the person can
        // see they are being heard. What cancel must stop is the *final* result being used.
        assertTrue(
            "no final transcript may be handed over after a cancel",
            transcripts.none { it.second },
        )
        assertFalse(controller.isListening)
        assertEquals("", controller.transcript)
    }

    @Test
    fun `a result arriving after cancel is ignored`() {
        val controller = controller()
        controller.start()
        controller.cancel()

        controller.onFinalResult("too late")

        assertTrue(transcripts.isEmpty())
    }

    @Test
    fun `an error stops listening and explains itself`() {
        val controller = controller()
        controller.start()

        controller.onError(VoiceErrors.Codes.NO_MATCH)

        assertFalse(controller.isListening)
        assertEquals(VoiceErrors.Recovery.RETRY, messages.single().second)
    }

    @Test
    fun `a permission error asks for the permission`() {
        val controller = controller()
        controller.start()

        controller.onError(VoiceErrors.Codes.INSUFFICIENT_PERMISSIONS)

        assertEquals(1, permissionRequests)
        assertEquals(VoiceErrors.Recovery.NEEDS_PERMISSION, messages.single().second)
    }

    @Test
    fun `a silent error says nothing at all`() {
        val controller = controller()
        controller.start()

        controller.onError(9999)

        assertTrue("the person does not need to be told about a cancellation", messages.isEmpty())
        assertFalse(controller.isListening)
    }

    @Test
    fun `the default language is Hindi, because that is who this is for`() {
        assertEquals("hi-IN", controller().languageTag)
    }

    @Test
    fun `cycling the language goes through the three the design asks for`() {
        val controller = controller()

        assertEquals("en-IN", controller.cycleLanguage())
        assertEquals("en-US", controller.cycleLanguage())
        assertEquals("hi-IN", controller.cycleLanguage())
    }

    @Test
    fun `a fresh attempt clears the previous transcript`() {
        val controller = controller()
        controller.start()
        controller.onFinalResult("first attempt")

        controller.start()

        assertEquals("", controller.transcript)
    }
}
