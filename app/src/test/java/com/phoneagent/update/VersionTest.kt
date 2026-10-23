package com.phoneagent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `a version is read from a tag with a leading v`() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
    }

    @Test
    fun `a version is read without the v`() {
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
    }

    @Test
    fun `a missing patch counts as zero`() {
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
    }

    @Test
    fun `a missing minor and patch count as zero`() {
        assertEquals(Version(2, 0, 0), Version.parse("2"))
    }

    @Test
    fun `surrounding space is ignored`() {
        assertEquals(Version(1, 2, 3), Version.parse("  v1.2.3  "))
    }

    @Test
    fun `a pre-release suffix is kept`() {
        assertEquals("beta.1", Version.parse("1.0.0-beta.1")!!.preRelease)
    }

    @Test
    fun `an empty or missing string is not a version`() {
        assertNull(Version.parse(null))
        assertNull(Version.parse(""))
        assertNull(Version.parse("   "))
        assertNull(Version.parse("v"))
    }

    @Test
    fun `a string with no leading number is not a version, because guessing could offer a downgrade`() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("release-two"))
    }

    @Test
    fun `ten is greater than nine, which string comparison would get wrong`() {
        // The reason this class exists: "1.10.0" < "1.9.0" as text.
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.0")!!)
    }

    @Test
    fun `versions compare by major first`() {
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
    }

    @Test
    fun `versions compare by minor when the major is equal`() {
        assertTrue(Version.parse("1.3.0")!! > Version.parse("1.2.9")!!)
    }

    @Test
    fun `versions compare by patch when the rest is equal`() {
        assertTrue(Version.parse("1.2.4")!! > Version.parse("1.2.3")!!)
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, Version.parse("1.2.3")!!.compareTo(Version.parse("1.2.3")!!))
    }

    @Test
    fun `a two-part version equals the same version with a zero patch`() {
        assertEquals(Version.parse("1.2")!!, Version.parse("1.2.0")!!)
    }

    @Test
    fun `a pre-release is older than the release it leads to`() {
        assertTrue(Version.parse("1.0.0-beta.1")!! < Version.parse("1.0.0")!!)
    }

    @Test
    fun `a release is newer than its pre-release`() {
        assertTrue(Version.parse("1.0.0")!! > Version.parse("1.0.0-beta.1")!!)
    }

    @Test
    fun `pre-releases compare with each other`() {
        assertTrue(Version.parse("1.0.0-beta.2")!! > Version.parse("1.0.0-beta.1")!!)
    }

    @Test
    fun `a version prints back the way it was written`() {
        assertEquals("1.2.3", Version(1, 2, 3).toString())
        assertEquals("1.2.3-beta.1", Version(1, 2, 3, "beta.1").toString())
    }

    @Test
    fun `a list of versions sorts correctly, which is what makes the newest findable`() {
        val sorted = listOf("1.2.0", "1.10.0", "1.9.0", "2.0.0", "1.0.0")
            .map { Version.parse(it)!! }
            .sorted()

        assertEquals(
            listOf("1.0.0", "1.2.0", "1.9.0", "1.10.0", "2.0.0"),
            sorted.map { it.toString() },
        )
    }
}

class UpdateDecisionTest {

    private fun release(version: String): UpdateDecision.Release {
        val parsed = Version.parse(version)!!
        return UpdateDecision.Release(
            version = parsed,
            versionName = version,
            apkUrl = "https://example.com/app.apk",
            sha256 = "a".repeat(64),
            notes = "Some changes",
        )
    }

    @Test
    fun `a newer release is offered`() {
        val result = UpdateDecision.decide("1.0.0", release("1.1.0"))

        assertTrue(result is UpdateDecision.Result.Available)
        assertEquals("1.1.0", (result as UpdateDecision.Result.Available).release.versionName)
    }

    @Test
    fun `the same version is not offered, because reinstalling is not an update`() {
        assertTrue(UpdateDecision.decide("1.1.0", release("1.1.0")) is UpdateDecision.Result.UpToDate)
    }

    @Test
    fun `an older release is never offered, because that would be a downgrade`() {
        assertTrue(UpdateDecision.decide("2.0.0", release("1.0.0")) is UpdateDecision.Result.UpToDate)
    }

    @Test
    fun `a pre-release is not offered by default`() {
        val result = UpdateDecision.decide("1.0.0", release("2.0.0-beta.1"))

        assertTrue(
            "someone on a stable build did not ask for a beta",
            result is UpdateDecision.Result.UpToDate,
        )
    }

    @Test
    fun `a pre-release is offered when the person opted in`() {
        val result = UpdateDecision.decide(
            "1.0.0",
            release("2.0.0-beta.1"),
            allowPreRelease = true,
        )

        assertTrue(result is UpdateDecision.Result.Available)
    }

    @Test
    fun `a stable release newer than a pre-release is offered`() {
        val result = UpdateDecision.decide("1.0.0-beta.1", release("1.0.0"))

        assertTrue(result is UpdateDecision.Result.Available)
    }

    @Test
    fun `an unreadable installed version offers nothing, rather than guessing`() {
        val result = UpdateDecision.decide("not a version", release("2.0.0"))

        assertTrue(result is UpdateDecision.Result.Failed)
    }

    @Test
    fun `no release at all is a failure, not an update`() {
        val result = UpdateDecision.decide("1.0.0", null)

        assertTrue(result is UpdateDecision.Result.Failed)
    }

    @Test
    fun `a ten-versus-nine update is offered, which string comparison would miss`() {
        val result = UpdateDecision.decide("1.9.0", release("1.10.0"))

        assertTrue("1.10.0 is newer than 1.9.0", result is UpdateDecision.Result.Available)
    }

    @Test
    fun `a failure explains itself rather than being silent`() {
        val result = UpdateDecision.decide(null, release("2.0.0")) as UpdateDecision.Result.Failed

        assertTrue(result.reason.isNotBlank())
    }
}
