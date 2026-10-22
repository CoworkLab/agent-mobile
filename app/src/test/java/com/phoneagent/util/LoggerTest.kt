package com.phoneagent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {

    @Test
    fun `entries are kept in order and bounded by capacity`() {
        val logger = Logger(capacity = 3, clock = { 0L })
        repeat(5) { logger.i("T", "msg$it") }

        val entries = logger.snapshot()
        assertEquals(3, entries.size)
        assertEquals(listOf("msg2", "msg3", "msg4"), entries.map { it.message })
    }

    @Test
    fun `minLevel filters out lower levels`() {
        val logger = Logger(clock = { 0L })
        logger.d("T", "debug line")
        logger.minLevel = LogLevel.WARN
        logger.d("T", "hidden")
        logger.w("T", "warn line")

        val messages = logger.snapshot().map { it.message }
        assertEquals(listOf("debug line", "warn line"), messages)
    }

    @Test
    fun `sink receives every entry that passes the filter`() {
        val seen = mutableListOf<String>()
        val logger = Logger(clock = { 0L }, sink = { seen.add(it.message) })
        logger.i("T", "one")
        logger.i("T", "two")

        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun `export renders every entry on its own line`() {
        val logger = Logger(clock = { 0L })
        logger.i("TagA", "first")
        logger.e("TagB", "second", IllegalStateException("boom"))

        val text = logger.export()
        assertTrue(text.contains("TagA: first"))
        assertTrue(text.contains("TagB: second"))
        assertTrue(text.contains("IllegalStateException: boom"))
    }

    @Test
    fun `clear empties the buffer`() {
        val logger = Logger(clock = { 0L })
        logger.i("T", "x")
        logger.clear()
        assertEquals(0, logger.snapshot().size)
    }

    @Test
    fun `secrets are masked before they are stored`() {
        val logger = Logger(clock = { 0L })
        logger.i("T", "using key AIzaSyDxxxxxxxxxxxxxxxxxxxxxxxxxxx now")
        logger.i("T", "Authorization: Bearer abcdef1234567890xyz")

        val text = logger.export()
        assertTrue("google key must be masked", !text.contains("AIzaSyDxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertTrue("bearer token must be masked", !text.contains("abcdef1234567890xyz"))
        assertTrue(text.contains("***"))
    }
}
