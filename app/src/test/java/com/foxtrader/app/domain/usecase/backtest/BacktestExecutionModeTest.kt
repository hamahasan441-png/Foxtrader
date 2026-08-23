package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestExecutionModeTest {

    @Test
    fun `next bar open fills on following candle and preserves decision metadata`() {
        val engine = engine(
            mode = BacktestExecutionMode.NEXT_BAR_OPEN,
            slippage = 0.25,
        )
        val candles = listOf(
            candle(1_000L, open = 100.0, high = 101.0, low = 99.0, close = 100.0),
            candle(2_000L, open = 105.0, high = 106.0, low = 104.0, close = 105.0),
            candle(3_000L, open = 106.0, high = 107.0, low = 105.0, close = 106.0),
        )

        val result = engine(
            candles,
            strategyAt(index = 0, entry = 100.0, stop = 90.0, target = 130.0, volume = 1.0),
            "TEST",
            Timeframe.M1,
        )

        val trade = result.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(2_000L, trade.entryTime)
        assertEquals(105.25, trade.entryPrice, 1e-12)
        assertEquals(0, trade.signalIndex)
        assertEquals(1_000L, trade.signalTime)
        assertEquals(1, trade.holdingBars)
        assertEquals(BacktestExecutionMode.NEXT_BAR_OPEN, result.config.executionMode)
    }

    @Test
    fun `next bar sizing is frozen on signal bar and cannot use future open`() {
        val engine = engine(
            mode = BacktestExecutionMode.NEXT_BAR_OPEN,
            initialBalance = 1_000.0,
            riskPercent = 1.0,
            contractSize = 1,
        )
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            candle(2_000L, 105.0, 106.0, 104.0, 105.0),
            candle(3_000L, 106.0, 107.0, 105.0, 106.0),
        )

        val result = engine(
            candles,
            strategyAt(index = 0, entry = 100.0, stop = 90.0, target = 130.0, volume = null),
        )

        // Signal-bar risk: 1% of 1000 = 10; stop distance = 10; contract = 1 -> 1.0.
        // Re-sizing from the future 105 open would incorrectly produce 0.67.
        assertEquals(1.0, result.trades.single().volume, 1e-12)
    }

    @Test
    fun `gap through stop exits at executable open instead of stale stop`() {
        val engine = engine(mode = BacktestExecutionMode.NEXT_BAR_OPEN)
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            candle(2_000L, 90.0, 92.0, 88.0, 91.0),
            candle(3_000L, 91.0, 93.0, 90.0, 92.0),
        )

        val result = engine(
            candles,
            strategyAt(index = 0, entry = 100.0, stop = 95.0, target = 120.0, volume = 1.0),
        )

        val trade = result.trades.single()
        assertEquals(ExitReason.SL, trade.exitReason)
        assertEquals(1, trade.entryIndex)
        assertEquals(1, trade.exitIndex)
        assertEquals(90.0, trade.entryPrice, 1e-12)
        assertEquals(90.0, trade.exitPrice, 1e-12)
    }

    @Test
    fun `next bar position can hit stop on its fill candle and SL wins ambiguous OHLC`() {
        val engine = engine(mode = BacktestExecutionMode.NEXT_BAR_OPEN)
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            // Entry is at this open. Both SL and TP are touched later in this candle.
            candle(2_000L, 100.0, 111.0, 94.0, 105.0),
            candle(3_000L, 105.0, 106.0, 104.0, 105.0),
        )

        val result = engine(
            candles,
            strategyAt(index = 0, entry = 100.0, stop = 95.0, target = 110.0, volume = 1.0),
        )

        val trade = result.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(1, trade.exitIndex)
        assertEquals(ExitReason.SL, trade.exitReason)
        assertEquals(95.0, trade.exitPrice, 1e-12)
    }

    @Test
    fun `legacy signal price mode keeps existing fill semantics`() {
        val engine = engine(mode = BacktestExecutionMode.SIGNAL_PRICE, slippage = 0.25)
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            candle(2_000L, 105.0, 106.0, 104.0, 105.0),
            candle(3_000L, 106.0, 107.0, 105.0, 106.0),
        )

        val result = engine(
            candles,
            strategyAt(index = 0, entry = 100.0, stop = 90.0, target = 130.0, volume = 1.0),
        )

        val trade = result.trades.single()
        assertEquals(0, trade.entryIndex)
        assertEquals(100.25, trade.entryPrice, 1e-12)
        assertEquals(trade.entryIndex, trade.signalIndex)
        assertEquals(trade.entryTime, trade.signalTime)
    }

    @Test
    fun `final bar signal never fabricates a future next bar fill`() {
        val engine = engine(mode = BacktestExecutionMode.NEXT_BAR_OPEN)
        val candles = listOf(
            candle(1_000L, 100.0, 101.0, 99.0, 100.0),
            candle(2_000L, 101.0, 102.0, 100.0, 101.0),
        )
        val strategy: StrategyFunction = { bars, index ->
            if (index == candles.lastIndex) {
                StrategySignal(
                    index = index,
                    timestamp = bars[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = bars[index].close,
                    stopLoss = bars[index].close - 5.0,
                    takeProfit = bars[index].close + 10.0,
                    volume = 1.0,
                )
            } else {
                null
            }
        }

        val result = engine(candles, strategy)
        assertTrue(result.trades.isEmpty())
    }

    @Test
    fun `next bar mode still gives strategy only its causal prefix`() {
        val engine = engine(mode = BacktestExecutionMode.NEXT_BAR_OPEN)
        val candles = (0 until 12).map { i ->
            val p = 100.0 + i
            candle((i + 1) * 1_000L, p, p + 1.0, p - 1.0, p)
        }
        var calls = 0
        val strategy: StrategyFunction = { bars, index ->
            calls++
            assertEquals(index + 1, bars.size)
            null
        }

        engine(candles, strategy)
        assertEquals(candles.size, calls)
    }

    private fun engine(
        mode: BacktestExecutionMode,
        slippage: Double = 0.0,
        initialBalance: Double = 10_000.0,
        riskPercent: Double = 1.0,
        contractSize: Int = 1,
    ): BacktestEngine = BacktestEngine().apply {
        updateConfig(
            BacktestConfig(
                initialBalance = initialBalance,
                spread = 0.0,
                variableSpread = false,
                commissionPerLot = 0.0,
                slippage = slippage,
                riskPercent = riskPercent,
                contractSize = contractSize,
                executionMode = mode,
            )
        )
    }

    private fun strategyAt(
        index: Int,
        entry: Double,
        stop: Double,
        target: Double,
        volume: Double?,
    ): StrategyFunction = { bars, i ->
        if (i == index) {
            StrategySignal(
                index = i,
                timestamp = bars[i].timestamp,
                direction = Direction.BULLISH,
                entry = entry,
                stopLoss = stop,
                takeProfit = target,
                volume = volume,
                setupType = "EXECUTION_TEST",
            )
        } else {
            null
        }
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
