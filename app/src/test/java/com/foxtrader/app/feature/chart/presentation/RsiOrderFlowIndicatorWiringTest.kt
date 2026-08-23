package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartBarMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsiOrderFlowIndicatorWiringTest {
    @Test
    fun `RSI and RSI OrderFlow are independent visible studies`() {
        val plain = IndicatorToggles(rsi = true)
        val orderFlow = IndicatorToggles(rsiOrderFlow = true)

        assertTrue(plain.rsi)
        assertFalse(plain.rsiOrderFlow)
        assertTrue(orderFlow.rsiOrderFlow)
        assertFalse(orderFlow.rsi)
        assertTrue(orderFlow.anyActive)
        assertEquals("RSI OrderFlow", ChartStudyId.RSI_ORDER_FLOW.label)
        assertEquals(
  IndicatorReadinessLevel.READY,
  IndicatorReadinessCatalog.status(ChartStudyId.RSI_ORDER_FLOW, 25, ChartBarMode.TIME).level,
        )
    }
}
