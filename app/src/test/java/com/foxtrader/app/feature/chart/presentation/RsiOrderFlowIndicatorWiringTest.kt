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

    @Test
    fun `chart gear controls the executable arrow risk and quality config`() {
        val settings = RsiOrderFlowStudySettings(
            minStrength = 67,
            riskLookback = 21,
            stopBufferRangeMultiple = 0.4,
            rewardRisk = 2.75,
        )

        val config = settings.toSignalEngineConfig()

        assertEquals(67, config.minStrength)
        assertEquals(21, config.riskLookback)
        assertEquals(0.4, config.stopBufferRangeMultiple, 0.000_001)
        assertEquals(2.75, config.rewardRisk, 0.000_001)
    }
}
