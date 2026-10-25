package com.mobileagent.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-shot speech recognition used by the stt_listen tool when the live conversation
 * microphone is NOT active. Returns the best recognised string or null.
 */
object VoiceListener {

    fun listenOnce(context: Context, language: String, timeoutMs: Long): String? = runCatching {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val latch = java.util.concurrent.CountDownLatch(1)
        val heard = java.util.concurrent.atomic.AtomicReference<String?>(null)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                heard.set(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                latch.countDown()
            }
            override fun onError(error: Int) { latch.countDown() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { recognizer.stopListening() }
        recognizer.destroy()
        heard.get()
    }.getOrNull()
}
