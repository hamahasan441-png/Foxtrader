package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.BinaryBacktestConfig
import com.foxtrader.app.domain.model.BinaryBacktestMetrics
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestTrade
import com.foxtrader.app.domain.model.BinaryOutcome
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestAnalyticsEngineTest {

    private val engine = BacktestAnalyticsEngine()

    @Test
    fun `analyze creates walk forward and monte carlo analytics`() {
        val report = engine.analyze(backtestResult(sampleTrades()), monteCarloRuns = 50, seed = 7)

        assertNotNull(report.walkForward)
        assertNotNull(report.monteCarlo)
        val stabilityScore = report.walkForward?.stabilityScore ?: -1
        assertTrue(stabilityScore in 0..100)
        assertEquals(50, report.monteCarlo?.runs ?: -1)
        assertTrue(report.recommendations.isNotEmpty())
    }

    @Test
    fun `binary analysis creates deterministic walk forward and monte carlo validation`() {
        val result = binaryBacktestResult()

        val first = engine.analyzeBinary(result, monteCarloRuns = 50, seed = 17)
        val second = engine.analyzeBinary(result, monteCarloRuns = 50, seed = 17)

        assertNotNull(first.walkForward)
        assertNotNull(first.monteCarlo)
        assertEquals(50, first.monteCarlo?.runs ?: -1)
        assertEquals(first.monteCarlo, second.monteCarlo)
        assertTrue(first.walkForward?.outOfSample?.totalTrades ?: 0 > 0)
        assertTrue(first.recommendations.isNotEmpty())
    }

    @Test
    fun `insufficient trades returns recommendations instead of analytics`() {
        val report = engine.analyze(backtestResult(sampleTrades().take(3)), monteCarloRuns = 50)

        assertNull(report.walkForward)
        assertNull(report.monteCarlo)
        assertTrue(report.recommendations.any { it.contains("more trades", ignoreCase = true) })
    }

    private fun sampleTrades(): List<BacktestTrade> = listOf(
        trade(1, 500.0),
        trade(2, -150.0),
        trade(3, 350.0),
        trade(4, -200.0),
        trade(5, 420.0),
        trade(6, -180.0),
        trade(7, 300.0),
        trade(8, 250.0),
    )

    private fun trade(id: Int, pnl: Double): BacktestTrade = BacktestTrade(
        id = id,
        direction = if (id % 2 == 0) Direction.BEARISH else Direction.BULLISH,
        entryIndex = id * 10,
        entryTime = id * 60_000L,
        entryPrice = 1.1000,
        exitIndex = id * 10 + 5,
        exitTime = id * 60_000L + 5_000L,
        exitPrice = 1.1050,
        volume = 0.1,
        grossPnL = pnl,
        commission = 0.0,
        netPnL = pnl,
        rMultiple = pnl / 200.0,
        exitReason = if (pnl >= 0.0) ExitReason.TP else ExitReason.SL,
        balanceAfter = 100_000.0 + pnl,
        holdingBars = 5,
    )

    private fun binaryBacktestResult(): BinaryBacktestResult {
        val config = BinaryBacktestConfig(initialBalance = 10_000.0, riskPercent = 1.0, payoutRatio = 0.85)
        val pnlValues = listOf(85.0, -100.0, 85.0, 85.0, -100.0, 85.0, -100.0, 85.0)
        var balance = config.initialBalance
        val trades = pnlValues.mapIndexed { index, pnl ->
            balance += pnl
            val bullish = index % 2 == 0
            BinaryBacktestTrade(
                id = index + 1,
                direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
                signalIndex = 60 + index * 4,
                signalTime = (60 + index * 4) * 60_000L,
                entryIndex = 61 + index * 4,
                entryTime = (61 + index * 4) * 60_000L,
                entryPrice = 100.0,
                expiryIndex = 63 + index * 4,
                expiryTime = (64 + index * 4) * 60_000L,
                expiryPrice = if (pnl > 0.0) { if (bullish) 101.0 else 99.0 } else { if (bullish) 99.0 else 101.0 },
                outcome = if (pnl > 0.0) BinaryOutcome.WIN else BinaryOutcome.LOSS,
                stake = 100.0,
                pnl = pnl,
                balanceAfter = balance,
                confidence = 80,
                setupType = "TEST",
            )
        }
        val wins = trades.count { it.outcome == BinaryOutcome.WIN }
        val losses = trades.count { it.outcome == BinaryOutcome.LOSS }
        val net = trades.sumOf { it.pnl }
        return BinaryBacktestResult(
            config = config,
            symbol = "R_100",
            timeframe = Timeframe.M1,
            trades = trades,
            metrics = BinaryBacktestMetrics(
                totalTrades = trades.size,
                wins = wins,
                losses = losses,
                ties = 0,
                winRate = wins.toDouble() / trades.size * 100.0,
                breakEvenWinRate = 100.0 / (1.0 + config.payoutRatio),
                edgeVsBreakEven = wins.toDouble() / trades.size * 100.0 - 100.0 / (1.0 + config.payoutRatio),
                netProfit = net,
                returnPercent = net / config.initialBalance * 100.0,
                expectancyPerTrade = net / trades.size,
                profitFactor = trades.filter { it.pnl > 0 }.sumOf { it.pnl } / kotlin.math.abs(trades.filter { it.pnl < 0 }.sumOf { it.pnl }),
                maxDrawdown = 100.0,
                maxDrawdownPercent = 1.0,
                maxConsecutiveWins = 2,
                maxConsecutiveLosses = 1,
                finalBalance = config.initialBalance + net,
            ),
            equityCurve = trades.mapIndexed { index, trade -> EquityPoint(index, trade.entryTime, trade.balanceAfter, 0.0, 0.0) },
            startDate = trades.first().signalTime,
            endDate = trades.last().expiryTime,
        )
    }

    private fun backtestResult(trades: List<BacktestTrade>): BacktestResult = BacktestResult(
        config = BacktestConfig(initialBalance = 100_000.0),
        trades = trades,
        metrics = BacktestMetrics(
            netProfit = trades.sumOf { it.netPnL },
            grossProfit = trades.filter { it.netPnL > 0.0 }.sumOf { it.netPnL },
            grossLoss = kotlin.math.abs(trades.filter { it.netPnL < 0.0 }.sumOf { it.netPnL }),
            totalTrades = trades.size,
            winningTrades = trades.count { it.netPnL > 0.0 },
            losingTrades = trades.count { it.netPnL < 0.0 },
            winRate = if (trades.isNotEmpty()) trades.count { it.netPnL > 0.0 }.toDouble() / trades.size * 100.0 else 0.0,
            profitFactor = 1.0,
            expectancy = 0.0,
            averageTrade = 0.0,
            averageWin = 0.0,
            averageLoss = 0.0,
            largestWin = 0.0,
            largestLoss = 0.0,
            maxDrawdown = 0.0,
            maxDrawdownPercent = 0.0,
            sharpeRatio = 0.0,
            sortinoRatio = 0.0,
            calmarRatio = 0.0,
            recoveryFactor = 0.0,
            avgHoldingBars = 0.0,
            maxConsecutiveWins = 0,
            maxConsecutiveLosses = 0,
            finalBalance = 100_000.0 + trades.sumOf { it.netPnL },
            returnPercent = 0.0,
            totalCommission = 0.0,
        ),
        equityCurve = trades.mapIndexed { index, trade ->
            EquityPoint(index, trade.entryTime, 100_000.0 + trades.take(index + 1).sumOf { it.netPnL }, 0.0, 0.0)
        },
        startDate = trades.firstOrNull()?.entryTime ?: 0L,
        endDate = trades.lastOrNull()?.exitTime ?: 0L,
        durationDays = 1.0,
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
    )
}
