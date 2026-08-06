package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HeikinAshiTransformer]. Real instance, hand-computed HA values.
 */
class HeikinAshiTransformerTest {

    private lateinit var transformer: HeikinAshiTransformer

    @Before
    fun setup() {
        transformer = HeikinAshiTransformer()
    }

    private fun candle(ts: Long, o: Double, h: Double, l: Double, c: Double, v: Double = 0.0) =
        Candle(timestamp = ts, open = o, high = h, low = l, close = c, volume = v)

    @Test
    fun `empty input yields empty output`() {
        assertTrue(transformer.transform(emptyList()).isEmpty())
    }

    @Test
    fun `preserves size timestamps and volume`() {
        val src = listOf(
            candle(1L, 10.0, 12.0, 8.0, 11.0, v = 100.0),
            candle(2L, 11.0, 15.0, 10.0, 14.0, v = 200.0),
        )
        val ha = transformer.transform(src)

        assertEquals(2, ha.size)
        assertEquals(listOf(1L, 2L), ha.map { it.timestamp })
        assertEquals(listOf(100.0, 200.0), ha.map { it.volume })
    }

    @Test
    fun `computes Heikin-Ashi values from the standard formulas`() {
        val src = listOf(
            candle(1L, 10.0, 12.0, 8.0, 11.0),
            candle(2L, 11.0, 15.0, 10.0, 14.0),
        )
        val ha = transformer.transform(src)

        // Bar 0: haClose = (10+12+8+11)/4 = 10.25 ; haOpen = (10+11)/2 = 10.5
        //        haHigh = max(12,10.5,10.25) = 12 ; haLow = min(8,10.5,10.25) = 8
        assertEquals(10.25, ha[0].close, 1e-9)
        assertEquals(10.5, ha[0].open, 1e-9)
        assertEquals(12.0, ha[0].high, 1e-9)
        assertEquals(8.0, ha[0].low, 1e-9)

        // Bar 1: haClose = (11+15+10+14)/4 = 12.5
        //        haOpen = (prevHaOpen 10.5 + prevHaClose 10.25)/2 = 10.375
        //        haHigh = max(15,10.375,12.5) = 15 ; haLow = min(10,10.375,12.5) = 10
        assertEquals(12.5, ha[1].close, 1e-9)
        assertEquals(10.375, ha[1].open, 1e-9)
        assertEquals(15.0, ha[1].high, 1e-9)
        assertEquals(10.0, ha[1].low, 1e-9)
    }

    @Test
    fun `high is always at least max of open and close and low at most min`() {
        val src = listOf(
            candle(1L, 10.0, 12.0, 8.0, 11.0),
            candle(2L, 11.0, 15.0, 10.0, 14.0),
            candle(3L, 14.0, 14.5, 9.0, 9.5),
        )
        val ha = transformer.transform(src)
        for (c in ha) {
            assertTrue(c.high >= c.open && c.high >= c.close)
            assertTrue(c.low <= c.open && c.low <= c.close)
        }
    }
}
