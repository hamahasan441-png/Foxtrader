package com.foxtrader.app.feature.backtest.presentation

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestReplayProjectionTest {
    @Test
    fun `future exit is hidden until exit candle is revealed`() {
        val candles = candles(8)
        val trade = BacktestTrade(
  id = 1,
  direction = Direction.BULLISH,
  entryIndex = 2,
  entryTime = candles[2].timestamp,
  entryPrice = candles[2].close,
  exitIndex = 6,
  exitTime = candles[6].timestamp,
  exitPrice = candles[6].close,
  volume = 1.0,
  grossPnL = 100.0,
  commission = 0.0,
  netPnL = 100.0,
  rMultiple = 2.0,
  exitReason = ExitReason.TP,
  balanceAfter = 10_100.0,
  holdingBars = 4,
        )
        val result = BacktestResult(
  config = BacktestConfig(initialBalance = 10_000.0),
  trades = listOf(trade),
  metrics = emptyMetrics(),
  equityCurve = emptyList(),
  startDate = candles.first().timestamp,
  endDate = candles.last().timestamp,
  durationDays = 0.0,
  symbol = "EURUSD",
  timeframe = Timeframe.M1,
        )

        val before = projectBacktestReplay(candles, result, null, 4)
        assertEquals(1, before.markers.size)
        assertTrue(before.markers.single().isOpen)
        assertNull(before.markers.single().outcome)
        assertEquals(0, before.completedTrades)
        assertEquals(0.0, before.netPnL, 0.0)

        val after = projectBacktestReplay(candles, result, null, 6)
        assertEquals("WIN", after.markers.single().outcome)
        assertEquals(1, after.completedTrades)
        assertEquals(1, after.wins)
        assertEquals(100.0, after.netPnL, 0.0)
    }

    private fun candles(count: Int) = List(count) { i ->
        val p = 100.0 + i
        Candle(i * 60_000L, p, p + 1.0, p - 1.0, p + 0.5, 100.0)
    }

    private fun emptyMetrics() = BacktestMetrics(
        netProfit = 0.0, grossProfit = 0.0, grossLoss = 0.0, totalTrades = 0,
        winningTrades = 0, losingTrades = 0, winRate = 0.0, profitFactor = 0.0,
        expectancy = 0.0, averageTrade = 0.0, averageWin = 0.0, averageLoss = 0.0,
        largestWin = 0.0, largestLoss = 0.0, maxDrawdown = 0.0, maxDrawdownPercent = 0.0,
        sharpeRatio = 0.0, sortinoRatio = 0.0, calmarRatio = 0.0, recoveryFactor = 0.0,
        avgHoldingBars = 0.0, maxConsecutiveWins = 0, maxConsecutiveLosses = 0,
        finalBalance = 10_000.0, returnPercent = 0.0, totalCommission = 0.0,
    )
}
