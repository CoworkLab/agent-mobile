package com.mobileagent.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobileagent.app.background.AgentConversationService
import com.mobileagent.app.data.model.GeminiConstants
import com.mobileagent.app.network.GeminiLiveWebSocket
import com.mobileagent.app.ui.home.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * REAL end-to-end proof that the agent responds, on a REAL Android emulator,
 * through the EXACT production stack the shipped APK uses:
 *
 *   Stage 1 (provider level):
 *     GeminiLiveWebSocket (Part 7 provider core)
 *       + ToolRegistry.setupToolsJson() (full production setup payload)
 *       + the user's real Gemini API key
 *       + the real wss://generativelanguage.googleapis.com endpoint
 *
 *   Stage 2 (service level — the exact path a user's typed chat message takes):
 *     AgentConversationService (foreground service, real mic pipeline)
 *       -> sendTextMessage() -> pending queue -> LIVE session
 *       -> Gemini turn -> chat history bubble fills with the agent's reply
 *
 * Both stages are opt-in: they only run when the instrumentation argument
 * `geminiApiKey` is provided (GitHub Actions emulator-live job):
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.geminiApiKey=AIza...
 *
 * Everywhere else (PRs, local runs, plain release CI) they silently skip.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveAgentE2eTest {

    private fun apiKeyFromRunner(): String? =
        InstrumentationRegistry.getArguments().getString("geminiApiKey")?.trim()?.takeIf { it.isNotBlank() }

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(500)
        }
        return cond()
    }

    private fun awaitNotNull(timeoutMs: Long, probe: () -> String?): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = probe()
            if (last != null) return last
            Thread.sleep(500)
        }
        return last
    }

    private fun isPendingPlaceholder(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || t == "(Audio response)" || t == "..."
    }

    // ------------------------------------------------------------------
    // Stage 1: provider level
    // ------------------------------------------------------------------

    @Test
    fun stage1_providerLevel_realKey_fullToolSetup_agentResponds() {
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
            // --- Connect + setup (full production payload incl. all tool declarations) ---
            provider.connect(scope)
            val wentLive = liveLatch.await(45, TimeUnit.SECONDS)
            assertTrue(
                "Provider never reached LIVE state. states=$states errors=$errors",
                wentLive
            )

            // --- Typed chat turn, exactly like ChatFragment -> service -> sendClientContent ---
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
                "STAGE1 PASS: audio=${audio}B assistantText='$heard' toolCalls=$toolCalls")
        } finally {
            provider.disconnect()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Stage 2: service level (the exact user experience)
    // ------------------------------------------------------------------

    @Test
    fun stage2_serviceLevel_typedChat_agentRepliesInChatHistory() {
        val key = apiKeyFromRunner()
        assumeTrue("geminiApiKey instrumentation arg not provided - skipping live E2E (normal CI run)", key != null)
        val apiKey = key!!

        // Stage 1 just closed a Live session; respect the free-tier Live RPM budget.
        Thread.sleep(25_000)

        val app = AgentApp.instance
        // Configure the app exactly like a real user: paste key + default model, clean chat.
        try {
            app.preferences.apiKey = apiKey
        } catch (e: IllegalStateException) {
            assertTrue("Secure key storage unavailable on emulator: ${e.message}", false)
        }
        app.preferences.aiModel = GeminiConstants.DEFAULT_MODEL
        AgentConversationService.clearLiveError()
        app.chatRepository.clearHistory()

        ActivityScenario.launch(MainActivity::class.java).use {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val startIntent = Intent(ctx, AgentConversationService::class.java).apply {
                action = AgentConversationService.ACTION_START_CONVERSATION
            }
            ctx.startForegroundService(startIntent)

            try {
                // --- Stage A: the foreground service reaches a LIVE Gemini session ---
                val wentLive = awaitTrue(90_000) {
                    AgentConversationService.connectionStatus.value.equals("LIVE", ignoreCase = true)
                }
                assertTrue(
                    "Service never reached LIVE. status='${AgentConversationService.connectionStatus.value}' " +
                        "liveError=${AgentConversationService.liveError.value}",
                    wentLive
                )

                // --- Stage B: typed chat, the exact path ChatFragment uses ---
                val message = "Reply with exactly: PONG"
                AgentConversationService.sendTextMessage(message)

                // --- Stage C: the reply bubble fills with a real, non-error answer ---
                val reply = awaitNotNull(150_000) {
                    val turns = app.chatRepository.turns.value
                    turns.lastOrNull { it.userTranscript.trim() == message }
                        ?.agentResponse?.trim()
                        ?.takeIf { !isPendingPlaceholder(it) }
                }
                assertNotNull(
                    "Agent never replied in chat history. turns=${app.chatRepository.turns.value} " +
                        "status='${AgentConversationService.connectionStatus.value}' " +
                        "liveError=${AgentConversationService.liveError.value}",
                    reply
                )
                val answer = reply!!
                assertFalse(
                    "Agent reply is a delivery-error message, not a real answer: '$answer'",
                    answer.startsWith("[Message not delivered") ||
                        answer.startsWith("[API KEY") ||
                        answer.startsWith("[PERMISSIONS")
                )
                android.util.Log.i("LiveAgentE2eTest", "STAGE2 PASS: chat reply='$answer'")
            } finally {
                ctx.startService(Intent(ctx, AgentConversationService::class.java).apply {
                    action = AgentConversationService.ACTION_STOP_ALL
                })
                Thread.sleep(1_500)
                ctx.stopService(Intent(ctx, AgentConversationService::class.java))
            }
        }
    }
}
