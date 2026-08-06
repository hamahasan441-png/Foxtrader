package com.foxtrader.app.domain.usecase.tick

import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TickAggregator].
 * Uses a real aggregator instance with synthetic ticks whose OHLCV values are
 * computed by hand from the mid price ((bid + ask) / 2).
 */
class TickAggregatorTest {

    private lateinit var aggregator: TickAggregator

    @Before
    fun setup() {
        aggregator = TickAggregator()
    }

    @Test
    fun `aggregate returns empty for empty input`() {
        val result = aggregator.aggregate(emptyList(), Timeframe.M1)
        assertTrue("Empty ticks should yield empty candles", result.isEmpty())
    }

    @Test
    fun `single tick produces one candle with OHLC all equal to mid`() {
        // mid = (10 + 12) / 2 = 11.0 ; bucket = floor(1000/60000)*60000 = 0
        val ticks = listOf(Tick(timestampMs = 1_000L, bid = 10.0, ask = 12.0))
        val result = aggregator.aggregate(ticks, Timeframe.M1)

        assertEquals(1, result.size)
        val c = result.first()
        assertEquals(0L, c.timestamp)
        assertEquals(11.0, c.open, 0.0001)
        assertEquals(11.0, c.high, 0.0001)
        assertEquals(11.0, c.low, 0.0001)
        assertEquals(11.0, c.close, 0.0001)
    }

    @Test
    fun `multi tick OHLC computed from mid prices`() {
        // All four ticks fall in bucket 0 (ts < 60000). Mids: 11, 15, 9, 13.
        val ticks = listOf(
            Tick(timestampMs = 1_000L, bid = 10.0, ask = 12.0), // mid 11 (open, earliest)
            Tick(timestampMs = 2_000L, bid = 14.0, ask = 16.0), // mid 15 (high)
            Tick(timestampMs = 3_000L, bid = 8.0, ask = 10.0),  // mid 9  (low)
            Tick(timestampMs = 4_000L, bid = 12.0, ask = 14.0), // mid 13 (close, latest)
        )
        val result = aggregator.aggregate(ticks, Timeframe.M1)

        assertEquals(1, result.size)
        val c = result.first()
        assertEquals(11.0, c.open, 0.0001)
        assertEquals(15.0, c.high, 0.0001)
        assertEquals(9.0, c.low, 0.0001)
        assertEquals(13.0, c.close, 0.0001)
    }

    @Test
    fun `ticks split across two buckets produce two candles`() {
        // ts 1000 -> bucket 0 ; ts 61000 -> bucket 60000
        val ticks = listOf(
            Tick(timestampMs = 1_000L, bid = 10.0, ask = 12.0),   // mid 11 in bucket 0
            Tick(timestampMs = 61_000L, bid = 20.0, ask = 22.0),  // mid 21 in bucket 60000
        )
        val result = aggregator.aggregate(ticks, Timeframe.M1)

        assertEquals(2, result.size)
        assertEquals(0L, result[0].timestamp)
        assertEquals(11.0, result[0].close, 0.0001)
        assertEquals(60_000L, result[1].timestamp)
        assertEquals(21.0, result[1].close, 0.0001)
    }

    @Test
    fun `volume is the sum of bid and ask volume within a bucket`() {
        // Volumes: (1+2) + (1+1) + (2+0) + (1+1) = 3 + 2 + 2 + 2 = 9
        val ticks = listOf(
            Tick(1_000L, 10.0, 12.0, bidVolume = 1.0, askVolume = 2.0),
            Tick(2_000L, 14.0, 16.0, bidVolume = 1.0, askVolume = 1.0),
            Tick(3_000L, 8.0, 10.0, bidVolume = 2.0, askVolume = 0.0),
            Tick(4_000L, 12.0, 14.0, bidVolume = 1.0, askVolume = 1.0),
        )
        val result = aggregator.aggregate(ticks, Timeframe.M1)

        assertEquals(1, result.size)
        assertEquals(9.0, result.first().volume, 0.0001)
    }

    @Test
    fun `output is sorted ascending by candle open time regardless of input order`() {
        // Ticks provided out of chronological order; buckets: 120000, 0, 60000.
        val ticks = listOf(
            Tick(timestampMs = 121_000L, bid = 30.0, ask = 32.0),
            Tick(timestampMs = 1_000L, bid = 10.0, ask = 12.0),
            Tick(timestampMs = 61_000L, bid = 20.0, ask = 22.0),
        )
        val result = aggregator.aggregate(ticks, Timeframe.M1)

        assertEquals(3, result.size)
        val timestamps = result.map { it.timestamp }
        assertEquals(listOf(0L, 60_000L, 120_000L), timestamps)
        // Confirm strictly ascending as an invariant.
        for (i in 1 until timestamps.size) {
            assertTrue("Candle timestamps must be ascending", timestamps[i] > timestamps[i - 1])
        }
    }
}
