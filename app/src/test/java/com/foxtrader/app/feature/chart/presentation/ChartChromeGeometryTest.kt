package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract for the fixed-height rows above the price canvas. */
class ChartChromeGeometryTest {

    @Test
    fun `non system chart chrome stays compact`() {
        assertEquals(40.dp, ChartDimens.topBarContentHeight)
        assertEquals(36.dp, ChartDimens.toolbarHeight)
        assertTrue(
            "Header and toolbar must not recreate the large blank chart band",
            ChartDimens.topBarContentHeight + ChartDimens.toolbarHeight <= 76.dp,
        )
    }
}
