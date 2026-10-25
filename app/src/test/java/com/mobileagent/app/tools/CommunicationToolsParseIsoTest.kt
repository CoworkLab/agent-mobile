package com.mobileagent.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CommunicationToolsParseIsoTest {

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): String {
        val c = Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }
        val pad = { n: Int -> n.toString().padStart(2, '0') }
        return "${c.get(Calendar.YEAR)}-${pad(mo)}-${pad(d)}T${pad(h)}:${pad(mi)}:${pad(s)}"
    }

    @Test
    fun parsesIsoWithSeconds() {
        val c = CommunicationTools.parseIso("2026-01-15T09:30:00")
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, c.get(Calendar.MONTH))
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test
    fun parsesSpaceSeparatedAndMinuteOnlyForms() {
        assertEquals(
            CommunicationTools.parseIso("2026-03-01T14:05:00").timeInMillis,
            CommunicationTools.parseIso("2026-03-01 14:05").timeInMillis
        )
        assertEquals(
            CommunicationTools.parseIso("2026-03-01T14:05:00").timeInMillis,
            CommunicationTools.parseIso("2026-03-01 14:05:00").timeInMillis
        )
    }

    @Test
    fun parsesUtcSuffixAsUtcNotLocalShiftedGarbage() {
        // "Z" means UTC; in a UTC-default JVM the wall clock must stay identical.
        val c = CommunicationTools.parseIso("2026-06-01T12:00:00Z")
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
    }

    @Test
    fun rejectsGarbageWithActionableError() {
        val e = assertThrows(ToolException::class.java) {
            CommunicationTools.parseIso("next Tuesday evening")
        }
        assertTrue(e.message!!.contains("ISO"))
    }
}
