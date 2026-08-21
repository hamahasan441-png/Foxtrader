package com.foxtrader.app.feature.chart.presentation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the live last-price tag geometry.
 *
 * Background: the tag used to be positioned with
 * `(lastY - tagH / 2f).coerceIn(0f, ch - tagH)`. When indicator panes (RSI,
 * MACD, Stochastic, volume, …) are stacked, the main chart area shrinks; once
 * `ch < tagH` the upper bound goes negative and `coerceIn` throws
 * IllegalArgumentException on the render thread — the "touching indicators
 * crashes the app" failure. The extracted helper must never throw and must
 * always return geometry inside the available area.
 */
class ChartPriceTagGeometryTest {

    private val textSize = 40f
    private val fullTag = textSize + PRICE_TAG_PADDING // 47f

    @Test
    fun `normal chart area centres the tag on the price`() {
        val g = priceTagGeometry(chartHeight = 800f, textSize = textSize, lastY = 400f)
        assertTrue("height should be the full tag", g.height == fullTag)
        assertTrue("top should centre the tag on lastY", g.top == 400f - fullTag / 2f)
    }

    @Test
    fun `tiny chart area smaller than the tag never throws and clamps the tag`() {
        // The exact state that crashed: chart area shorter than the tag.
        for (ch in floatArrayOf(1f, 5f, 10f, fullTag - 1f, fullTag, 0f)) {
            val g = priceTagGeometry(chartHeight = ch, textSize = textSize, lastY = ch / 2f)
            assertTrue("height must fit the chart area (ch=$ch, h=${g.height})", g.height <= ch)
            assertTrue("top must be >= 0 (ch=$ch, top=${g.top})", g.top >= 0f)
            assertTrue(
                "top + height must stay inside the chart area (ch=$ch)",
                g.top + g.height <= ch + 0.001f,
            )
        }
    }

    @Test
    fun `zero height chart area degenerates to an empty tag`() {
        val g = priceTagGeometry(chartHeight = 0f, textSize = textSize, lastY = 0f)
        assertTrue(g.height == 0f)
        assertTrue(g.top == 0f)
    }

    @Test
    fun `last price above or below the chart area clamps the tag inside it`() {
        val gHigh = priceTagGeometry(chartHeight = 300f, textSize = textSize, lastY = 50_000f)
        assertTrue(gHigh.top == 300f - fullTag)

        val gLow = priceTagGeometry(chartHeight = 300f, textSize = textSize, lastY = -50_000f)
        assertTrue(gLow.top == 0f)
    }

    @Test
    fun `negative chart height input is treated as zero`() {
        val g = priceTagGeometry(chartHeight = -12f, textSize = textSize, lastY = 5f)
        assertTrue(g.height == 0f)
        assertTrue(g.top == 0f)
    }

    @Test
    fun `non-finite inputs never produce a non-empty inverted range`() {
        val g = priceTagGeometry(chartHeight = Float.NaN, textSize = textSize, lastY = Float.NaN)
        // NaN chart height coerces to a finite geometry via coerceAtLeast(0f).
        assertTrue(g.height >= 0f)
        assertTrue(g.top >= 0f)
    }
}
