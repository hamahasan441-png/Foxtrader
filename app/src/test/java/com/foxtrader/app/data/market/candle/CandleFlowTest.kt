package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Flow operator must forward only sealed bars (isBarClose = true), preserve
 * bucket state across emissions, and flush the final forming bucket when the
 * upstream completes.
 */
class CandleFlowTest {

    private fun tick(ts: Long, price: Double = 100.0) = Tick("BTCUSDT", price, 1.0, ts)

    private fun monday(): Long =
        LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `emits sealed candles and flushes the final bucket`() = runTest {
        val start = monday()
        val ticks = listOf(
            tick(start),               // opens bucket 0
            tick(start + 30_000, 101.0),
            tick(start + 60_000, 102.0), // seals bucket 0, opens bucket 1
            tick(start + 90_000, 103.0),
        )

        val updates = ticks.asFlow()
            .buildCandles(listOf(MarketTimeframe.M1))
            .toList()

        assertEquals(2, updates.size)
        assertTrue(updates.all { it.isBarClose })
        assertEquals(start, updates[0].candle.timestamp)
        assertEquals(100.0, updates[0].candle.open, 0.0)
        assertEquals(101.0, updates[0].candle.close, 0.0)
        assertEquals(start + 60_000, updates[1].candle.timestamp)
        assertEquals(103.0, updates[1].candle.close, 0.0)
    }

    @Test
    fun `an empty upstream emits nothing`() = runTest {
        val updates = emptyList<Tick>().asFlow()
            .buildCandles(MarketTimeframe.ALL)
            .toList()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `multi-timeframe flow closes bars at the right boundaries`() = runTest {
        val start = monday()
        // One tick per minute for minutes 0..4 (five ticks, all inside one M5 bar).
        val ticks = (0L until 5L).map { minute -> tick(start + minute * 60_000L, 100.0 + minute) }

        val updates = ticks.asFlow()
            .buildCandles(listOf(MarketTimeframe.M1, MarketTimeframe.M5))
            .toList()

        val m1 = updates.filter { it.timeframe == MarketTimeframe.M1 }
        val m5 = updates.filter { it.timeframe == MarketTimeframe.M5 }
        // M1: buckets 0..4; 0..3 sealed by crossings, 4 flushed => 5 bars.
        assertEquals(5, m1.size)
        // M5: all five ticks fall in one 5-minute bucket, flushed at end of stream.
        assertEquals(1, m5.size)
        assertEquals(start, m5[0].candle.timestamp)
    }
}
