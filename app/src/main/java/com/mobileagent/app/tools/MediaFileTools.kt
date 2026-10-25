package com.mobileagent.app.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.provider.MediaStore
import com.mobileagent.app.AgentConversationService as AgentConversationServiceRef
import com.mobileagent.app.audio.TtsSpeaker
import com.mobileagent.app.audio.VoiceListener
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files & media: read/write/list/delete files, download, camera capture, audio recording,
 * text-to-speech and speech-to-text. File access is scoped to app dirs + public storage
 * (MANAGE_EXTERNAL_STORAGE is requested for sideloaded installs).
 */
object MediaFileTools {

    val NAMES: Set<String> = setOf(
        "read_file", "write_file", "list_dir", "delete_file", "download_file",
        "take_photo", "record_audio", "tts_speak", "stt_listen"
    )

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t("read_file", "Read a text file.",
            ToolRegistry.p("path", "STRING", "absolute file path", req = true),
            ToolRegistry.p("max_chars", "INTEGER", "default 20000")),
        ToolRegistry.t("write_file", "Write or append to a text file.",
            ToolRegistry.p("path", "STRING", "absolute file path", req = true),
            ToolRegistry.p("content", "STRING", req = true),
            ToolRegistry.p("append", "BOOLEAN", "default false")),
        ToolRegistry.t("list_dir", "List a directory's contents.",
            ToolRegistry.p("path", "STRING", "absolute directory path", req = true)),
        ToolRegistry.t("delete_file", "Delete a file or folder (recursive for folders). Confirm risky deletes with ask_user first.",
            ToolRegistry.p("path", "STRING", req = true)),
        ToolRegistry.t("download_file", "Download a URL into storage.",
            ToolRegistry.p("url", "STRING", req = true),
            ToolRegistry.p("save_as", "STRING", "file name (saved into Downloads or the app's files dir)")),
        ToolRegistry.t("take_photo", "Capture a photo with the device camera (no shutter UI).",
            ToolRegistry.p("camera", "STRING", "back | front, default back", enum = listOf("back", "front"))),
        ToolRegistry.t("record_audio", "Record microphone audio to a file for N seconds.",
            ToolRegistry.p("seconds", "INTEGER", req = true)),
        ToolRegistry.t("tts_speak", "Speak text aloud through the system TTS engine.",
            ToolRegistry.p("text", "STRING", req = true),
            ToolRegistry.p("language", "STRING", "BCP-47 like hi-IN or en-US")),
        ToolRegistry.t("stt_listen", "Listen with the microphone and return recognised text.",
            ToolRegistry.p("timeout_s", "INTEGER", "default 10"),
            ToolRegistry.p("language", "STRING", "BCP-47 like hi-IN"))
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        val context = ctx.context
        return when (name) {
            "read_file" -> {
                val f = File(args.getString("path"))
                if (!f.exists() || !f.isFile) throw ToolException("File not found: ${f.absolutePath}")
                if (f.length() > 10 * 1024 * 1024) throw ToolException("File is larger than 10 MB; read a specific file instead.")
                val max = args.optInt("max_chars", 20_000).coerceIn(200, 200_000)
                val text = f.readText().take(max)
                ctx.ok("path" to f.absolutePath, "size" to f.length(), "text" to text)
            }
            "write_file" -> {
                val f = File(args.getString("path"))
                f.parentFile?.mkdirs()
                val content = args.getString("content")
                if (args.optBoolean("append", false)) f.appendText(content) else f.writeText(content)
                ctx.ok("written" to true, "path" to f.absolutePath, "size" to f.length())
            }
            "list_dir" -> {
                val d = File(args.getString("path"))
                if (!d.exists() || !d.isDirectory) throw ToolException("Directory not found: ${d.absolutePath}")
                val arr = JSONArray()
                d.listFiles()?.sortedBy { it.name.lowercase() }?.take(200)?.forEach { c ->
                    arr.put(JSONObject()
                        .put("name", c.name)
                        .put("type", if (c.isDirectory) "dir" else "file")
                        .put("size", if (c.isFile) c.length() else 0))
                }
                ctx.ok("path" to d.absolutePath, "count" to arr.length(), "entries" to arr)
            }
            "delete_file" -> {
                val f = File(args.getString("path"))
                if (!f.exists()) throw ToolException("Path not found: ${f.absolutePath}")
                val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
                ctx.ok("deleted" to deleted, "path" to f.absolutePath)
            }
            "download_file" -> {
                val url = args.getString("url")
                val name2 = args.optString("save_as", "").ifBlank {
                    "download_${System.currentTimeMillis()}"
                }
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .readTimeout(java.time.Duration.ofSeconds(60))
                    .build()
                val req = okhttp3.Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ToolException("Download failed: HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: throw ToolException("Empty response body.")
                    val saved = saveToStorage(context, name2, bytes)
                    ctx.ok("saved" to true, "path" to saved, "size" to bytes.size)
                }
            }
            "take_photo" -> {
                if (!ctx.hasPermission(Manifest.permission.CAMERA)) ctx.permissionError("take_photo", Manifest.permission.CAMERA)
                val lens = args.optString("camera", "back")
                val path = CameraSnap.capture(context, lens == "front")
                    ?: throw ToolException(
                        "Camera capture failed (camera busy or permission missing). " +
                            "If the camera is in use, close the other app first."
                    )
                ctx.ok("saved" to true, "path" to path)
            }
            "record_audio" -> {
                if (!ctx.hasPermission(Manifest.permission.RECORD_AUDIO)) ctx.permissionError("record_audio", Manifest.permission.RECORD_AUDIO)
                val seconds = args.optInt("seconds", 5).coerceIn(1, 300)
                val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
                val f = File(dir, "rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a")
                val rec = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(MediaRecorder::class.java)
                          else @Suppress("DEPRECATION") MediaRecorder()
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                delay(seconds * 1000L)
                runCatching { rec.stop() }
                rec.release()
                ctx.ok("saved" to true, "path" to f.absolutePath, "seconds" to seconds)
            }
            "tts_speak" -> {
                val text = args.getString("text")
                val lang = args.optString("language", "")
                val spoke = TtsSpeaker.speak(context, text, lang)
                if (spoke) ctx.ok("spoken" to true) else ctx.fail("TTS engine unavailable on this device.")
            }
            "stt_listen" -> {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    return ctx.fail("No speech recognition service on this device.")
                }
                if (AgentConversationServiceRef.isSessionOn.value) {
                    return ctx.fail(
                        "The live microphone is busy with the ongoing conversation. " +
                            "The user can simply speak right now — no need for stt_listen."
                    )
                }
                val timeout = args.optInt("timeout_s", 10).coerceIn(3, 60)
                val lang = args.optString("language", Locale.getDefault().toString())
                val text = VoiceListener.listenOnce(context, lang, timeout * 1000L)
                if (text.isNullOrBlank()) ctx.fail("No speech recognised within ${timeout}s.")
                else ctx.ok("text" to text)
            }
            else -> ctx.fail("Unknown media/file tool '$name'.")
        }
    }

    fun saveToStorage(context: Context, fileName: String, bytes: ByteArray): String {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(safeName))
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ToolException("Could not create download entry.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw ToolException("Could not open output stream.")
            uri.toString()
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = File(dir, safeName)
            f.writeBytes(bytes)
            f.absolutePath
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}

/**
 * Minimal Camera2 still-capture helper (JPEG via ImageReader, no UI).
 */
private object CameraSnap {
    fun capture(context: Context, front: Boolean): String? = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val facing = if (front) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                     else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == facing
        } ?: return null
        val chars = cm.getCameraCharacteristics(cameraId)
        val size = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: return null

        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
        var device: android.hardware.camera2.CameraDevice? = null

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            img.use { image ->
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                result.set(bytes)
            }
            latch.countDown()
        }, null)

        val stateCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(d: android.hardware.camera2.CameraDevice) {
                device = d
                runCatching {
                    val surface = reader.surface
                    d.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                            runCatching {
                                val req = d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    .addTarget(surface)
                                    .set(android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                                         android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                                    .build()
                                s.capture(req, null, null)
                            }.onFailure { latch.countDown() }
                        }
                        override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { latch.countDown() }
                    }, null)
                }.onFailure { latch.countDown() }
            }
            override fun onDisconnected(d: android.hardware.camera2.CameraDevice) { d.close(); latch.countDown() }
            override fun onError(d: android.hardware.camera2.CameraDevice, error: Int) { d.close(); latch.countDown() }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            cm.openCamera(cameraId, context.mainExecutor, stateCallback)
        } else {
            cm.openCamera(cameraId, stateCallback, null)
        }

        latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { device?.close() }
        reader.close()
        val bytes = result.get() ?: return null
        val dir = File(context.getExternalFilesDir(null), "photos").apply { mkdirs() }
        val f = File(dir, "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()
}
