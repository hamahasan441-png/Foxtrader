package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartBacktestMapperTest {
    @Test
    fun `maps win loss and breakeven counts to chart state`() {
        val result = BacktestResult(
            config = BacktestConfig(),
            trades = listOf(
                trade(1, 120.0, Direction.BULLISH),
                trade(2, -80.0, Direction.BEARISH),
                trade(3, 0.0, Direction.BULLISH),
            ),
            metrics = metrics(total = 3, wins = 1, losses = 1, winRate = 33.333, netProfit = 40.0),
            equityCurve = listOf(
                EquityPoint(index = 0, timestamp = 1L, balance = 100_000.0, drawdown = 0.0, drawdownPercent = 0.0),
                EquityPoint(index = 1, timestamp = 2L, balance = 100_040.0, drawdown = 0.0, drawdownPercent = 0.0),
            ),
            startDate = 1L,
            endDate = 3L,
            durationDays = 1.0,
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
        )

        val mapped = ChartBacktestMapper.map(
            result = result,
            strategyName = "LIT X",
            previous = ChartBacktestState(selectedRange = ChartBacktestRange.SIX_MONTHS),
            rangeCoverageComplete = false,
            historyNotice = "Partial provider history",
            nowMillis = 1234L,
        )

        assertTrue(mapped.hasResult)
        assertEquals(3, mapped.totalSignals)
        assertEquals(1, mapped.winningSignals)
        assertEquals(1, mapped.losingSignals)
        assertEquals(1, mapped.breakevenSignals)
        assertEquals(3, mapped.markers.size)
        assertEquals(40.0, mapped.netPnL, 0.0)
        assertEquals(0.04, mapped.returnPercent, 0.0)
        assertEquals(0.08, mapped.maxDrawdownPercent, 0.0)
        assertEquals(10.0, mapped.expectancy, 0.0)
        assertEquals(1.0 / 3.0, mapped.averageR, 1e-9)
        assertEquals(2, mapped.equityCurve.size)
        assertEquals(ChartBacktestRange.SIX_MONTHS, mapped.selectedRange)
        assertTrue(!mapped.rangeCoverageComplete)
        assertEquals("Partial provider history", mapped.historyNotice)
        assertEquals(1234L, mapped.lastRunTime)
    }

    private fun trade(id: Int, pnl: Double, direction: Direction) = BacktestTrade(
        id = id,
        direction = direction,
        entryIndex = id,
        entryTime = id.toLong(),
        entryPrice = 1.1000,
        exitIndex = id + 1,
        exitTime = (id + 1).toLong(),
        exitPrice = 1.1010,
        volume = 1.0,
        grossPnL = pnl,
        commission = 0.0,
        netPnL = pnl,
        rMultiple = if (pnl > 0) 2.0 else if (pnl < 0) -1.0 else 0.0,
        exitReason = if (pnl > 0) ExitReason.TP else if (pnl < 0) ExitReason.SL else ExitReason.END,
        balanceAfter = 100_000.0 + pnl,
        holdingBars = 1,
    )

    private fun metrics(total: Int, wins: Int, losses: Int, winRate: Double, netProfit: Double) = BacktestMetrics(
        netProfit = netProfit,
        grossProfit = 120.0,
        grossLoss = -80.0,
        totalTrades = total,
        winningTrades = wins,
        losingTrades = losses,
        winRate = winRate,
        profitFactor = 1.5,
        expectancy = 10.0,
        averageTrade = 10.0,
        averageWin = 120.0,
        averageLoss = -80.0,
        largestWin = 120.0,
        largestLoss = -80.0,
        maxDrawdown = 80.0,
        maxDrawdownPercent = 0.08,
        sharpeRatio = 0.0,
        sortinoRatio = 0.0,
        calmarRatio = 0.0,
        recoveryFactor = 0.0,
        avgHoldingBars = 1.0,
        maxConsecutiveWins = 1,
        maxConsecutiveLosses = 1,
        finalBalance = 100_040.0,
        returnPercent = 0.04,
        totalCommission = 0.0,
    )
}
