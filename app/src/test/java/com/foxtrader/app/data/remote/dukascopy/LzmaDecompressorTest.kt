package com.foxtrader.app.data.remote.dukascopy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [LzmaDecompressor].
 */
class LzmaDecompressorTest {

    private lateinit var decompressor: LzmaDecompressor

    @Before
    fun setup() {
        decompressor = LzmaDecompressor()
    }

    @Test
    fun `decompress returns empty for empty input`() {
        val result = decompressor.decompress(ByteArray(0))
        assertEquals(0, result.size)
    }

    @Test
    fun `decompress returns empty for input smaller than header size`() {
        val result = decompressor.decompress(ByteArray(12))
        assertEquals(0, result.size)
    }

    @Test
    fun `decompress handles corrupted stream gracefully without throwing`() {
        val badPayload = ByteArray(30) { 0x5D.toByte() }
        val result = decompressor.decompress(badPayload)
        // Must not crash, returns byte array
        assertTrue(result.isEmpty() || result.isNotEmpty())
    }

    @Test
    fun `decompress rejects oversized declared output before allocation`() {
        val payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x5D.toByte())
            putInt(1 shl 23)
            putLong(64L * 1024L * 1024L)
        }.array()

        assertTrue(decompressor.decompress(payload).isEmpty())
    }
}
