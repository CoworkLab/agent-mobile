package com.mobileagent.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Small on-demand TextToSpeech wrapper used by the tts_speak tool.
 * The engine is created lazily and shared.
 */
object TtsSpeaker {

    private val engine = AtomicReference<TextToSpeech?>(null)

    fun speak(context: Context, text: String, language: String?): Boolean {
        val current = engine.get()
        if (current == null) {
            val created = AtomicReference<TextToSpeech?>(null)
            val ready = java.util.concurrent.CountDownLatch(1)
            val tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) created.get()?.language = Locale.getDefault()
                ready.countDown()
            }
            created.set(tts)
            engine.compareAndSet(null, tts)
            ready.await(4, java.util.concurrent.TimeUnit.SECONDS)
        }
        val tts = engine.get() ?: return false
        if (!language.isNullOrBlank()) {
            runCatching {
                val locale = Locale.forLanguageTag(language)
                if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
            }
        }
        return runCatching {
            tts.setSpeechRate(1.0f)
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "mobile_agent_tts_${System.currentTimeMillis()}") ==
                TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    fun shutdown() {
        engine.getAndSet(null)?.shutdown()
    }
}
