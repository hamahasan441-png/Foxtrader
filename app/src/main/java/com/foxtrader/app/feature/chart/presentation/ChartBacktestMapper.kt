package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.BacktestChartMarker
import com.foxtrader.app.domain.model.BacktestOutcome
import com.foxtrader.app.domain.model.BacktestResult
import kotlinx.collections.immutable.toPersistentList

/** Maps a domain backtest result into the compact state rendered over the chart. */
internal object ChartBacktestMapper {
    fun map(
        result: BacktestResult,
        strategyName: String,
        previous: ChartBacktestState,
        sourceBarCount: Int = result.equityCurve.size,
        sourceNewestTimestamp: Long = result.endDate,
        rangeCoverageComplete: Boolean = true,
        historyNotice: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChartBacktestState {
        val markers = result.trades.map { trade ->
            val outcome = when {
                trade.netPnL > PNL_EPSILON -> BacktestOutcome.WIN
                trade.netPnL < -PNL_EPSILON -> BacktestOutcome.LOSS
                else -> BacktestOutcome.BREAKEVEN
            }
            BacktestChartMarker(
                tradeId = trade.id,
                direction = trade.direction,
                entryIndex = trade.entryIndex,
                entryTime = trade.entryTime,
                entryPrice = trade.entryPrice,
                exitIndex = trade.exitIndex,
                exitTime = trade.exitTime,
                exitPrice = trade.exitPrice,
                outcome = outcome,
                netPnL = trade.netPnL,
                rMultiple = trade.rMultiple,
                exitReason = trade.exitReason,
            )
        }
        val wins = markers.count { it.outcome == BacktestOutcome.WIN }
        val losses = markers.count { it.outcome == BacktestOutcome.LOSS }
        val breakeven = markers.size - wins - losses
        return previous.copy(
            isRunning = false,
            error = null,
            strategyName = strategyName,
            totalSignals = markers.size,
            winningSignals = wins,
            losingSignals = losses,
            breakevenSignals = breakeven,
            winRate = result.metrics.winRate,
            netPnL = result.metrics.netProfit,
            profitFactor = result.metrics.profitFactor,
            returnPercent = result.metrics.returnPercent,
            maxDrawdownPercent = result.metrics.maxDrawdownPercent,
            expectancy = result.metrics.expectancy,
            averageR = if (markers.isEmpty()) 0.0 else markers.map { it.rMultiple }.average(),
            markers = markers.toPersistentList(),
            equityCurve = result.equityCurve.toPersistentList(),
            testedBars = result.equityCurve.size,
            testedFromTimestamp = result.startDate,
            testedThroughTimestamp = result.endDate,
            rangeCoverageComplete = rangeCoverageComplete,
            historyNotice = historyNotice,
            sourceBarsAtRun = sourceBarCount,
            sourceNewestTimestampAtRun = sourceNewestTimestamp,
            lastRunTime = nowMillis,
        )
    }

    private const val PNL_EPSILON = 1e-9
}
