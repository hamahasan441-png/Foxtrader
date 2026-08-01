package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleSanitizerTest {

    private val sanitizer = CandleSanitizer()

    private fun c(ts: Long, o: Double, h: Double, l: Double, cl: Double, v: Double = 100.0) =
        Candle(ts, o, h, l, cl, v)

    @Test
    fun `well-formed series passes through`() {
        val input = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(60_000, 100.5, 102.0, 100.0, 101.5),
            c(120_000, 101.5, 103.0, 101.0, 102.5),
        )
        assertEquals(3, sanitizer.sanitize(input).size)
    }

    @Test
    fun `non-finite bars are dropped`() {
        val input = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(60_000, Double.NaN, 101.0, 99.0, 100.5),
            c(120_000, 100.0, Double.POSITIVE_INFINITY, 99.0, 100.5),
            c(180_000, 100.0, 101.0, 99.0, 100.5),
        )
        assertEquals(2, sanitizer.sanitize(input).size)
    }

    @Test
    fun `non-positive prices are dropped`() {
        val input = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(60_000, -1.0, 101.0, 99.0, 100.5),
            c(120_000, 100.0, 101.0, 0.0, 100.5),
        )
        assertEquals(1, sanitizer.sanitize(input).size)
    }

    @Test
    fun `duplicate or out-of-order timestamps are dropped`() {
        val input = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(0, 100.0, 101.0, 99.0, 100.5), // duplicate ts
            c(30_000, 100.0, 101.0, 99.0, 100.5),
            c(10_000, 100.0, 101.0, 99.0, 100.5), // out of order
        )
        val out = sanitizer.sanitize(input)
        assertEquals(2, out.size)
        assertTrue(out[0].timestamp < out[1].timestamp)
    }

    @Test
    fun `inverted high-low is repaired`() {
        val out = sanitizer.sanitize(listOf(c(0, 100.0, 99.0, 101.0, 100.0)))
        assertEquals(1, out.size)
        assertTrue("high must be >= low", out[0].high >= out[0].low)
        assertEquals(100.0, out[0].high, 1e-9)
        assertEquals(100.0, out[0].low, 1e-9)
    }

    @Test
    fun `gaps larger than the interval are counted`() {
        val input = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(60_000, 100.0, 101.0, 99.0, 100.5),
            c(120_000, 100.0, 101.0, 99.0, 100.5),
            c(600_000, 100.0, 101.0, 99.0, 100.5), // 8-minute jump on a 1m timeframe
        )
        assertEquals(1, sanitizer.countGaps(input, Timeframe.M1, factor = 2.0))
    }

    @Test
    fun `empty input is safe`() {
        assertTrue(sanitizer.sanitize(emptyList()).isEmpty())
        assertEquals(0, sanitizer.countGaps(emptyList(), Timeframe.M1))
    }
}
