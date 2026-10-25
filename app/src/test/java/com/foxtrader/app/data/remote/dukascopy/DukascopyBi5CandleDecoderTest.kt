package com.foxtrader.app.data.remote.dukascopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DukascopyBi5CandleDecoderTest {
    private val decoder = DukascopyBi5CandleDecoder()

    @Test
    fun `decodes one big-endian candle record onto the bucket open`() {
        val payload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(60)
            putInt(110_000)
            putInt(110_010)
            putInt(109_990)
            putInt(110_020)
            putFloat(0.00015f)
        }.array()

        val candles = decoder.decode(payload, bucketStartMs = 1_700_000_000_000L, pointValue = 100_000.0)

        assertEquals(1, candles.size)
        assertEquals(1_700_000_060_000L, candles[0].timestamp)
        assertEquals(1.10000, candles[0].open, 1e-9)
        assertEquals(1.10020, candles[0].high, 1e-9)
        assertEquals(1.09990, candles[0].low, 1e-9)
        assertEquals(1.10010, candles[0].close, 1e-9)
        assertEquals(1_500.0, candles[0].volume, 1.0)
    }

    @Test
    fun `inverted or truncated records are dropped instead of crashing`() {
        assertTrue(decoder.decode(ByteArray(23), 1L, 100_000.0).isEmpty())
        val inverted = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(10)
            putInt(10)
            putInt(12)
            putInt(9)
            putFloat(1f)
        }.array()
        assertTrue(decoder.decode(inverted, 1L, 100_000.0).isEmpty())
    }
}
