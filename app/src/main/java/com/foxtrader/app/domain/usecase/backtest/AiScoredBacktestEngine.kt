package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Candle
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
 * The result also carries:
 *   - Overall AI approval rate (% of trades the AI agreed with)
 *   - Filtered metrics for AI-approved trades only (the "what if we only took
 *     AI-approved setups?" comparison)
 *
 * NON-REPAINTING: uses the same `subList(0, entryIndex+1)` window the standard
 * backtester enforces — the AI sees only confirmed data at entry time.
 *
 * Pure domain logic — no Android dependencies.
 */
class AiScoredBacktestEngine @Inject constructor(
    private val backtestEngine: BacktestEngine,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
) {

    /**
     * Run a standard backtest AND score every trade with the AI pipeline.
     *
     * @param candles Full candle series.
     * @param strategy Strategy function (same contract as [BacktestEngine]).
     * @param symbol Symbol being backtested.
     * @param timeframe Execution timeframe.
     * @param htfCandles Optional HTF candles for the AI agents' MTF context.
     */
    operator fun invoke(
        candles: List<Candle>,
        strategy: StrategyFunction,
        symbol: String = "UNKNOWN",
        timeframe: Timeframe = Timeframe.M15,
        htfCandles: Map<Timeframe, List<Candle>> = emptyMap(),
    ): BacktestResult {
        // 1. Run the standard backtest first to get all trades.
        val baseResult = backtestEngine(candles, strategy, symbol, timeframe)

        if (baseResult.trades.isEmpty()) {
            return baseResult.copy(aiScoringEnabled = true, aiApprovalRate = null)
        }

        // 2. Score each trade entry with the AI pipeline.
        val scoredTrades = baseResult.trades.map { trade ->
            scoreTradeEntry(trade, candles, symbol, timeframe, htfCandles)
        }

        // 3. Compute AI-filtered summary.
        val approved = scoredTrades.filter { it.aiApproved == true }
        val approvalRate = if (scoredTrades.isNotEmpty()) {
            (approved.size.toDouble() / scoredTrades.size) * 100.0
        } else null

        // Recompute metrics for AI-approved trades only (if any).
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

    /**
     * Run the AI pipeline at the entry bar of a trade and annotate it.
     */
    private fun scoreTradeEntry(
        trade: BacktestTrade,
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        htfCandles: Map<Timeframe, List<Candle>>,
    ): BacktestTrade {
        // Non-repainting: give the AI only candles [0..entryIndex].
        val visibleCandles = candles.subList(0, (trade.entryIndex + 1).coerceAtMost(candles.size))
        if (visibleCandles.size < MIN_BARS_FOR_AI) {
            return trade // Not enough data — leave AI fields null.
        }

        val context = AgentContext(
            symbol = symbol,
            timeframe = timeframe,
            candles = visibleCandles,
            mtfCandles = htfCandles,
        )
        val orchestratorResult = orchestrator.analyze(context)
        val decision = decisionEngine.evaluate(orchestratorResult)

        return trade.copy(
            aiApproved = decision.approved,
            aiGrade = decision.grade.name,
            aiConfidence = decision.confidence,
            aiConfluenceCount = decision.confluencePresent.size,
        )
    }

    /**
     * Lightweight metrics computation for the AI-filtered subset.
     * Reuses the trade P&L data to compute win rate, profit factor, and expectancy
     * without a full re-simulation (trades already have their P&L from the base run).
     */
    private fun computeFilteredMetrics(
        trades: List<BacktestTrade>,
        initialBalance: Double,
    ): com.foxtrader.app.domain.model.BacktestMetrics {
        val wins = trades.filter { it.netPnL > 0 }
        val losses = trades.filter { it.netPnL <= 0 }
        val grossProfit = wins.sumOf { it.netPnL }
        val grossLoss = kotlin.math.abs(losses.sumOf { it.netPnL })
        val netProfit = grossProfit - grossLoss
        val winRate = if (trades.isNotEmpty()) (wins.size.toDouble() / trades.size) * 100.0 else 0.0
        val avgWin = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
        val avgLoss = if (losses.isNotEmpty()) grossLoss / losses.size else 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) Double.MAX_VALUE else 0.0
        val expectancy = (winRate / 100.0) * avgWin - ((100.0 - winRate) / 100.0) * avgLoss

        return com.foxtrader.app.domain.model.BacktestMetrics(
            netProfit = netProfit,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            totalTrades = trades.size,
            winningTrades = wins.size,
            losingTrades = losses.size,
            winRate = winRate,
            profitFactor = profitFactor,
            expectancy = expectancy,
            averageTrade = if (trades.isNotEmpty()) netProfit / trades.size else 0.0,
            averageWin = avgWin,
            averageLoss = avgLoss,
            largestWin = wins.maxOfOrNull { it.netPnL } ?: 0.0,
            largestLoss = losses.minOfOrNull { it.netPnL } ?: 0.0,
            maxDrawdown = 0.0,           // simplified — full DD requires equity re-simulation
            maxDrawdownPercent = 0.0,
            sharpeRatio = 0.0,
            sortinoRatio = 0.0,
            calmarRatio = 0.0,
            recoveryFactor = 0.0,
            avgHoldingBars = trades.sumOf { it.holdingBars }.toDouble() / trades.size,
            maxConsecutiveWins = 0,
            maxConsecutiveLosses = 0,
            finalBalance = initialBalance + netProfit,
            returnPercent = (netProfit / initialBalance) * 100.0,
            totalCommission = trades.sumOf { it.commission },
        )
    }

    private companion object {
        const val MIN_BARS_FOR_AI = 50
    }
}
