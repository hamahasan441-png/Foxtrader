package com.foxtrader.app.feature.chart.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAnalysisSystemTest {

    @Test
    fun `selecting adventure clears unrelated public engines but keeps required internals`() {
        val legacy = IndicatorToggles(
            ema = true,
            macd = true,
            tradePro = true,
            binary3m = true,
            lit = true,
            smt = true,
            rsiOrderFlow = true,
            allStrategies = true,
        )

        val result = legacy.withProductionAnalysisSystem(ProductionAnalysisSystem.LIT_ADVENTURE)

        assertTrue(result.litX)
        assertTrue(result.structure)
        assertTrue(result.orderBlocks)
        assertTrue(result.fairValueGaps)
        assertTrue(result.liquidity)
        assertTrue(result.sessions)
        assertFalse(result.lit)
        assertFalse(result.smt)
        assertFalse(result.rsiOrderFlow)
        assertFalse(result.tradePro)
        assertFalse(result.binary3m)
        assertFalse(result.ema)
        assertFalse(result.macd)
        assertFalse(result.allStrategies)
    }

    @Test
    fun `selecting each canonical system activates exactly one primary engine`() {
        val mappings = listOf(
            ProductionAnalysisSystem.LIT_ADVENTURE to { t: IndicatorToggles -> t.litX },
            ProductionAnalysisSystem.LIT_MAY_MADNESS to { t: IndicatorToggles -> t.lit },
            ProductionAnalysisSystem.SMT to { t: IndicatorToggles -> t.smt },
            ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE to { t: IndicatorToggles -> t.rsiOrderFlow },
        )

        mappings.forEach { (system, isExpectedActive) ->
            val result = IndicatorToggles().withProductionAnalysisSystem(system)
            assertTrue(isExpectedActive(result))
            assertTrue(result.productionAnalysisSystem() == system)
        }
    }

    @Test
    fun `off clears every legacy selectable system`() {
        val result = IndicatorToggles(
            litX = true,
            lit = true,
            smt = true,
            rsiOrderFlow = true,
            tradePro = true,
            binary3m = true,
            activeStrategy = com.foxtrader.app.domain.model.StrategyType.BREAKOUT,
            allStrategies = true,
        ).withProductionAnalysisSystem(null)

        assertFalse(result.anyActive)
        assertNull(result.productionAnalysisSystem())
    }

    @Test
    fun `ambiguous legacy multi-system state is not reported as one system`() {
        val legacy = IndicatorToggles(litX = true, smt = true)
        assertNull(legacy.productionAnalysisSystem())
    }
}
