package com.foxtrader.app.domain.usecase.binary

import com.foxtrader.app.domain.model.BinaryBacktestConfig
import com.foxtrader.app.domain.model.BinaryOutcome
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryBacktestEngineTest {
    private val engine = BinaryBacktestEngine(DerivBinary3mSignalEngine())

    @Test
    fun `closed signal enters next minute and expires exactly three bars later`() {
        val candles = bullishPullbackSeries()
        val result = engine(
            candles = candles,
            symbol = "R_100",
            timeframe = Timeframe.M1,
            config = BinaryBacktestConfig(
                initialBalance = 10_000.0,
                riskPercent = 1.0,
                payoutRatio = 0.85,
                expiryBars = 3,
                minConfidence = 60,
            ),
        )

        assertTrue(result.trades.isNotEmpty())
        val trade = result.trades.first()
        assertEquals(trade.signalIndex + 1, trade.entryIndex)
        assertEquals(trade.signalIndex + 3, trade.expiryIndex)
        assertEquals(candles[trade.entryIndex].open, trade.entryPrice, 1e-12)
        assertEquals(candles[trade.expiryIndex].close, trade.expiryPrice, 1e-12)
    }

    @Test
    fun `winning contract credits payout ratio and never martingales stake`() {
        val result = engine(
            candles = bullishPullbackSeries(),
            symbol = "R_100",
            timeframe = Timeframe.M1,
            config = BinaryBacktestConfig(
                initialBalance = 10_000.0,
                riskPercent = 1.0,
                payoutRatio = 0.85,
                expiryBars = 3,
                minConfidence = 60,
            ),
        )

        val first = result.trades.first()
        assertEquals(BinaryOutcome.WIN, first.outcome)
        assertEquals(100.0, first.stake, 1e-9)
        assertEquals(85.0, first.pnl, 1e-9)
        assertEquals(10_085.0, first.balanceAfter, 1e-9)
        assertEquals(100.0 / 1.85, result.metrics.breakEvenWinRate, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `three minute binary backtest refuses non M1 candles`() {
        engine(
            candles = bullishPullbackSeries(),
            symbol = "R_100",
            timeframe = Timeframe.M5,
        )
    }

    private fun bullishPullbackSeries(size: Int = 220): List<Candle> {
        val out = ArrayList<Candle>(size)
        var price = 100.0
        val start = 1_700_000_000_000L
        repeat(size) { i ->
            val mod = i % 12
            val delta = when (mod) {
                8 -> -0.07
                9 -> -0.06
                10 -> 0.11
                else -> 0.025
            }
            val open = price
            val close = open + delta
            val low = minOf(open, close) - if (mod == 10) 0.05 else 0.015
            val high = maxOf(open, close) + 0.015
            out += Candle(start + i * 60_000L, open, high, low, close, 0.0)
            price = close
        }
        return out
    }
}
