package com.mobileagent.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobileagent.app.data.model.GeminiConstants
import com.mobileagent.app.network.GeminiLiveWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * REAL end-to-end proof that the agent responds, on a REAL Android emulator,
 * through the EXACT production provider stack the shipped APK uses:
 *
 *   GeminiLiveWebSocket (Part 7 provider core)
 *     + ToolRegistry.setupToolsJson() (the full production setup payload)
 *     + the user's real Gemini API key
     *     + the real wss://generativelanguage.googleapis.com endpoint
 *
 * The test is opt-in: it only runs when the instrumentation argument
 * `geminiApiKey` is provided (GitHub Actions emulator-live job):
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.geminiApiKey=AIza...
 *
 * Everywhere else (PRs, local runs, plain release CI) it silently skips.
 */
@RunWith(AndroidJUnit4::class)
class LiveAgentE2eTest {

    private fun apiKeyFromRunner(): String? =
        InstrumentationRegistry.getArguments().getString("geminiApiKey")?.trim()?.takeIf { it.isNotBlank() }

    @Test
    fun productionProvider_realKey_fullToolSetup_agentResponds() {
        val key = apiKeyFromRunner()
        assumeTrue("geminiApiKey instrumentation arg not provided - skipping live E2E (normal CI run)", key != null)
        val apiKey = key!!

        val liveLatch = CountDownLatch(1)
        val turnDone = CountDownLatch(1)
        val audioBytes = AtomicInteger(0)
        val toolCalls = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val states = CopyOnWriteArrayList<String>()
        val assistantText = StringBuffer()

        val provider = GeminiLiveWebSocket(
            apiKey = apiKey,
            model = GeminiConstants.DEFAULT_MODEL,          // models/gemini-3.1-flash-live-preview (Part 7 default)
            voiceName = "Puck",
            systemPrompt = "You are Mobile Agent, a professional on-device Android AI assistant. " +
                "Reply to the user briefly and exactly as requested.",
            listener = object : GeminiLiveWebSocket.Listener {
                override fun onConnectionStateChanged(status: String) {
                    states.add(status)
                    if (status == "LIVE") liveLatch.countDown()
                }

                override fun onAudioDataReceived(pcmData: ByteArray) {
                    audioBytes.addAndGet(pcmData.size)
                }

                override fun onAssistantTextReceived(textChunk: String) {
                    synchronized(assistantText) { assistantText.append(textChunk) }
                }

                override fun onUserTextReceived(textChunk: String) {}

                override fun onInterrupted() {}

                override fun onTurnCompleted() {
                    turnDone.countDown()
                }

                override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                    errors.add("[${errorCode ?: "-"}] $message")
                }

                override fun onToolCall(callId: String, name: String, args: JSONObject) {
                    toolCalls.add(name)
                }
            }
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // --- Stage 1: connect + setup (full production payload incl. all tool declarations) ---
            provider.connect(scope)
            val wentLive = liveLatch.await(45, TimeUnit.SECONDS)
            assertTrue(
                "Provider never reached LIVE state. states=$states errors=$errors",
                wentLive
            )

            // --- Stage 2: typed chat turn, exactly like ChatFragment -> service -> sendClientContent ---
            provider.sendClientContent("Say exactly: PONG")
            val finished = turnDone.await(90, TimeUnit.SECONDS)
            val heard = synchronized(assistantText) { assistantText.toString() }.trim()
            val audio = audioBytes.get()

            assertTrue(
                "Agent did not complete the turn within 90s. audio=${audio}B text='$heard' " +
                    "errors=$errors states=$states",
                finished
            )
            assertTrue(
                "Agent completed the turn but produced NO audio response " +
                    "(audio=${audio}B text='$heard' errors=$errors)",
                audio > 500
            )

            android.util.Log.i("LiveAgentE2eTest",
                "E2E PASS: audio=${audio}B assistantText='$heard' toolCalls=$toolCalls")
        } finally {
            provider.disconnect()
            scope.cancel()
        }
    }
}
