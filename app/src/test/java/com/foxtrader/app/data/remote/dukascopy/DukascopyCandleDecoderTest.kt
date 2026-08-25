package com.foxtrader.app.data.remote.dukascopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DukascopyCandleDecoderTest {
    private val decoder = DukascopyCandleDecoder()

    @Test
    fun `decodes delta candle columns without fabricating gap bars`() {
        val payload = """
            {
              "timestamp":1000000,
              "multiplier":0.00001,
              "shift":60000,
              "open":1.10000,
              "high":1.10020,
              "low":1.09980,
              "close":1.10010,
              "times":[0,2],
              "opens":[0,10],
              "highs":[0,10],
              "lows":[0,10],
              "closes":[0,10],
              "volumes":[12.5,14.0]
            }
        """.trimIndent()

        val candles = decoder.decode(payload)

        assertEquals(2, candles.size)
        assertEquals(1_000_000L, candles[0].timestamp)
        assertEquals(1_120_000L, candles[1].timestamp)
        assertEquals(1.10010, candles[1].open, 1e-9)
        assertEquals(1.10030, candles[1].high, 1e-9)
        assertEquals(1.10020, candles[1].close, 1e-9)
        assertEquals(14.0, candles[1].volume, 1e-9)
    }

    @Test
    fun `empty provider bucket decodes to empty`() {
        val payload = """{"timestamp":1000000,"multiplier":0.00001,"shift":60000,"times":[],"opens":[],"highs":[],"lows":[],"closes":[],"volumes":[]}"""
        assertTrue(decoder.decode(payload).isEmpty())
    }

    @Test
    fun `mismatched provider columns fail closed`() {
        val payload = """{"timestamp":1000000,"multiplier":0.00001,"shift":60000,"open":1.1,"high":1.2,"low":1.0,"close":1.1,"times":[0],"opens":[],"highs":[0],"lows":[0],"closes":[0],"volumes":[1]}"""
        var failed = false
        try {
            decoder.decode(payload)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
