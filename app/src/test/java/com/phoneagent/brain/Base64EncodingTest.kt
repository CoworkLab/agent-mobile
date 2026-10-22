package com.phoneagent.brain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The base64 encoder is hand-written so it runs in a JVM test, so it is checked against
 * known-good vectors. A wrong image encoding would fail at the provider with a confusing
 * 400, which is exactly the kind of bug that is expensive to find later.
 */
class Base64EncodingTest {

    private fun decodeAscii(bytes: ByteArray): String = ChatCompletionsBody.encode(bytes)

    @Test
    fun `empty input encodes to nothing`() {
        assertEquals("", decodeAscii(ByteArray(0)))
    }

    @Test
    fun `one byte is padded with two equals signs`() {
        assertEquals("QQ==", decodeAscii("A".toByteArray()))
    }

    @Test
    fun `two bytes are padded with one equals sign`() {
        assertEquals("QUI=", decodeAscii("AB".toByteArray()))
    }

    @Test
    fun `three bytes need no padding`() {
        assertEquals("QUJD", decodeAscii("ABC".toByteArray()))
    }

    @Test
    fun `the classic vectors are correct`() {
        assertEquals("", decodeAscii("".toByteArray()))
        assertEquals("Zg==", decodeAscii("f".toByteArray()))
        assertEquals("Zm8=", decodeAscii("fo".toByteArray()))
        assertEquals("Zm9v", decodeAscii("foo".toByteArray()))
        assertEquals("Zm9vYg==", decodeAscii("foob".toByteArray()))
        assertEquals("Zm9vYmE=", decodeAscii("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", decodeAscii("foobar".toByteArray()))
    }

    @Test
    fun `bytes above 127 are not sign-extended`() {
        // A negative byte must contribute its unsigned value, or every JPEG breaks.
        assertEquals("/w==", decodeAscii(byteArrayOf(0xFF.toByte())))
        assertEquals("/4A=", decodeAscii(byteArrayOf(0xFF.toByte(), 0x80.toByte())))
        assertEquals("gA==", decodeAscii(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `a JPEG magic header encodes as expected`() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        )
        assertEquals("/9j/4AAQSkZJRgAB", decodeAscii(jpegHeader))
    }

    @Test
    fun `the output length is always a multiple of four`() {
        for (size in 0..40) {
            val encoded = decodeAscii(ByteArray(size) { 7 })
            assertEquals("size $size", 0, encoded.length % 4)
        }
    }

    @Test
    fun `the output contains no line breaks`() {
        val encoded = decodeAscii(ByteArray(300) { (it % 256).toByte() })
        assertEquals(false, encoded.contains('\n'))
        assertEquals(false, encoded.contains('\r'))
    }

    @Test
    fun `only the last group is padded`() {
        for (size in 1..40) {
            val encoded = decodeAscii(ByteArray(size) { 1 })
            assertEquals(
                "padding must not appear before the end for size $size",
                false,
                encoded.dropLast(2).contains('='),
            )
        }
    }
}
