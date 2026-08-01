package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.BarMode
import com.foxtrader.app.domain.model.tradepro.BarSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversalRangeBarBuilderTest {

    private val builder = ReversalRangeBarBuilder(CandleSanitizer())

    /** Turn a list of closes into candles (each candle's OHLC centred on the close). */
    private fun fromCloses(closes: List<Double>): List<Candle> = closes.mapIndexed { i, c ->
        Candle(timestamp = i * 60_000L, open = c, high = c, low = c, close = c, volume = 10.0)
    }

    // size = 16 ticks * 0.25 = 4.0
    private val rangeSpec = BarSpec(BarMode.RANGE, ticks = 16, tickSize = 0.25)
    private val reversalSpec = BarSpec(BarMode.REVERSAL, ticks = 16, tickSize = 0.25)

    @Test
    fun `time mode returns the sanitized series unchanged`() {
        val candles = fromCloses(listOf(100.0, 101.0, 102.0))
        val out = builder.build(candles, BarSpec(BarMode.TIME))
        assertEquals(3, out.size)
    }

    @Test
    fun `range bars close each time price travels the threshold`() {
        // up 100->104 completes bar 1; down 104->100 completes bar 2.
        val candles = fromCloses(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 103.0, 102.0, 101.0, 100.0))
        val out = builder.build(candles, rangeSpec)
        assertEquals(2, out.size)
        assertEquals(100.0, out[0].open, 1e-9)
        assertEquals(104.0, out[0].close, 1e-9)
        assertEquals(104.0, out[1].open, 1e-9)
        assertEquals(100.0, out[1].close, 1e-9)
    }

    @Test
    fun `an unfinished range move still emits a trailing bar`() {
        val candles = fromCloses(listOf(100.0, 101.0, 102.0, 103.0)) // only 3 pts of range
        val out = builder.build(candles, rangeSpec)
        assertEquals(1, out.size)
        assertEquals(103.0, out[0].high, 1e-9)
        assertEquals(100.0, out[0].low, 1e-9)
    }

    @Test
    fun `reversal bar prints when price retraces the threshold from the extreme`() {
        // rally to 105, then a 4-pt reversal to 101 closes the up bar.
        val candles = fromCloses(listOf(100.0, 102.0, 104.0, 105.0, 101.0, 100.0))
        val out = builder.build(candles, reversalSpec)
        assertEquals(2, out.size)
        assertEquals(105.0, out[0].high, 1e-9)
        assertEquals(101.0, out[0].close, 1e-9)
        assertEquals(101.0, out[1].open, 1e-9)
    }

    @Test
    fun `builder sanitizes first so bad bars never corrupt output`() {
        val candles = listOf(
            Candle(0, 100.0, 100.0, 100.0, 100.0, 10.0),
            Candle(60_000, Double.NaN, 1.0, 1.0, 1.0, 10.0), // dropped by sanitizer
            Candle(120_000, 104.0, 104.0, 104.0, 104.0, 10.0),
        )
        val out = builder.build(candles, rangeSpec)
        // Two valid closes (100, 104) span exactly the 4-pt threshold -> one bar, no crash.
        assertTrue(out.isNotEmpty())
        out.forEach { assertTrue(it.high >= it.low) }
    }

    @Test
    fun `too-few bars returns the sanitized input`() {
        assertEquals(1, builder.build(fromCloses(listOf(100.0)), rangeSpec).size)
        assertTrue(builder.build(emptyList(), rangeSpec).isEmpty())
    }
}
