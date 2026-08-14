package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No-repaint tests for Polygon minute-to-chart-timeframe aggregation.
 * Forming bars may update, but a sealed bar must be emitted once and never
 * replaced by a late minute or an invented gap candle.
 */
class PolygonCandleAggregatorTest {

    @Test
    fun `M1 repeated provider updates replace cumulative volume`() {
        val aggregator = PolygonCandleAggregator()

        aggregator.update("AAPL", Timeframe.M1, candle(60_000, 100.0, 101.0, 99.0, 100.5, 100.0))
        val update = aggregator.update(
            "AAPL",
            Timeframe.M1,
            candle(60_000, 100.0, 102.0, 98.0, 101.5, 125.0),
        ).single()

        assertEquals(102.0, update.candle.high, 0.0)
        assertEquals(98.0, update.candle.low, 0.0)
        assertEquals(101.5, update.candle.close, 0.0)
        assertEquals(125.0, update.candle.volume, 0.0)
        assertTrue(!update.isBarClose)
    }

    @Test
    fun `H1 aggregates minute bars and seals on the next bucket`() {
        val aggregator = PolygonCandleAggregator()
        val start = 3_600_000L

        aggregator.update("AAPL", Timeframe.H1, candle(start, 100.0, 101.0, 99.0, 100.5, 10.0))
        aggregator.update(
            "AAPL",
            Timeframe.H1,
            candle(start + 60_000L, 100.5, 103.0, 100.0, 102.0, 5.0),
        )
        // The second minute is sent again with cumulative volume 8, so only 3
        // additional units belong in the H1 bucket.
        val forming = aggregator.update(
            "AAPL",
            Timeframe.H1,
            candle(start + 60_000L, 100.5, 104.0, 99.5, 103.0, 8.0),
        ).single()

        assertEquals(104.0, forming.candle.high, 0.0)
        assertEquals(99.0, forming.candle.low, 0.0)
        assertEquals(103.0, forming.candle.close, 0.0)
        assertEquals(18.0, forming.candle.volume, 0.0)

        val nextBucket = aggregator.update(
            "AAPL",
            Timeframe.H1,
            candle(start + 3_600_000L, 103.0, 105.0, 102.0, 104.0, 2.0),
        )

        assertEquals(2, nextBucket.size)
        assertTrue(nextBucket[0].isBarClose)
        assertEquals(103.0, nextBucket[0].candle.close, 0.0)
        assertTrue(!nextBucket[1].isBarClose)
        assertEquals(start + 3_600_000L, nextBucket[1].candle.timestamp)
    }

    @Test
    fun `late minute and missing buckets do not repaint or fabricate`() {
        val aggregator = PolygonCandleAggregator()
        val start = 3_600_000L

        aggregator.update("EURUSD", Timeframe.H1, candle(start, 1.1, 1.2, 1.0, 1.15, 1.0))
        val next = aggregator.update(
            "EURUSD",
            Timeframe.H1,
            candle(start + 3 * 3_600_000L, 1.15, 1.3, 1.1, 1.25, 1.0),
        )
        val late = aggregator.update(
            "EURUSD",
            Timeframe.H1,
            candle(start - 60_000L, 1.0, 1.2, 0.9, 1.1, 1.0),
        )

        assertEquals(2, next.size)
        assertEquals(1, next.count { it.isBarClose })
        assertEquals("only the prior and current real buckets are emitted", 0, late.size)
    }

    @Test
    fun `daily bucket is UTC aligned`() {
        val aggregator = PolygonCandleAggregator()
        val timestamp = java.time.Instant.parse("2026-08-14T15:30:00Z").toEpochMilli()

        val update = aggregator.update(
            "AAPL",
            Timeframe.D1,
            candle(timestamp, 100.0, 101.0, 99.0, 100.5, 1.0),
        ).single()

        assertEquals(
            java.time.Instant.parse("2026-08-14T00:00:00Z").toEpochMilli(),
            update.candle.timestamp,
        )
    }

    private fun candle(
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
    ) = Candle(timestamp, open, high, low, close, volume)
}
