package com.phoneagent.voice

/**
 * What a speech recognition error means for the person.
 *
 * `SpeechRecognizer` reports a bare integer, and the numbers are not all the same kind of
 * thing: some are the person's to fix ("say that again"), some are the app's ("no permission"),
 * and one is neither ("no speech"). Turning them into a sentence here keeps that distinction
 * in one place and makes it testable, which matters because a bad message here is the
 * difference between the person retrying and the person giving up.
 */
object VoiceErrors {

    /** What the app should do next. */
    enum class Recovery {
        /** Say the message and let the person try again immediately. */
        RETRY,

        /** Ask for the microphone permission, then try again. */
        NEEDS_PERMISSION,

        /** Nothing will work until a network is available. */
        NEEDS_NETWORK,

        /** The person stopped on purpose, or said nothing. Say nothing. */
        SILENT,
    }

    data class Explained(
        val message: String?,
        val recovery: Recovery,
    )

    /** The codes `SpeechRecognizer` uses, by name so the call sites read clearly. */
    object Codes {
        const val NETWORK_TIMEOUT = 1
        const val NETWORK = 2
        const val AUDIO = 3
        const val SERVER = 4
        const val CLIENT = 5
        const val SPEECH_TIMEOUT = 6
        const val NO_MATCH = 7
        const val RECOGNIZER_BUSY = 8
        const val INSUFFICIENT_PERMISSIONS = 9
        const val TOO_MANY_REQUESTS = 10
        const val SERVER_DISCONNECTED = 11
        const val LANGUAGE_NOT_SUPPORTED = 12
        const val LANGUAGE_UNAVAILABLE = 13
    }

    /**
     * Turns a code into a sentence and a next step.
     *
     * The wording avoids "error" and jargon. The person is holding a phone and pressing a
     * button; the message has to tell them what to do, not what went wrong internally.
     */
    fun explain(code: Int): Explained = when (code) {
        Codes.NO_MATCH -> Explained(
            "I did not catch that. Try again, a little closer to the phone.",
            Recovery.RETRY,
        )

        Codes.SPEECH_TIMEOUT -> Explained(
            "I did not hear anything. Hold the button while you speak.",
            Recovery.RETRY,
        )

        Codes.INSUFFICIENT_PERMISSIONS -> Explained(
            "I need permission to use the microphone.",
            Recovery.NEEDS_PERMISSION,
        )

        Codes.NETWORK, Codes.NETWORK_TIMEOUT, Codes.SERVER, Codes.SERVER_DISCONNECTED -> Explained(
            "Speech needs an internet connection, which is not available right now.",
            Recovery.NEEDS_NETWORK,
        )

        Codes.AUDIO -> Explained(
            "I could not hear anything. Check that nothing is covering the microphone.",
            Recovery.RETRY,
        )

        Codes.RECOGNIZER_BUSY -> Explained(
            "I am still finishing the last thing. Wait a moment and try again.",
            Recovery.RETRY,
        )

        Codes.TOO_MANY_REQUESTS -> Explained(
            "Too many attempts just now. Wait a moment and try again.",
            Recovery.RETRY,
        )

        Codes.LANGUAGE_NOT_SUPPORTED, Codes.LANGUAGE_UNAVAILABLE -> Explained(
            "This phone cannot listen in the selected language. Change it in Settings.",
            Recovery.RETRY,
        )

        Codes.CLIENT -> Explained(
            "Listening could not start. Try again.",
            Recovery.RETRY,
        )

        // The person pressed stop, or the recogniser was cancelled as part of normal use.
        // Saying "error" here would be wrong and would worry them for no reason.
        else -> Explained(null, Recovery.SILENT)
    }
}

/**
 * The push-to-talk state machine.
 *
 * Kept separate from `SpeechRecognizer` because the interesting behaviour is not the API call
 * but the sequencing: a press must start listening, a release must stop it, and partial
 * results must be shown as they arrive so the person can see that they are being heard. All
 * of that is testable without a device.
 */
class PushToTalkController(
    private val languageTags: List<String> = DEFAULT_LANGUAGES,
    private val onTranscript: (String, Boolean) -> Unit = { _, _ -> },
    private val onMessage: (String, VoiceErrors.Recovery) -> Unit = { _, _ -> },
    private val requestMicrophonePermission: () -> Unit = {},
) {

    /** Whether the microphone is held down. */
    var isListening: Boolean = false
        private set

    /** The best transcript so far, partial until [stop] is called. */
    var transcript: String = ""
        private set

    /** The language of the current attempt, for the caller to pass to the recogniser. */
    var languageTag: String = languageTags.firstOrNull() ?: "en-IN"
        private set

    /** Starts an attempt. Returns false when one is already running. */
    fun start(): Boolean {
        if (isListening) return false
        isListening = true
        transcript = ""
        return true
    }

    /** Shows a partial result while the person is still speaking. */
    fun onPartialResult(text: String) {
        if (!isListening) return
        if (text.isBlank()) return
        transcript = text
        onTranscript(text, false)
    }

    /** The final result. Stops listening and hands back the text. */
    fun onFinalResult(text: String) {
        if (!isListening) return
        isListening = false
        if (text.isNotBlank()) transcript = text
        onTranscript(transcript, true)
    }

    /** The person released the button, so stop listening. */
    fun stop() {
        if (!isListening) return
        isListening = false
        // A release with a partial result is not a lost attempt: the partial is handed over
        // rather than discarded, because the person has already said it.
        if (transcript.isNotBlank()) onTranscript(transcript, true)
    }

    /** Cancels without using the result. */
    fun cancel() {
        isListening = false
        transcript = ""
    }

    fun onError(code: Int) {
        isListening = false
        val explained = VoiceErrors.explain(code)
        when (explained.recovery) {
            VoiceErrors.Recovery.SILENT -> Unit
            VoiceErrors.Recovery.NEEDS_PERMISSION -> {
                onMessage(explained.message.orEmpty(), explained.recovery)
                requestMicrophonePermission()
            }

            else -> onMessage(explained.message.orEmpty(), explained.recovery)
        }
    }

    /**
     * Cycles to the next language.
     *
     * The design asks for Hindi, Indian English and US English, in that order, because the
     * person most likely to use this app speaks one of the first two. Cycling rather than a
     * picker keeps the phone-only interaction to one gesture.
     */
    fun cycleLanguage(): String {
        val index = languageTags.indexOf(languageTag)
        languageTag = languageTags[(index + 1) % languageTags.size]
        return languageTag
    }

    companion object {
        val DEFAULT_LANGUAGES = listOf("hi-IN", "en-IN", "en-US")
    }
}
