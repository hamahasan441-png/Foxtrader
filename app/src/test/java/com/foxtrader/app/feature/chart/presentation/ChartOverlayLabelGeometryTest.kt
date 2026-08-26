package com.foxtrader.app.feature.chart.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartOverlayLabelGeometryTest {

    @Test
    fun `normal label baseline is clamped inside chart`() {
        assertEquals(20f, chartOverlayLabelBaseline(20f, 12f, 100f)!!, 0f)
        assertEquals(12f, chartOverlayLabelBaseline(-500f, 12f, 100f)!!, 0f)
        assertEquals(98f, chartOverlayLabelBaseline(500f, 12f, 100f)!!, 0f)
    }

    @Test
    fun `chart shorter than text skips label instead of building inverted range`() {
        for (height in floatArrayOf(0f, 1f, 5f, 11f, 13f)) {
            assertNull(chartOverlayLabelBaseline(6f, textSize = 12f, chartHeight = height, bottomPadding = 2f))
        }
    }

    @Test
    fun `first fitting height returns a valid baseline`() {
        val baseline = chartOverlayLabelBaseline(1f, textSize = 12f, chartHeight = 14f, bottomPadding = 2f)
        assertEquals(12f, baseline!!, 0f)
        assertTrue(baseline <= 12f)
    }

    @Test
    fun `non finite geometry never reaches canvas`() {
        assertNull(chartOverlayLabelBaseline(Float.NaN, 12f, Float.NaN))
        assertNull(chartOverlayLabelBaseline(10f, Float.NaN, 100f))
    }
}
