package com.foxtrader.app.feature.chart.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartViewportFutureSpaceTest {

    @Test
    fun `latest candle can be dragged to horizontal centre`() {
        val viewport = ChartViewport(startIndex = 400f, visibleBars = 100f)

        // Drag chart left by half its width, then apply the normal camera clamp.
        viewport.panByPixels(panPx = -500f, chartAreaWidth = 1_000f)
        viewport.clamp(total = 500)

        assertEquals(449f, viewport.startIndex, 0.001f)
        assertEquals(500f, viewport.xForIndex(index = 499f, chartAreaWidth = 1_000f), 0.001f)
    }

    @Test
    fun `future space is bounded at latest candle centre`() {
        val viewport = ChartViewport(startIndex = 10_000f, visibleBars = 80f)

        viewport.clamp(total = 500)

        assertEquals(459f, viewport.startIndex, 0.001f)
        assertEquals(500f, viewport.xForIndex(index = 499f, chartAreaWidth = 1_000f), 0.001f)
    }

    @Test
    fun `moving latest candle into future space disables live follow`() {
        val viewport = ChartViewport(startIndex = 420f, visibleBars = 80f)
        assertTrue(viewport.isAtRightEdge(total = 500))

        viewport.panByPixels(panPx = -250f, chartAreaWidth = 1_000f)
        viewport.clamp(total = 500)

        assertFalse(viewport.isAtRightEdge(total = 500))
    }
}
