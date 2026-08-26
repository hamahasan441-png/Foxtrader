package com.foxtrader.app.feature.backtest.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestLabStrategyScopeTest {

    @Test
    fun `primary lab exposes only FoxTrader target methodologies`() {
        assertEquals(
            listOf(
                BacktestStrategyTemplate.LITX,
                BacktestStrategyTemplate.LIT_MAY_MADNESS,
                BacktestStrategyTemplate.SMT,
                BacktestStrategyTemplate.RSI_ORDERFLOW,
                BacktestStrategyTemplate.AMD,
                BacktestStrategyTemplate.NASCENT,
            ),
            BacktestStrategyTemplate.primaryEntries,
        )
    }

    @Test
    fun `lab defaults to LiT Adventure and enables mode comparison`() {
        val state = BacktestLabUiState()

        assertEquals(BacktestStrategyTemplate.LITX, state.strategy)
        assertTrue(state.canCompareLitModes)
    }
}
