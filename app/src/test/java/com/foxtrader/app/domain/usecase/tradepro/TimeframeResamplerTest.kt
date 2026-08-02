package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeframeResamplerTest {

    private val hourMillis = 3_600_000L

    /** Eight H1 bars with distinct highs/lows so max/min aggregation is verifiable. */
    private fun eightH1Bars(): List<Candle> = listOf(
        Candle(0 * hourMillis, 100.0, 105.0, 99.0, 103.0, 10.0),
        Candle(1 * hourMillis, 103.0, 107.0, 101.0, 104.0, 12.0),
        Candle(2 * hourMillis, 104.0, 106.0, 100.0, 102.0, 8.0),
        Candle(3 * hourMillis, 102.0, 108.0, 98.0, 106.0, 14.0),
        Candle(4 * hourMillis, 106.0, 110.0, 105.0, 109.0, 9.0),
        Candle(5 * hourMillis, 109.0, 112.0, 107.0, 108.0, 11.0),
        Candle(6 * hourMillis, 108.0, 109.0, 104.0, 105.0, 7.0),
        Candle(7 * hourMillis, 105.0, 111.0, 103.0, 110.0, 13.0),
    )

    @Test
    fun `empty input yields empty output`() {
        assertTrue(TimeframeResampler.resample(emptyList(), Timeframe.H4).isEmpty())
    }

    @Test
    fun `eight H1 bars roll up into two H4 bars with correct OHLCV`() {
        val h4 = TimeframeResampler.resample(eightH1Bars(), Timeframe.H4)

        assertEquals(2, h4.size)

        val first = h4[0]
        assertEquals(0L, first.timestamp)
        assertEquals(100.0, first.open, 0.0)          // first bar's open
        assertEquals(108.0, first.high, 0.0)          // max(105,107,106,108)
        assertEquals(98.0, first.low, 0.0)            // min(99,101,100,98)
        assertEquals(106.0, first.close, 0.0)         // fourth bar's close
        assertEquals(44.0, first.volume, 0.0)         // 10+12+8+14

        val second = h4[1]
        assertEquals(4 * hourMillis, second.timestamp) // bucket start = 4h
        assertEquals(106.0, second.open, 0.0)
        assertEquals(112.0, second.high, 0.0)          // max(110,112,109,111)
        assertEquals(103.0, second.low, 0.0)           // min(105,107,104,103)
        assertEquals(110.0, second.close, 0.0)         // eighth bar's close
        assertEquals(40.0, second.volume, 0.0)         // 9+11+7+13
    }

    @Test
    fun `four M15 bars roll up into a single H1 bar`() {
        val quarter = 15 * 60_000L
        val m15 = listOf(
            Candle(0 * quarter, 10.0, 12.0, 9.0, 11.0, 1.0),
            Candle(1 * quarter, 11.0, 13.0, 10.0, 12.0, 1.0),
            Candle(2 * quarter, 12.0, 14.0, 11.0, 13.0, 1.0),
            Candle(3 * quarter, 13.0, 15.0, 12.0, 14.0, 1.0),
        )
        val h1 = TimeframeResampler.resample(m15, Timeframe.H1)
        assertEquals(1, h1.size)
        assertEquals(10.0, h1[0].open, 0.0)
        assertEquals(15.0, h1[0].high, 0.0)
        assertEquals(9.0, h1[0].low, 0.0)
        assertEquals(14.0, h1[0].close, 0.0)
        assertEquals(4.0, h1[0].volume, 0.0)
    }

    @Test
    fun `bars spanning less than one target bucket collapse into one bar`() {
        // Eight H1 bars all fall inside the same calendar day -> one D1 bar.
        val d1 = TimeframeResampler.resample(eightH1Bars(), Timeframe.D1)
        assertEquals(1, d1.size)
        assertEquals(100.0, d1[0].open, 0.0)
        assertEquals(112.0, d1[0].high, 0.0)
        assertEquals(98.0, d1[0].low, 0.0)
        assertEquals(110.0, d1[0].close, 0.0)
        assertEquals(107.0, d1[0].volume, 0.0) // sum of all eight volumes
    }
}
