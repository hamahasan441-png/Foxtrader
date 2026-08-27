package com.foxtrader.app.feature.chart.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartPaneStackSizingTest {
    @Test
    fun `dragging upward grows the indicator pane`() {
        assertEquals(172f, resizedPaneHeight(currentHeightDp = 132f, dragDeltaDp = -40f), 0f)
    }

    @Test
    fun `dragging downward shrinks the indicator pane`() {
        assertEquals(102f, resizedPaneHeight(currentHeightDp = 132f, dragDeltaDp = 30f), 0f)
    }

    @Test
    fun `indicator pane height is clamped to safe bounds`() {
        assertEquals(72f, resizedPaneHeight(currentHeightDp = 132f, dragDeltaDp = 10_000f), 0f)
        assertEquals(280f, resizedPaneHeight(currentHeightDp = 132f, dragDeltaDp = -10_000f), 0f)
    }
}
