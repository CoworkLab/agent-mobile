package com.mobileagent.app.network

import android.util.Base64
import android.util.Log
import com.mobileagent.app.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        // Shared OkHttpClient across all sessions — reuses connection pool, DNS cache, and TLS sessions
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(60, TimeUnit.SECONDS)
            .build()
    }

    private val client = sharedClient

    private var activeModel: String = when (model) {
        GeminiConstants.MODEL_3_8_LIVE -> GeminiConstants.MODEL_3_8_LIVE
        GeminiConstants.MODEL_3_8_LIVE_THINKING -> GeminiConstants.MODEL_3_8_LIVE_THINKING
        // Legacy dead ID saved by older builds: run on the default Live model.
        GeminiConstants.LEGACY_MODEL_3_1_FLASH_AGENT -> GeminiConstants.MODEL_3_1_FLASH
        else -> if (model.isNotBlank() && !model.contains("2.5")) model else GeminiConstants.DEFAULT_MODEL
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    var promptProvider: (() -> String)? = null

    fun isSessionReady(): Boolean = isConnected.get() && isSetupComplete.get()

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API Key is empty. Aborting connection.")
            listener.onError("Gemini API Key is missing. Please configure it in Settings.", isFatal = true, errorCode = "API_KEY_MISSING")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live. Sending BidiSetup...")
                    isConnected.set(true)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CONFIGURING...")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = reason.contains("API_KEY_INVALID", ignoreCase = true) ||
                                       reason.contains("API key not valid", ignoreCase = true) ||
                                       reason.contains("API key", ignoreCase = true) ||
                                       reason.contains("key invalid", ignoreCase = true)
                    if (isApiKeyError) {
                        Log.e(TAG, "Gemini rejected session due to invalid API key (code=$code, reason=$reason)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    val isQuotaError = reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                                       reason.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "Gemini rate-limited or connection collision (code=$code, reason=$reason). Retrying in cooldown...")
                        // Surface it once so the user is never left staring at silence —
                        // Gemini reports quota over the close frame, not a JSON error.
                        listener.onError("API quota/rate limit reached — retrying automatically...", isFatal = false, errorCode = "QUOTA_EXCEEDED")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    // Gemini reports setup-payload problems over the WebSocket close frame
                    // (e.g. 1007 "Invalid JSON payload received. Unknown name ... at setup.tools[N]",
                    // or "models/<id> not found"). Classify them instead of retrying blindly:
                    val isModelRejection = reason.contains("not found", ignoreCase = true) ||
                            reason.contains("not supported", ignoreCase = true) ||
                            reason.contains("BidiGenerateContent", ignoreCase = true)
                    if (isModelRejection && activeModel != GeminiConstants.MODEL_3_8_LIVE) {
                        Log.w(TAG, "Model $activeModel rejected by Gemini (code=$code, reason=$reason). Auto-falling back to ${GeminiConstants.MODEL_3_8_LIVE}")
                        activeModel = GeminiConstants.MODEL_3_8_LIVE
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                        return
                    }

                    val isPayloadRejected = reason.contains("Invalid JSON payload", ignoreCase = true) ||
                            reason.contains("Unknown name", ignoreCase = true) ||
                            reason.contains("Cannot find field", ignoreCase = true) ||
                            reason.contains("Invalid value at", ignoreCase = true)
                    if (isPayloadRejected) {
                        // Retrying the same payload is pointless — make the failure VISIBLE
                        // instead of looping forever with the agent appearing dead.
                        Log.e(TAG, "Gemini rejected the setup payload (code=$code, reason=$reason)")
                        listener.onError("Gemini rejected the session setup: $reason", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("ERROR • SETUP REJECTED")
                        return
                    }

                    if (code == 1008) {
                        listener.onError("Gemini setup was rejected: $reason", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost (${code}). Retrying...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val responseBody = try { response?.body?.string() } catch (_: Exception) { null }
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: code=$code, errorMsg=$errorMsg, body=$responseBody", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = code == 400 || code == 401 || code == 403 ||
                            errorMsg.contains("400") || errorMsg.contains("403") || errorMsg.contains("401") ||
                            errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                            errorMsg.contains("API key not valid", ignoreCase = true) ||
                            (responseBody?.contains("API_KEY_INVALID", ignoreCase = true) == true) ||
                            (responseBody?.contains("API key not valid", ignoreCase = true) == true)

                    val isQuotaError = code == 429 ||
                            errorMsg.contains("429") ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            errorMsg.contains("quota", ignoreCase = true) ||
                            (responseBody?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true)

                    if (isApiKeyError) {
                        Log.e(TAG, "Fatal failure: Invalid API Key (HTTP $code: $errorMsg)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    if (isQuotaError) {
                        Log.w(TAG, "Quota / rate limit hit (HTTP $code: $errorMsg). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}", isFatal = true, errorCode = "CONNECTION_FAILED")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = systemPrompt
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", activeModel)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", effectivePrompt)))
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.6)
                    // Gemini 2.5 Flash native audio has thinking enabled by default (budget=2048),
                    // which causes a 2-3 second delay before generating speech.
                    // Setting thinking_budget to 0 disables chain-of-thought, yielding instant (<1s) conversational response.
                    if (activeModel.contains("2.5")) {
                        put("thinking_config", JSONObject().apply {
                            put("thinking_budget", 0)
                        })
                    }
                })
                put("tools", com.mobileagent.app.tools.ToolRegistry.setupToolsJson())
            })
        }
        val json = setup.toString()
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live with tools")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // Check if server returned an error payload
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "") ?: json.optString("error", "Unknown error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                Log.e(TAG, "Server returned error: code=$errCode, message=$errMsg")
                val isApiKey = errMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                               errMsg.contains("API key not valid", ignoreCase = true) ||
                               errCode == 400 || errCode == 403
                val isQuota = errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                              errMsg.contains("quota", ignoreCase = true) ||
                              errCode == 429
                if (isApiKey) {
                    listener.onError("Invalid API Key: $errMsg", isFatal = true, errorCode = "API_KEY_INVALID")
                    listener.onConnectionStateChanged("ERROR • INVALID KEY")
                } else if (isQuota) {
                    listener.onError("API Quota Exceeded: $errMsg", isFatal = true, errorCode = "QUOTA_EXCEEDED")
                    listener.onConnectionStateChanged("ERROR • QUOTA EXCEEDED")
                } else {
                    val isModelRejected = errMsg.contains("not found", ignoreCase = true) ||
                            errMsg.contains("not supported", ignoreCase = true) ||
                            errCode == 404
                    if (isModelRejected && activeModel != GeminiConstants.MODEL_3_8_LIVE) {
                        Log.w(TAG, "Model $activeModel rejected via error payload ($errMsg). Auto-falling back to ${GeminiConstants.MODEL_3_8_LIVE}")
                        activeModel = GeminiConstants.MODEL_3_8_LIVE
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onError("Gemini Error ($errCode): $errMsg", isFatal = false)
                    }
                }
                return
            }

            // 0. Server setupComplete handshake acknowledgment
            if (json.has("setupComplete")) {
                Log.i(TAG, "Server acknowledged setup: setupComplete received. Session is now LIVE!")
                reconnectAttempts = 0
                if (!isSetupComplete.getAndSet(true)) {
                    listener.onConnectionStateChanged("LIVE")
                    startSessionRenewal()
                }
                return
            }

            // Ensure session is marked ready if server sends content before explicit setupComplete
            if (!isSetupComplete.get() && (json.has("serverContent") || json.has("toolCall"))) {
                isSetupComplete.set(true)
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
            }

            // 1. Top-level toolCall
            val topToolCall = json.optJSONObject("toolCall")
            if (topToolCall != null) {
                val functionCalls = topToolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val name = call.optString("name", "")
                        val id = call.optString("id", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty()) {
                            Log.i(TAG, "Gemini invoked toolCall: $name (id=$id)")
                            listener.onToolCall(id, name, args)
                        }
                    }
                }
                return
            }

            // 2. serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                val scToolCall = serverContent.optJSONObject("toolCall")
                if (scToolCall != null) {
                    val functionCalls = scToolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val name = call.optString("name", "")
                            val id = call.optString("id", "")
                            val args = call.optJSONObject("args") ?: JSONObject()
                            if (name.isNotEmpty()) {
                                Log.i(TAG, "Gemini invoked toolCall (serverContent): $name (id=$id)")
                                listener.onToolCall(id, name, args)
                            }
                        }
                    }
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    var userText = inputTrans.optString("text", "").trim()
                    if (userText.isEmpty()) {
                        val parts = inputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            userText = sb.toString().trim()
                        }
                    }
                    if (userText.isNotBlank()) {
                        listener.onUserTextReceived(userText)
                    }
                }

                var hasOutputTranscriptionText = false
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    var outText = outputTrans.optString("text", "")
                    if (outText.isEmpty()) {
                        val parts = outputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            outText = sb.toString()
                        }
                    }
                    if (outText.isNotEmpty()) {
                        hasOutputTranscriptionText = true
                        listener.onAssistantTextReceived(outText)
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Filter out internal thinking/reasoning parts (Gemini 2.5 thinking tokens)
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) {
                                Log.d(TAG, "Suppressed model thought part: ${part.optString("text").take(40)}")
                                continue
                            }
                            val text = part.optString("text", "")
                            val isThinkingLeak = text.startsWith("**") && (
                                    text.contains("thinking", ignoreCase = true) ||
                                    text.contains("crafting", ignoreCase = true) ||
                                    text.contains("formulating", ignoreCase = true) ||
                                    text.contains("acknowledge", ignoreCase = true) ||
                                    text.contains("inquire", ignoreCase = true) ||
                                    text.contains("greeting", ignoreCase = true)
                            )
                            if (text.isNotEmpty() && !isThinkingLeak && !hasOutputTranscriptionText) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.length > 8) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (pcmBytes.size >= 16) {
                                        val validLen = pcmBytes.size and 1.inv()
                                        val aligned = if (validLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLen)
                                        listener.onAudioDataReceived(aligned)
                                    }
                                }
                            }
                        }
                    }
                }

                val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                 serverContent.optBoolean("generationComplete", false) ||
                                 serverContent.optBoolean("endOfTurn", false)
                if (isTurnDone) {
                    Log.d(TAG, "Server signaled turnComplete/generationComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("tool_response", JSONObject().apply {
                    put("function_responses", JSONArray().put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", result)
                    }))
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent tool_response for $functionName: $result")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    private var mediaChunksSent = 0
    private val lastVideoFrameSentAt = AtomicLong(0L)

    /**
     * Streams real-time screen frames to Gemini Live (BidiGenerateContent).
     */
    fun sendMediaChunk(jpegBytes: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || jpegBytes.isEmpty()) return
        // Gemini Live accepts video as a continuous realtimeInput stream at up to 1 FPS.
        // Enforce the cap here so periodic and post-action frames share one rate limiter.
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastVideoFrameSentAt.get()
            if (now - previous < 1000L) return
            if (lastVideoFrameSentAt.compareAndSet(previous, now)) break
        }
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$base64Image\"}}}"
            val sent = webSocket?.send(json) ?: false
            mediaChunksSent++
            if (mediaChunksSent % 10 == 1) {
                Log.i(TAG, "Sent video frame #$mediaChunksSent to Gemini Live (sent=$sent, size=${jpegBytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media chunk: ${e.message}")
        }
    }

    /**
     * In Gemini Live BidiGenerateContent, streaming audio via realtimeInput handles turn-taking
     * natively via server-side neural VAD. Sending empty client_content cancels active generation.
     */
    fun sendAudioStreamEnd() {
        // No-op: server-side VAD handles turn completion smoothly
    }

    /**
     * Sends a text prompt to Gemini Live using the client_content turn protocol.
     */
    fun sendClientContent(text: String) {
        if (!isConnected.get() || !isSetupComplete.get() || text.isBlank()) return
        try {
            val msg = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turn_complete", true)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent client_content: $text")
        } catch (e: Exception) {
            Log.e(TAG, "sendClientContent failed: ${e.message}", e)
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            // Exponential backoff so quota collisions / network drops do not hammer the API.
            val backoffMs = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L)
            val delayMs = backoffMs[reconnectAttempts.coerceAtMost(backoffMs.lastIndex)]
            reconnectAttempts++
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            delay(delayMs)
            if (!isManuallyStopped.get()) {
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
