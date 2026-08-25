package com.foxtrader.app.data.remote.dukascopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [DukascopyTickDecoder].
 * Builds byte arrays with a BIG_ENDIAN [ByteBuffer] holding known records and
 * asserts the exact decoded [com.foxtrader.app.domain.model.Tick] values.
 */
class DukascopyTickDecoderTest {

    private lateinit var decoder: DukascopyTickDecoder

    @Before
    fun setup() {
        decoder = DukascopyTickDecoder()
    }

    /**
     * Encode [count] records into a BIG_ENDIAN buffer.
     * Record layout: int32 msOffset, int32 askPoints, int32 bidPoints,
     * float32 askVol, float32 bidVol (20 bytes each).
     */
    private fun encode(
        records: List<IntArray>,      // each: [msOffset, askPoints, bidPoints]
        volumes: List<FloatArray>,    // each: [askVol, bidVol]
        trailingBytes: Int = 0,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(records.size * 20 + trailingBytes)
            .order(ByteOrder.BIG_ENDIAN)
        for (i in records.indices) {
            buffer.putInt(records[i][0])
            buffer.putInt(records[i][1])
            buffer.putInt(records[i][2])
            buffer.putFloat(volumes[i][0])
            buffer.putFloat(volumes[i][1])
        }
        // Any trailing bytes remain zero-filled — simulating a partial record.
        return buffer.array()
    }

    @Test
    fun `decode returns empty for empty input`() {
        val result = decoder.decode(ByteArray(0), hourStartMs = 0L, pointValue = 100_000.0)
        assertTrue("Empty payload should decode to no ticks", result.isEmpty())
    }

    @Test
    fun `decode returns empty when payload shorter than one record`() {
        val result = decoder.decode(ByteArray(19), hourStartMs = 0L, pointValue = 100_000.0)
        assertTrue("A payload under 20 bytes yields no ticks", result.isEmpty())
    }

    @Test
    fun `decode one record produces exact tick`() {
        val payload = encode(
            records = listOf(intArrayOf(500, 110_000, 109_000)),
            volumes = listOf(floatArrayOf(1.5f, 2.5f)),
        )

        val ticks = decoder.decode(payload, hourStartMs = 1_000_000L, pointValue = 100_000.0)

        assertEquals(1, ticks.size)
        val t = ticks.first()
        assertEquals(1_000_500L, t.timestampMs)      // hourStartMs + msOffset
        assertEquals(1.10, t.ask, 0.0000001)         // 110000 / 100000
        assertEquals(1.09, t.bid, 0.0000001)         // 109000 / 100000
        assertEquals(1.5, t.askVolume, 0.0001)
        assertEquals(2.5, t.bidVolume, 0.0001)
    }

    @Test
    fun `decode ignores trailing partial record`() {
        // One full 20-byte record followed by 5 stray bytes.
        val payload = encode(
            records = listOf(intArrayOf(0, 200_000, 199_000)),
            volumes = listOf(floatArrayOf(3.0f, 4.0f)),
            trailingBytes = 5,
        )

        val ticks = decoder.decode(payload, hourStartMs = 0L, pointValue = 100_000.0)

        assertEquals("Trailing partial record must be ignored", 1, ticks.size)
        assertEquals(2.0, ticks.first().ask, 0.0000001)   // 200000 / 100000
        assertEquals(1.99, ticks.first().bid, 0.0000001)  // 199000 / 100000
    }

    @Test
    fun `decode applies pointValue scaling`() {
        val payload = encode(
            records = listOf(intArrayOf(0, 110_000, 109_000)),
            volumes = listOf(floatArrayOf(0.0f, 0.0f)),
        )

        // pointValue 1000 -> larger real prices than the 5-digit FX case.
        val ticks = decoder.decode(payload, hourStartMs = 0L, pointValue = 1_000.0)

        assertEquals(1, ticks.size)
        assertEquals(110.0, ticks.first().ask, 0.0000001)   // 110000 / 1000
        assertEquals(109.0, ticks.first().bid, 0.0000001)   // 109000 / 1000
    }

    @Test
    fun `decode multiple records preserves order and offsets`() {
        val payload = encode(
            records = listOf(
                intArrayOf(100, 110_000, 109_000),
                intArrayOf(250, 111_000, 110_000),
            ),
            volumes = listOf(
                floatArrayOf(1.0f, 1.0f),
                floatArrayOf(2.0f, 2.0f),
            ),
        )

        val ticks = decoder.decode(payload, hourStartMs = 500L, pointValue = 100_000.0)

        assertEquals(2, ticks.size)
        assertEquals(600L, ticks[0].timestampMs)   // 500 + 100
        assertEquals(750L, ticks[1].timestampMs)   // 500 + 250
        assertEquals(1.11, ticks[1].ask, 0.0000001)
    }

    @Test
    fun `decode rejects corrupt time price spread and volume records`() {
        val payload = encode(
            records = listOf(
                intArrayOf(-1, 110_000, 109_000),
                intArrayOf(100, 109_000, 110_000),
                intArrayOf(200, 111_000, 110_000),
                intArrayOf(150, 112_000, 111_000),
            ),
            volumes = listOf(
                floatArrayOf(1.0f, 1.0f),
                floatArrayOf(1.0f, 1.0f),
                floatArrayOf(2.0f, 2.0f),
                floatArrayOf(1.0f, 1.0f),
            ),
        )

        val ticks = decoder.decode(payload, hourStartMs = 1_000L, pointValue = 100_000.0)

        assertEquals(1, ticks.size)
        assertEquals(1_200L, ticks.single().timestampMs)
    }

    @Test
    fun `decode rejects invalid price divisor`() {
        val payload = encode(
            records = listOf(intArrayOf(0, 110_000, 109_000)),
            volumes = listOf(floatArrayOf(1.0f, 1.0f)),
        )
        assertTrue(decoder.decode(payload, 0L, 0.0).isEmpty())
        assertTrue(decoder.decode(payload, 0L, Double.NaN).isEmpty())
    }
}
