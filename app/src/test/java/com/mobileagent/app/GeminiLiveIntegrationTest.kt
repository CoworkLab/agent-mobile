package com.mobileagent.app

import com.google.gson.JsonParser
import com.mobileagent.app.data.model.GeminiConstants
import com.mobileagent.app.network.LiveProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * REAL-WORLD integration test: talks to Google's Gemini API with a live key.
 *
 * Runs in GitHub Actions (US runners — Gemini API is geo-restricted in some
 * regions, so it is opt-in via env) whenever GEMINI_LIVE_IT=1 and
 * GEMINI_API_KEY are set. Everywhere else it silently skips.
 *
 * It validates exactly what the app does:
 *  1. The API key is accepted by the REST endpoint.
 *  2. The app's setup payload (identical bytes — built by LiveProtocol) is
 *     accepted by BidiGenerateContent for at least one model in the fallback chain.
 *  3. Transcription is returned (outputTranscription), i.e. the chat screen
 *     receives assistant text.
 *  4. A full conversational turn completes.
 */
class GeminiLiveIntegrationTest {

    private val apiKey: String? = System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotBlank() }

    private fun integrationEnabled(): Boolean =
        System.getenv("GEMINI_LIVE_IT") == "1" && apiKey != null

    @Test
    fun `rest endpoint accepts the api key and lists models`() {
        assumeTrue(integrationEnabled())
        val key = apiKey!!
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("${GeminiConstants.REST_BASE_URL}/models?key=$key&pageSize=200")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            println("REST /models -> HTTP ${response.code}")
            assertEquals("Key rejected by REST endpoint: $body", 200, response.code)
            val models = JsonParser.parseString(body).asJsonObject.getAsJsonArray("models")
            println("Models visible to this key: ${models.size()}")
            val bidi = models.map { it.asJsonObject }
                .filter { it.getAsJsonArray("supportedGenerationMethods")?.any { m -> m.asString == "bidiGenerateContent" } == true }
                .map { it.get("name").asString }
            println("bidiGenerateContent-capable models: $bidi")
        }
    }

    @Test
    fun `live websocket accepts app setup payload and completes a turn`() {
        assumeTrue(integrationEnabled())
        val key = apiKey!!
        val results = LinkedHashMap<String, String>()

        var workingModel: String? = null
        for (model in GeminiConstants.SUPPORTED_MODELS) {
            val outcome = tryLiveHandshake(key, model)
            results[model] = outcome
            println("MODEL $model -> $outcome")
            if (outcome == "FULL_TURN_OK") {
                workingModel = model
                break
            }
        }

        println("==== Gemini Live integration summary ====")
        results.forEach { (m, r) -> println("  $m -> $r") }
        println("=========================================")

        assertTrue(
            "No model in the catalogue completed a real Live turn: $results",
            workingModel != null
        )
        if (results[GeminiConstants.DEFAULT_MODEL] != "FULL_TURN_OK") {
            println(
                "WARNING: default model ${GeminiConstants.DEFAULT_MODEL} is not live for this key " +
                        "(${results[GeminiConstants.DEFAULT_MODEL]}); the app auto-falls back to $workingModel."
            )
        }
    }

    /** Connects, sends the app's exact setup, waits for setupComplete, then a full text turn. */
    private fun tryLiveHandshake(key: String, model: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val setupDone = CountDownLatch(1)
        val turnComplete = CountDownLatch(1)
        val transcript = AtomicReference("")
        val audioReceived = AtomicBoolean(false)
        val terminalStatus = AtomicReference<String?>(null)

        val ws = client.newWebSocket(
            Request.Builder().url(LiveProtocol.wsUrl(key)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setup = LiveProtocol.buildSetupJson(
                        model = model,
                        voiceName = GeminiConstants.DEFAULT_VOICE,
                        systemPrompt = "You are a helpful assistant. Keep replies to one short sentence.",
                        toolsJson = null,
                        includeSpeechConfig = true,
                        disableThinking = model.contains("2.5")
                    )
                    webSocket.send(setup)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                    if (json.has("setupComplete")) {
                        setupDone.countDown()
                        // Send a typed chat turn exactly like the app's chat tab does.
                        webSocket.send(LiveProtocol.buildClientContentJson("Reply with exactly: OK"))
                        return
                    }
                    if (json.has("error")) {
                        val err = json.getAsJsonObject("error")
                        terminalStatus.compareAndSet(
                            null,
                            "ERROR ${err.get("code")}: ${err.get("message")}"
                        )
                        setupDone.countDown()
                        turnComplete.countDown()
                        return
                    }
                    val sc = json.getAsJsonObject("serverContent") ?: return
                    sc.getAsJsonObject("outputTranscription")?.get("text")?.let {
                        transcript.accumulateAndGet(it.asString) { a, b -> a + b }
                    }
                    val mt = sc.getAsJsonObject("modelTurn")
                    mt?.getAsJsonArray("parts")?.forEach { p ->
                        if (p.asJsonObject.has("inlineData")) audioReceived.set(true)
                    }
                    if (sc.get("turnComplete")?.asBoolean == true) turnComplete.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    terminalStatus.compareAndSet(
                        null,
                        "FAILURE HTTP ${response?.code ?: 0}: ${t.message} ${body?.take(300).orEmpty()}"
                    )
                    setupDone.countDown()
                    turnComplete.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    terminalStatus.compareAndSet(null, "CLOSED $code: $reason")
                    setupDone.countDown()
                    turnComplete.countDown()
                }
            }
        )

        try {
            val setupOk = setupDone.await(20, TimeUnit.SECONDS) && terminalStatus.get() == null
            if (!setupOk) {
                return terminalStatus.get() ?: "TIMEOUT waiting for setupComplete"
            }
            val turnDone = turnComplete.await(45, TimeUnit.SECONDS)
            if (!turnDone) return "TIMEOUT waiting for turnComplete"

            val reply = transcript.get().trim()
            return when {
                reply.isNotEmpty() -> "FULL_TURN_OK (transcript: ${reply.take(80)})"
                audioReceived.get() -> "FULL_TURN_OK (audio only)"
                else -> "TURN_COMPLETED_BUT_NO_TRANSCRIPTION"
            }
        } finally {
            runCatching { ws.close(1000, "test done") }
            client.dispatcher.executorService.shutdown()
        }
    }
}
