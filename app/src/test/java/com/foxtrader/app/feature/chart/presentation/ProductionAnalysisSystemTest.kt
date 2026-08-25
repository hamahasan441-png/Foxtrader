package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.StrategyType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAnalysisSystemTest {

    @Test
    fun `selecting adventure preserves independent studies and settings`() {
        val customSettings = ChartStudySettings(
            ema = EmaStudySettings(fastPeriod = 9, slowPeriod = 34),
            rsi = RsiStudySettings(period = 7),
        )
        val existing = IndicatorToggles(
            ema = true,
            macd = true,
            tradePro = true,
            binary3m = true,
            lit = true,
            smt = true,
            rsiOrderFlow = true,
            allStrategies = true,
            activeStrategy = StrategyType.BREAKOUT,
            settings = customSettings,
        )

        val result = existing.withProductionAnalysisSystem(ProductionAnalysisSystem.LIT_ADVENTURE)

        assertTrue(result.litX)
        assertTrue(result.structure)
        assertTrue(result.orderBlocks)
        assertTrue(result.fairValueGaps)
        assertTrue(result.liquidity)
        assertTrue(result.sessions)

        // Only the mutually-exclusive canonical flags are switched.
        assertFalse(result.lit)
        assertFalse(result.smt)
        assertFalse(result.rsiOrderFlow)

        // Every independent indicator/engine/strategy choice survives.
        assertTrue(result.tradePro)
        assertTrue(result.binary3m)
        assertTrue(result.ema)
        assertTrue(result.macd)
        assertTrue(result.allStrategies)
        assertTrue(result.activeStrategy == StrategyType.BREAKOUT)
        assertTrue(result.settings == customSettings)
    }

    @Test
    fun `selecting each canonical system activates exactly one canonical engine`() {
        val mappings = listOf(
            ProductionAnalysisSystem.LIT_ADVENTURE to { t: IndicatorToggles -> t.litX },
            ProductionAnalysisSystem.LIT_MAY_MADNESS to { t: IndicatorToggles -> t.lit },
            ProductionAnalysisSystem.SMT to { t: IndicatorToggles -> t.smt },
            ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE to { t: IndicatorToggles -> t.rsiOrderFlow },
        )

        mappings.forEach { (system, isExpectedActive) ->
            val result = IndicatorToggles(ema = true).withProductionAnalysisSystem(system)
            assertTrue(isExpectedActive(result))
            assertTrue(result.productionAnalysisSystem() == system)
            assertTrue("technical study must survive system switch", result.ema)
        }
    }

    @Test
    fun `off clears only canonical engines and preserves chart studies`() {
        val result = IndicatorToggles(
            litX = true,
            lit = true,
            smt = true,
            rsiOrderFlow = true,
            tradePro = true,
            binary3m = true,
            ema = true,
            activeStrategy = StrategyType.BREAKOUT,
            allStrategies = true,
        ).withProductionAnalysisSystem(null)

        assertFalse(result.litX)
        assertFalse(result.lit)
        assertFalse(result.smt)
        assertFalse(result.rsiOrderFlow)
        assertNull(result.productionAnalysisSystem())

        assertTrue(result.tradePro)
        assertTrue(result.binary3m)
        assertTrue(result.ema)
        assertTrue(result.activeStrategy == StrategyType.BREAKOUT)
        assertTrue(result.allStrategies)
        assertTrue(result.anyActive)
    }

    @Test
    fun `ambiguous multi-system state is not reported as one system`() {
        val multi = IndicatorToggles(litX = true, smt = true)
        assertNull(multi.productionAnalysisSystem())
    }
}
