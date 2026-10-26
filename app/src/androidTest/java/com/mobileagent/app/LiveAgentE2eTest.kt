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
 *     If the full payload is rejected, a bare no-tools probe runs too, so the
 *     failure message pinpoints whether the tool declarations are the cause.
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

    /** Data collected from one provider session attempt. */
    private class SessionResult(
        val wentLive: Boolean,
        val audioBytes: Int,
        val finished: Boolean,
        val heard: String,
        val errors: List<String>,
        val states: List<String>
    )

    /** Runs one full provider session: connect -> (optional) text turn -> disconnect. */
    private fun runProviderSession(apiKey: String, includeTools: Boolean, turnText: String?): SessionResult {
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
            includeTools = includeTools,
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
            provider.connect(scope)
            val wentLive = liveLatch.await(45, TimeUnit.SECONDS)
            var finished = false
            var heard = ""
            if (wentLive && turnText != null) {
                provider.sendClientContent(turnText)
                finished = turnDone.await(90, TimeUnit.SECONDS)
                heard = synchronized(assistantText) { assistantText.toString() }.trim()
            }
            return SessionResult(wentLive, audioBytes.get(), finished, heard, errors.toList(), states.toList())
        } finally {
            provider.disconnect()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Stage 1: provider level (full production payload, with bare-payload fallback diagnostics)
    // ------------------------------------------------------------------

    @Test
    fun stage1_providerLevel_realKey_fullToolSetup_agentResponds() {
        val key = apiKeyFromRunner()
        assumeTrue("geminiApiKey instrumentation arg not provided - skipping live E2E (normal CI run)", key != null)
        val apiKey = key!!

        // --- Attempt A: the EXACT production payload (all tool declarations). ---
        val full = runProviderSession(apiKey, includeTools = true, turnText = "Say exactly: PONG")
        if (full.wentLive && full.finished) {
            assertTrue(
                "Agent completed the turn but produced NO audio response " +
                    "(audio=${full.audioBytes}B text='${full.heard}' errors=${full.errors})",
                full.audioBytes > 500
            )
            android.util.Log.i("LiveAgentE2eTest",
                "STAGE1 PASS (full production payload): audio=${full.audioBytes}B " +
                    "assistantText='${full.heard}'")
            return
        }

        // --- Attempt A failed. Run attempt B (bare payload, no tools) to isolate the cause. ---
        Thread.sleep(20_000) // spacing for the free-tier Live RPM budget
        val bare = runProviderSession(apiKey, includeTools = false, turnText = null)

        val fullFailure = if (full.wentLive)
            "connected LIVE but the turn never completed (audio=${full.audioBytes}B " +
                "text='${full.heard}' errors=${full.errors})"
        else
            "never reached LIVE. errors=${full.errors} states=${full.states}"

        if (bare.wentLive) {
            // Bare setup works -> the tool declarations are the problem.
            assertTrue(
                "FULL PRODUCTION PAYLOAD REJECTED by Gemini while a bare no-tools setup " +
                    "connects fine -> the problem is inside ToolRegistry.setupToolsJson(). " +
                    "Full-payload failure: $fullFailure. Bare probe: LIVE OK.",
                false
            )
        } else {
            assertTrue(
                "Gemini rejected BOTH the full payload and a bare no-tools setup -> " +
                    "the problem is endpoint/key/model level, not the tool declarations. " +
                    "Full: $fullFailure. Bare: errors=${bare.errors} states=${bare.states}",
                false
            )
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

        // The service refuses to start a session without these; grant them NOW —
        // the app is installed (we are running inside it), unlike pre-install adb grants.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (cmd in listOf(
            "pm grant com.mobileagent.app android.permission.RECORD_AUDIO",
            "pm grant com.mobileagent.app android.permission.POST_NOTIFICATIONS",
            "dumpsys deviceidle whitelist +com.mobileagent.app"
        )) {
            try {
                automation.executeShellCommand(cmd).close()
            } catch (e: Exception) {
                android.util.Log.w("LiveAgentE2eTest", "shell cmd failed: $cmd (${e.message})")
            }
        }

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
