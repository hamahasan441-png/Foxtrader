package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import javax.inject.Inject

/**
 * AI-Scored Backtest Engine.
 *
 * Wraps the standard [BacktestEngine] and evaluates every trade entry against
 * the AI decision pipeline (orchestrator → MasterDecisionEngine). Each trade
 * is annotated with:
 *   - Whether the AI would have approved it ([BacktestTrade.aiApproved])
 *   - The signal grade ([BacktestTrade.aiGrade])
 *   - The aggregate confidence and confluence count
 *
 * NON-REPAINTING guarantees:
 *   - Primary candles: trimmed to [0..entryIndex] (inclusive).
 *   - HTF candles: trimmed so that only CLOSED bars whose open time + bar
 *     duration ≤ decision timestamp are visible. A bar that was still forming
 *     at the decision moment is excluded entirely, matching how live trading
 *     works — a higher-timeframe bar is not readable until it closes.
 *
 * Pure domain logic — no Android dependencies.
 */
class AiScoredBacktestEngine @Inject constructor(
    private val backtestEngine: BacktestEngine,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
) {

    fun updateConfig(config: BacktestConfig) { backtestEngine.updateConfig(config) }
    fun getConfig(): BacktestConfig = backtestEngine.getConfig()

    operator fun invoke(
        candles: List<Candle>,
        strategy: StrategyFunction,
        symbol: String = "UNKNOWN",
        timeframe: Timeframe = Timeframe.M15,
        htfCandles: Map<Timeframe, List<Candle>> = emptyMap(),
        dataSource: CandleSource = CandleSource.LIVE,
    ): BacktestResult {
        val baseResult = backtestEngine(candles, strategy, symbol, timeframe)

        if (baseResult.trades.isEmpty()) {
            return baseResult.copy(aiScoringEnabled = true, aiApprovalRate = null)
        }

        val scoredTrades = baseResult.trades.map { trade ->
            scoreTradeEntry(trade, candles, symbol, timeframe, htfCandles, dataSource)
        }

        val approved = scoredTrades.filter { it.aiApproved == true }
        val approvalRate = if (scoredTrades.isNotEmpty()) {
            (approved.size.toDouble() / scoredTrades.size) * 100.0
        } else null

        val filteredMetrics = if (approved.size >= 2) {
            computeFilteredMetrics(approved, baseResult.config.initialBalance)
        } else null

        return baseResult.copy(
            trades = scoredTrades,
            aiScoringEnabled = true,
            aiApprovalRate = approvalRate,
            aiFilteredMetrics = filteredMetrics,
        )
    }

    private fun scoreTradeEntry(
        trade: BacktestTrade,
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        htfCandles: Map<Timeframe, List<Candle>>,
        dataSource: CandleSource,
    ): BacktestTrade {
        val visibleCandles = candles.subList(0, (trade.entryIndex + 1).coerceAtMost(candles.size))
        if (visibleCandles.size < MIN_BARS_FOR_AI) return trade

        // Point-in-time HTF filter: the AI may only see HTF bars that had
        // CLOSED at or before the moment the entry bar closed.
        // Formula: bar.timestamp + htf.minutes*60_000 <= entryTime + ltf.minutes*60_000
        val decisionTimestamp = trade.entryTime + timeframe.minutes * 60_000L
        val pointInTimeHtf = htfCandles.mapValues { (htf, bars) ->
            val htfBarMs = htf.minutes * 60_000L
            bars.filter { bar -> bar.timestamp + htfBarMs <= decisionTimestamp }
        }.filterValues { it.isNotEmpty() }

        val context = AgentContext(
            symbol = symbol,
            timeframe = timeframe,
            candles = visibleCandles,
            mtfCandles = pointInTimeHtf,
        )
        val orchestratorResult = orchestrator.analyze(context)
        val decision = decisionEngine.evaluate(orchestratorResult, dataSource)

        return trade.copy(
            aiApproved = decision.approved,
            aiGrade = decision.grade.name,
            aiConfidence = decision.confidence,
            aiConfluenceCount = decision.confluencePresent.size,
        )
    }

    private fun computeFilteredMetrics(
        trades: List<BacktestTrade>,
        initialBalance: Double,
    ): com.foxtrader.app.domain.model.BacktestMetrics {
        val wins   = trades.filter { it.netPnL > 0 }
        val losses = trades.filter { it.netPnL <= 0 }
        val grossProfit = wins.sumOf { it.netPnL }
        val grossLoss   = kotlin.math.abs(losses.sumOf { it.netPnL })
        val netProfit   = grossProfit - grossLoss
        val winRate     = if (trades.isNotEmpty()) (wins.size.toDouble() / trades.size) * 100.0 else 0.0
        val avgWin      = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
        val avgLoss     = if (losses.isNotEmpty()) grossLoss / losses.size else 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss
                           else if (grossProfit > 0) Double.MAX_VALUE else 0.0
        val expectancy   = (winRate / 100.0) * avgWin - ((100.0 - winRate) / 100.0) * avgLoss
        val safeDenom    = if (initialBalance > 0.0) initialBalance else 1.0

        return com.foxtrader.app.domain.model.BacktestMetrics(
            netProfit = netProfit, grossProfit = grossProfit, grossLoss = grossLoss,
            totalTrades = trades.size, winningTrades = wins.size, losingTrades = losses.size,
            winRate = winRate, profitFactor = profitFactor, expectancy = expectancy,
            averageTrade = if (trades.isNotEmpty()) netProfit / trades.size else 0.0,
            averageWin = avgWin, averageLoss = avgLoss,
            largestWin  = wins.maxOfOrNull { it.netPnL } ?: 0.0,
            largestLoss = losses.minOfOrNull { it.netPnL } ?: 0.0,
            maxDrawdown = 0.0, maxDrawdownPercent = 0.0,
            sharpeRatio = 0.0, sortinoRatio = 0.0, calmarRatio = 0.0, recoveryFactor = 0.0,
            avgHoldingBars = trades.sumOf { it.holdingBars }.toDouble() / trades.size,
            maxConsecutiveWins = 0, maxConsecutiveLosses = 0,
            finalBalance  = initialBalance + netProfit,
            returnPercent = (netProfit / safeDenom) * 100.0,
            totalCommission = trades.sumOf { it.commission },
        )
    }

    private companion object { const val MIN_BARS_FOR_AI = 50 }
}
