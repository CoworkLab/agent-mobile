package com.phoneagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private val valid = """
        |---
        |name: Dismiss popups
        |description: Getting past a dialog
        |apps: com.android.settings, com.whatsapp
        |version: 2
        |---
        |
        |Read the dialog, then choose the quieter option.
    """.trimMargin()

    @Test
    fun `a well formed skill parses`() {
        val parsed = SkillParser.parse(valid)

        assertNull(parsed.error)
        val skill = parsed.skill!!
        assertEquals("Dismiss popups", skill.name)
        assertEquals("Getting past a dialog", skill.description)
        assertEquals(listOf("com.android.settings", "com.whatsapp"), skill.apps)
        assertEquals(2, skill.version)
        assertTrue(skill.body.contains("quieter option"))
    }

    @Test
    fun `a skill with no status is approved, because that is the shipped default`() {
        assertEquals(SkillStatus.APPROVED, SkillParser.parse(valid).skill!!.status)
    }

    @Test
    fun `a draft is marked as a draft`() {
        val text = valid.replace("version: 2", "version: 2\nstatus: draft")

        assertEquals(SkillStatus.DRAFT, SkillParser.parse(text).skill!!.status)
    }

    @Test
    fun `an archived skill is marked as archived`() {
        val text = valid.replace("version: 2", "version: 2\nstatus: archived")

        assertEquals(SkillStatus.ARCHIVED, SkillParser.parse(text).skill!!.status)
    }

    @Test
    fun `a missing description is empty rather than an error`() {
        val text = valid.lines().filterNot { it.startsWith("description:") }.joinToString("\n")

        val parsed = SkillParser.parse(text)

        assertNull(parsed.error)
        assertEquals("", parsed.skill!!.description)
    }

    @Test
    fun `missing apps means no restriction, so the skill always applies`() {
        val text = valid.lines().filterNot { it.startsWith("apps:") }.joinToString("\n")

        assertTrue(SkillParser.parse(text).skill!!.apps.isEmpty())
    }

    @Test
    fun `a missing version defaults to one`() {
        val text = valid.lines().filterNot { it.startsWith("version:") }.joinToString("\n")

        assertEquals(1, SkillParser.parse(text).skill!!.version)
    }

    @Test
    fun `a version that is not a number does not fail the whole skill`() {
        val text = valid.replace("version: 2", "version: two")

        assertEquals(1, SkillParser.parse(text).skill!!.version)
    }

    @Test
    fun `text without front matter is rejected with an explanation`() {
        val parsed = SkillParser.parse("Just some prose.")

        assertNull(parsed.skill)
        assertTrue(parsed.error!!.contains("---"))
    }

    @Test
    fun `unclosed front matter is rejected`() {
        val parsed = SkillParser.parse("---\nname: Half a skill\n")

        assertNull(parsed.skill)
        assertTrue(parsed.error!!.contains("not closed"))
    }

    @Test
    fun `a skill with no name is rejected`() {
        val parsed = SkillParser.parse("---\ndescription: nameless\n---\n\nSome body text.")

        assertNull(parsed.skill)
        assertTrue(parsed.error!!.contains("name"))
    }

    @Test
    fun `a skill with no body is rejected, because there is nothing to follow`() {
        val parsed = SkillParser.parse("---\nname: Empty\n---\n\n")

        assertNull(parsed.skill)
        assertTrue(parsed.error!!.contains("body"))
    }

    @Test
    fun `rendering then parsing gives the same skill`() {
        val original = Skill(
            name = "Search inside an app",
            description = "Finding a thing",
            apps = listOf("com.android.chrome"),
            version = 3,
            body = "1. Tap the magnifying glass.\n2. Type.",
        )

        val parsed = SkillParser.parse(SkillParser.render(original)).skill!!

        assertEquals(original.name, parsed.name)
        assertEquals(original.description, parsed.description)
        assertEquals(original.apps, parsed.apps)
        assertEquals(original.version, parsed.version)
        assertEquals(original.body, parsed.body)
        assertEquals(original.status, parsed.status)
    }

    @Test
    fun `a draft survives a render and parse round trip`() {
        val draft = Skill(
            name = "Something learned",
            description = "From one run",
            body = "Steps.",
            status = SkillStatus.DRAFT,
        )

        assertEquals(SkillStatus.DRAFT, SkillParser.parse(SkillParser.render(draft)).skill!!.status)
    }

    @Test
    fun `a colon inside the body does not confuse the parser`() {
        val skill = Skill(
            name = "Colons",
            description = "A body with colons",
            body = "Note: this has a colon.\nAlso: another.",
        )

        val parsed = SkillParser.parse(SkillParser.render(skill)).skill!!

        assertTrue(parsed.body.contains("Note: this has a colon."))
    }

    @Test
    fun `an id is derived from the name, so the same name is always the same skill`() {
        val a = Skill(name = "Dismiss Popups!", description = "d", body = "b")
        val b = Skill(name = "dismiss   popups", description = "d", body = "b")

        assertEquals(a.id, b.id)
    }
}

class SkillLibraryTest {

    private fun skill(
        name: String,
        apps: List<String> = emptyList(),
        status: SkillStatus = SkillStatus.APPROVED,
    ) = Skill(name = name, description = "does $name", apps = apps, body = "steps for $name", status = status)

    @Test
    fun `the index carries the name, the description and the apps but not the body`() {
        val library = SkillLibrary(listOf(skill("Trim a video", apps = listOf("com.editor"))))

        val index = library.index()

        assertTrue(index, index.contains("Trim a video"))
        assertTrue(index, index.contains("does Trim a video"))
        assertTrue(index, index.contains("com.editor"))
        assertFalse("the body must not be in the index", index.contains("steps for Trim a video"))
    }

    @Test
    fun `a draft is never in the index`() {
        val library = SkillLibrary(listOf(skill("Unreviewed", status = SkillStatus.DRAFT)))

        assertFalse(library.index().contains("Unreviewed"))
        assertTrue(library.drafts().isNotEmpty())
    }

    @Test
    fun `a draft cannot be read`() {
        val library = SkillLibrary(listOf(skill("Unreviewed", status = SkillStatus.DRAFT)))

        val text = library.read("unreviewed")

        assertTrue(text, text.contains("has not been approved"))
        assertFalse("the body must not leak from a draft", text.contains("steps for Unreviewed"))
    }

    @Test
    fun `an archived skill is not offered`() {
        val library = SkillLibrary(listOf(skill("Old", status = SkillStatus.ARCHIVED)))

        assertTrue(library.usable().isEmpty())
        assertFalse(library.index().contains("Old"))
    }

    @Test
    fun `an approved skill can be read in full`() {
        val library = SkillLibrary(listOf(skill("Search")))

        val text = library.read("search")

        assertTrue(text, text.contains("steps for Search"))
    }

    @Test
    fun `reading an unknown skill explains rather than throwing`() {
        assertTrue(SkillLibrary().read("nope").contains("no skill"))
    }

    @Test
    fun `a skill with no apps applies everywhere`() {
        val library = SkillLibrary(listOf(skill("General")))

        assertTrue(library.indexFor("com.anything").contains("General"))
        assertTrue(library.indexFor(null).contains("General"))
    }

    @Test
    fun `a skill for one app is not offered in another`() {
        val library = SkillLibrary(listOf(skill("WhatsApp only", apps = listOf("com.whatsapp"))))

        assertTrue(library.indexFor("com.whatsapp").contains("WhatsApp only"))
        assertFalse(library.indexFor("com.other.app").contains("WhatsApp only"))
    }

    @Test
    fun `a wildcard app pattern matches a family of packages`() {
        val library = SkillLibrary(listOf(skill("Google apps", apps = listOf("com.google.*"))))

        assertTrue(library.indexFor("com.google.android.gm").contains("Google apps"))
        assertFalse(library.indexFor("com.whatsapp").contains("Google apps"))
    }

    @Test
    fun `an app-specific skill is not offered when the package is unknown`() {
        val library = SkillLibrary(listOf(skill("Specific", apps = listOf("com.whatsapp"))))

        assertFalse(library.indexFor(null).contains("Specific"))
    }

    @Test
    fun `the index says how to read a skill in full`() {
        val library = SkillLibrary(listOf(skill("Something")))

        assertTrue(library.indexFor("com.x").contains("load_skill"))
    }

    @Test
    fun `an empty library produces an empty index rather than a stray heading`() {
        assertEquals("", SkillLibrary().indexFor("com.x"))
        assertEquals("", SkillLibrary().index())
    }

    @Test
    fun `adding a skill with the same name replaces it`() {
        val library = SkillLibrary(listOf(skill("Same")))
        library.put(skill("Same").copy(body = "a better version"))

        assertEquals(1, library.all().size)
        assertTrue(library.read("same").contains("a better version"))
    }

    @Test
    fun `a skill can be removed`() {
        val library = SkillLibrary(listOf(skill("Temporary")))
        library.remove("temporary")

        assertTrue(library.all().isEmpty())
    }
}

class SkillDraftPolicyTest {

    @Test
    fun `a long successful task is worth drafting`() {
        val candidate = SkillDraftPolicy.assess(stepCount = 7, succeeded = true, planSummary = "did things")

        assertTrue(candidate.worthDrafting)
    }

    @Test
    fun `a short task is not worth drafting`() {
        val candidate = SkillDraftPolicy.assess(stepCount = 2, succeeded = true, planSummary = "did a thing")

        assertFalse(candidate.worthDrafting)
        assertTrue(candidate.reason.contains("too few"))
    }

    @Test
    fun `a task of exactly the minimum is worth drafting`() {
        assertTrue(
            SkillDraftPolicy.assess(
                stepCount = SkillDraftPolicy.MIN_STEPS,
                succeeded = true,
                planSummary = "did things",
            ).worthDrafting,
        )
    }

    @Test
    fun `a failed task is never worth drafting, however long`() {
        val candidate = SkillDraftPolicy.assess(stepCount = 30, succeeded = false, planSummary = "x")

        assertFalse("a failed run would teach the wrong thing", candidate.worthDrafting)
        assertTrue(candidate.reason.contains("did not finish"))
    }

    @Test
    fun `a task with nothing to describe is not worth drafting`() {
        assertFalse(
            SkillDraftPolicy.assess(stepCount = 6, succeeded = true, planSummary = "  ").worthDrafting,
        )
    }

    @Test
    fun `the drafting prompt asks for patterns, not coordinates`() {
        val prompt = SkillDraftPolicy.draftingPrompt("Set an alarm", "1. Opened the clock\n2. Set 7am")

        assertTrue(prompt.contains("Never include coordinates"))
        assertTrue(prompt.contains("Set an alarm"))
    }

    @Test
    fun `the drafting prompt asks for the front matter the parser expects`() {
        val prompt = SkillDraftPolicy.draftingPrompt("goal", "steps")

        assertTrue(prompt.contains("---"))
        assertTrue(prompt.contains("name:"))
        assertTrue(prompt.contains("description:"))
        assertTrue(prompt.contains("version:"))
        assertTrue(prompt.contains("status: draft"))
    }

    @Test
    fun `a reply to the drafting prompt would parse as a draft`() {
        val reply = """
            |---
            |name: Set an alarm
            |description: Using the clock app to set an alarm
            |apps: com.google.android.deskclock
            |version: 1
            |status: draft
            |---
            |
            |1. Open the clock app.
            |2. Choose the alarm tab.
            |3. Set the time and save.
        """.trimMargin()

        val parsed = SkillParser.parse(reply)

        assertNull(parsed.error)
        assertEquals(SkillStatus.DRAFT, parsed.skill!!.status)
    }
}

/**
 * The bundled skills are content, and content rots. These tests read the shipped files, so a
 * bad edit is caught here rather than on a phone.
 */
class BundledSkillsTest {

    private val directory = java.io.File("src/main/assets/skills")

    private fun files(): List<java.io.File> =
        directory.listFiles { file -> file.name.endsWith(".md") }?.sortedBy { it.name }.orEmpty()

    @Test
    fun `the bundled skills directory exists and has skills in it`() {
        assertTrue("the shipped skills are missing", files().isNotEmpty())
    }

    @Test
    fun `every bundled skill parses`() {
        files().forEach { file ->
            val parsed = SkillParser.parse(file.readText())
            assertNull("${file.name} did not parse: ${parsed.error}", parsed.error)
        }
    }

    @Test
    fun `every bundled skill is approved, because a shipped draft would be unusable`() {
        files().forEach { file ->
            assertEquals(
                "${file.name} must be approved",
                SkillStatus.APPROVED,
                SkillParser.parse(file.readText()).skill!!.status,
            )
        }
    }

    @Test
    fun `every bundled skill has a description, so the index is useful`() {
        files().forEach { file ->
            val skill = SkillParser.parse(file.readText()).skill!!
            assertTrue("${file.name} needs a description", skill.description.isNotBlank())
        }
    }

    @Test
    fun `no bundled skill contains a coordinate, which would only work on one phone`() {
        // The rule from the design: patterns, not coordinates.
        val coordinate = Regex("@?\\b\\d{2,4}\\s*,\\s*\\d{2,4}\\b")
        files().forEach { file ->
            val body = SkillParser.parse(file.readText()).skill!!.body
            assertFalse(
                "${file.name} contains something that looks like a coordinate",
                coordinate.containsMatchIn(body),
            )
        }
    }

    @Test
    fun `no bundled skill tells the model to tap Allow or Buy`() {
        val forbidden = listOf("tap Allow", "tap Buy", "tap Purchase", "tap Confirm")
        files().forEach { file ->
            val body = SkillParser.parse(file.readText()).skill!!.body
            forbidden.forEach { phrase ->
                assertFalse(
                    "${file.name} must not instruct $phrase",
                    body.contains(phrase, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the bundled skills cover the situations the design names`() {
        val names = files().map { SkillParser.parse(it.readText()).skill!!.name.lowercase() }

        assertTrue("popups", names.any { it.contains("popup") })
        assertTrue("forms", names.any { it.contains("form") })
        assertTrue("search", names.any { it.contains("search") })
        assertTrue("sharing media", names.any { it.contains("share") })
        assertTrue("a video editor", names.any { it.contains("video") })
    }

    @Test
    fun `the bundled skills form a usable library`() {
        val library = SkillLibrary(files().map { SkillParser.parse(it.readText()).skill!! })

        val index = library.indexFor("com.whatsapp")

        assertTrue(index.isNotEmpty())
        assertFalse("no body text may appear in the index", index.contains("1. "))
    }
}
