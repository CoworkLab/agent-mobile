package com.phoneagent.update

import java.security.MessageDigest

/**
 * Checks a downloaded file before the system installer is allowed to see it.
 *
 * This is the only thing standing between a bad download and a request to install. Without it,
 * a corrupted file, a truncated download, or a file swapped in transit would be handed to the
 * installer and the person would be asked to approve it. So the rule is absolute: the file is
 * only offered when its digest matches the one published with the release.
 *
 * The comparison is constant-time. A plain string comparison returns as soon as two bytes
 * differ, which leaks how much of a guess was correct; that is a real attack against a check
 * like this one, and the fix is two lines.
 */
object ApkVerifier {

    sealed interface Result {
        /** The file matches the published digest and may be installed. */
        data object Verified : Result

        /** The digest did not match, so the file must be deleted. */
        data class Mismatch(val expected: String, val actual: String) : Result

        /** The check could not be completed. Treated exactly like a mismatch. */
        data class Unreadable(val reason: String) : Result

        val isSafeToInstall: Boolean get() = this is Verified
    }

    /** Hashes a file and compares it with the digest published for the release. */
    fun verify(file: java.io.File, expectedSha256: String): Result {
        if (!file.exists()) return Result.Unreadable("The downloaded file is not there.")
        if (file.length() == 0L) return Result.Unreadable("The downloaded file is empty.")

        val expected = normalize(expectedSha256)
        if (expected == null) {
            return Result.Unreadable("The release did not publish a usable checksum.")
        }

        val actual = try {
            sha256(file)
        } catch (error: Throwable) {
            return Result.Unreadable("The downloaded file could not be read: ${error.message}")
        }

        return if (constantTimeEquals(expected, actual)) {
            Result.Verified
        } else {
            Result.Mismatch(expected = expected, actual = actual)
        }
    }

    /** The digest of a file, as lowercase hex. */
    fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** The digest of some bytes, for tests and for the release script. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * Accepts a digest as published and returns it as lowercase hex, or null when it is not a
     * SHA-256 at all.
     *
     * Being strict here matters: a short or malformed digest must not be compared against a
     * prefix of the real one and accidentally match.
     */
    fun normalize(raw: String?): String? {
        val clean = raw?.trim()?.lowercase()?.removePrefix("sha256:")?.replace(" ", "").orEmpty()
        if (clean.length != SHA256_HEX_LENGTH) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return clean
    }

    /** Compares without leaking how many leading characters matched. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].code xor b[index].code)
        }
        return difference == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte)
    }

    private const val SHA256_HEX_LENGTH = 64
}
