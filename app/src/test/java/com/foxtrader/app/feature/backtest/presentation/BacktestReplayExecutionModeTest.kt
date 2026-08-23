package com.foxtrader.app.feature.backtest.presentation

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestReplayExecutionModeTest {

    @Test
    fun `replay hides market entry until its next-bar fill is revealed`() {
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            candle(2_000L, 101.0, 102.0, 100.0, 101.0),
            candle(3_000L, 102.0, 103.0, 101.0, 102.0),
        )
        val engine = BacktestEngine().apply {
            updateConfig(
                BacktestConfig(
                    initialBalance = 10_000.0,
                    spread = 0.0,
                    variableSpread = false,
                    commissionPerLot = 0.0,
                    slippage = 0.0,
                    contractSize = 1,
                    executionMode = BacktestExecutionMode.NEXT_BAR_OPEN,
                )
            )
        }
        val result = engine(candles, { bars, index ->
            if (index == 0) {
                StrategySignal(
                    index = 0,
                    timestamp = bars[0].timestamp,
                    direction = Direction.BULLISH,
                    entry = bars[0].close,
                    stopLoss = 90.0,
                    takeProfit = 120.0,
                    volume = 1.0,
                )
            } else {
                null
            }
        })

        val onSignalBar = projectBacktestReplay(
            candles = candles,
            result = result,
            binaryResult = null,
            cursor = 0,
        )
        assertTrue(onSignalBar.markers.isEmpty())

        val onFillBar = projectBacktestReplay(
            candles = candles,
            result = result,
            binaryResult = null,
            cursor = 1,
        )
        assertEquals(1, onFillBar.markers.size)
        assertEquals(1, onFillBar.markers.single().entryIndex)
        assertNull(onFillBar.markers.single().exitIndex)
        assertNull(onFillBar.markers.single().outcome)

        val afterExit = projectBacktestReplay(
            candles = candles,
            result = result,
            binaryResult = null,
            cursor = 2,
        )
        assertEquals(2, afterExit.markers.single().exitIndex)
        assertEquals("WIN", afterExit.markers.single().outcome)
    }

    private fun candle(
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ): Candle = Candle(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )
}
