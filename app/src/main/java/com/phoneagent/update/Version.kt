package com.phoneagent.update

/**
 * A version number that can be compared.
 *
 * String comparison is wrong for versions: "1.10.0" sorts before "1.9.0" as text but after it
 * as a version. Comparing the numbers is the only correct way, and getting it wrong means
 * either offering an update that is older than what is installed or never offering a real one.
 *
 * A missing part counts as zero, so "1.2" equals "1.2.0". That is what makes a tag like
 * `v1.2` comparable with `versionName = "1.2.0"`.
 */
data class Version(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    /** Anything after the third number, such as the `-beta.1` in `1.0.0-beta.1`. */
    val preRelease: String? = null,
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

        // A pre-release is older than the release it leads to: 1.0.0-beta is before 1.0.0.
        // This is the rule people expect, and it stops a beta being offered over a release.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        /**
         * Reads a version from a tag or a version name.
         *
         * Forgiving by design: a leading `v`, extra spaces, and a missing minor or patch are
         * all accepted, because tags are written by hand. Anything with no leading number at
         * all returns null, and the caller treats that as "not a version" rather than
         * guessing, since guessing here could offer a downgrade.
         */
        fun parse(raw: String?): Version? {
            val text = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()
            if (text.isEmpty()) return null

            val (numbers, pre) = text.split('-', limit = 2).let {
                it[0] to it.getOrNull(1)?.takeIf { p -> p.isNotBlank() }
            }

            val parts = numbers.split('.')
            if (parts.isEmpty()) return null

            val parsed = parts.map { it.trim().toIntOrNull() }
            // Every part before the first failure must be a number, and the first must be one.
            if (parsed.firstOrNull() == null) return null

            val values = mutableListOf<Int>()
            for (part in parsed) {
                if (part == null) break
                values += part
            }

            return Version(
                major = values.getOrElse(0) { 0 },
                minor = values.getOrElse(1) { 0 },
                patch = values.getOrElse(2) { 0 },
                preRelease = pre,
            )
        }
    }
}

/**
 * Which release to offer, if any.
 *
 * The rules are deliberately conservative: never offer a downgrade, never offer a pre-release
 * to someone on a stable version, and never offer anything when the comparison is not
 * understood. Offering nothing is always safe; offering the wrong build is not.
 */
object UpdateDecision {

    data class Release(
        val version: Version,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val sizeBytes: Long = 0,
    )

    sealed interface Result {
        /** The installed version is current or newer. */
        data class UpToDate(val installed: Version) : Result

        /** A newer stable release is available. */
        data class Available(val release: Release) : Result

        /** The check itself could not be made. */
        data class Failed(val reason: String) : Result
    }

    /**
     * Decides whether to offer [latest].
     *
     * [allowPreRelease] is off by default. Someone who installed a stable build did not ask to
     * be moved onto a beta, and a beta is where the rough edges are.
     */
    fun decide(
        installedVersionName: String?,
        latest: Release?,
        allowPreRelease: Boolean = false,
    ): Result {
        val installed = Version.parse(installedVersionName)
            ?: return Result.Failed("This app's own version could not be read, so no update was offered.")

        if (latest == null) {
            return Result.Failed("No release was found to compare against.")
        }

        if (!allowPreRelease && latest.version.preRelease != null) {
            return Result.UpToDate(installed)
        }

        return if (latest.version > installed) {
            Result.Available(latest)
        } else {
            Result.UpToDate(installed)
        }
    }
}
