package com.phoneagent.update

import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a release APK to a private directory and verifies it.
 *
 * Two rules shape this:
 *
 *  - The file is written to the app's own cache, never to shared storage. A partially written
 *    or unverified APK must not be somewhere another app could pick it up and offer it.
 *  - The digest is checked before the file is reported as ready. A failure deletes the file
 *    rather than leaving it for a later, less careful code path to find.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val logger: Logger,
    private val directory: File,
) {

    sealed interface Progress {
        data class Started(val totalBytes: Long) : Progress
        data class Advanced(val bytesRead: Long, val totalBytes: Long) : Progress
        data class Finished(val file: File) : Progress
        data class Failed(val reason: String) : Progress
    }

    /**
     * Downloads and verifies.
     *
     * A verification failure is reported as a failure and the file is removed, so nothing
     * downstream can mistake an unverified file for a good one.
     */
    suspend fun download(
        url: String,
        expectedSha256: String,
        fileName: String,
        onProgress: (Progress) -> Unit = {},
    ): Progress = withContext(Dispatchers.IO) {
        val target = File(directory, fileName)

        try {
            if (!directory.exists() && !directory.mkdirs()) {
                return@withContext Progress.Failed("The download folder could not be created.")
            }

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Progress.Failed(
                        "The download was refused by the server (${response.code}).",
                    )
                }

                val body = response.body
                    ?: return@withContext Progress.Failed("The download had no content.")

                val total = body.contentLength()
                onProgress(Progress.Started(total))

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            read += count
                            onProgress(Progress.Advanced(read, total))
                        }
                    }
                }

                // The declared length is checked when the server gave one. A truncated download
                // is the most common failure, and catching it here gives a better message than
                // a checksum mismatch would.
                if (total > 0 && target.length() != total) {
                    target.delete()
                    return@withContext Progress.Failed(
                        "The download stopped early. Please try again.",
                    )
                }
            }

            when (val verified = ApkVerifier.verify(target, expectedSha256)) {
                is ApkVerifier.Result.Verified -> {
                    logger.log(LogLevel.INFO, TAG, "Downloaded and verified $fileName")
                    Progress.Finished(target)
                }

                is ApkVerifier.Result.Mismatch -> {
                    target.delete()
                    logger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Checksum mismatch for $fileName: the file was deleted, not installed.",
                    )
                    Progress.Failed(
                        "The download did not match the published checksum, so it was discarded.",
                    )
                }

                is ApkVerifier.Result.Unreadable -> {
                    target.delete()
                    Progress.Failed(verified.reason)
                }
            }
        } catch (error: Throwable) {
            // Any failure leaves nothing behind that could be installed later.
            runCatching { target.delete() }
            logger.log(LogLevel.ERROR, TAG, "Download failed: ${error.message}")
            Progress.Failed("The download could not be completed.")
        }
    }

    private companion object {
        const val TAG = "ApkDownloader"
    }
}
