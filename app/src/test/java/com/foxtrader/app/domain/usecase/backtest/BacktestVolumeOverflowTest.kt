package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position sizing must never turn an impossible signal into a huge position.
 *
 * `calculateVolume` used `(volume * 100).roundToInt()`, which saturates at
 * Int.MAX_VALUE rather than throwing. A stop distance near MIN_PRICE_DISTANCE
 * (1e-12) sizes to ~1e9 lots, saturates, and silently became a ~21 million lot
 * trade whose P&L dominated every metric in the report.
 */
class BacktestVolumeOverflowTest {

    private val candles = List(60) { index ->
        Candle(
            timestamp = index * 3_600_000L,
            open = 1.10000,
            high = 1.10050,
            low = 1.09950,
            close = 1.10000,
            volume = 1_000.0,
        )
    }

    private fun engineWith(mode: BacktestExecutionMode): BacktestEngine =
        BacktestEngine().apply {
            updateConfig(
                BacktestConfig(
                    initialBalance = 100_000.0,
                    riskPercent = 1.0,
                    contractSize = 100_000,
                    executionMode = mode,
                    // Remove fee noise so a trade's presence is the only signal.
                    spread = 0.0,
                    variableSpread = false,
                    commissionPerLot = 0.0,
                    slippage = 0.0,
                ),
            )
        }

    /** A stop this tight cannot be sized inside the risk budget — skip it. */
    private fun degenerateStopStrategy(): StrategyFunction = { prefix, index ->
        if (index != 10) {
            null
        } else {
            StrategySignal(
                index = index,
                timestamp = prefix[index].timestamp,
                direction = Direction.BULLISH,
                entry = 1.10000,
                // Passes MIN_PRICE_DISTANCE (1e-12) but is far below any
                // representable tick.
                stopLoss = 1.10000 - 1e-11,
                takeProfit = 1.20000,
                setupType = "degenerate",
            )
        }
    }

    @Test
    fun `a sub-tick stop opens no position instead of an overflowed one`() {
        for (mode in BacktestExecutionMode.entries) {
            val result = engineWith(mode)(
                candles = candles,
                strategy = degenerateStopStrategy(),
                symbol = "EURUSD",
                timeframe = Timeframe.H1,
            )
            assertEquals(
                "mode $mode must reject a stop the risk model cannot size",
                0,
                result.trades.size,
            )
        }
    }

    /** A normal stop still sizes and trades exactly as before. */
    @Test
    fun `a realistic stop still produces a sanely sized trade`() {
        val strategy: StrategyFunction = { prefix, index ->
            if (index != 10) {
                null
            } else {
                StrategySignal(
                    index = index,
                    timestamp = prefix[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = 1.10000,
                    stopLoss = 1.09000,
                    takeProfit = 1.12000,
                    setupType = "normal",
                )
            }
        }

        val result = engineWith(BacktestExecutionMode.NEXT_BAR_OPEN)(
            candles = candles,
            strategy = strategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        assertTrue("a well-formed signal must still open a position", result.trades.isNotEmpty())
        val volume = result.trades.first().volume
        // 1 % of 100 000 = 1 000 risk over a 0.01 stop on a 100 000 contract = 1 lot.
        assertEquals(1.0, volume, 1e-9)
    }
}
