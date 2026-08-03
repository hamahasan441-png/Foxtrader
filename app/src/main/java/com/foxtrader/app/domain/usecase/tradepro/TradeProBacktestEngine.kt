package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.TradeManagementAction
import com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult
import com.foxtrader.app.domain.model.tradepro.TradeProBacktestTrade
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Replays the **full** TRADEPRO lifecycle over historical candles, bar-by-bar, using the real
 * production engines:
 *
 * 1. [TradeProSignalEngine] reads the market at each bar and, only when a setup reaches
 *    [com.foxtrader.app.domain.model.tradepro.SetupStage.EXECUTE], hands over an entry.
 * 2. [TradeManagementEngine] then runs that entry through the 3-contract T1/T2/runner plan,
 *    one bar at a time, until it closes (stop, runner target, or end-of-data).
 * 3. Closed trades are aggregated into a point-based [TradeProBacktestResult].
 *
 * This is deliberately different from the generic backtester (simple entry + SL/TP): it exercises the
 * actual staged-exit management, so the report reflects how the framework really trades.
 *
 * Discipline mirrored from the framework:
 * - **One position at a time.** No pyramiding or overlapping setups — focus over frequency.
 * - **No look-ahead.** The bar that *generates* a setup is never used to fill it; management starts on
 *   the following bar.
 * - A trailing [run]'s `analysisWindow` feeds the signal engine so cost stays roughly linear.
 * - **Multi-timeframe.** The trailing window is resampled up the timeframe ladder (via
 *   [TimeframeResampler]) and fed to the signal engine as higher-timeframe context, so the same
 *   "HTF defines bias, LTF provides entry" filter that gates live signals also gates backtested ones.
 *   Because the window ends at the current bar, the resampled HTF view carries no future information.
 */
class TradeProBacktestEngine @Inject constructor(
    private val signalEngine: TradeProSignalEngine,
    private val managementEngine: TradeManagementEngine,
) {

    /**
     * @param baseTimeframe the timeframe [candles] are sampled at. Used to pick which higher
     *   timeframes to resample the trailing window into for MTF bias validation. Pass the actual
     *   series timeframe; the HTF ladder is derived from it.
     * @param analysisWindow number of trailing bars handed to the signal engine each evaluation.
     *   Large enough to see recent structure, bounded to keep the walk near-linear.
     * @param multiTimeframe when true (default), setups are validated against higher-timeframe bias
     *   just as they are live. Set false to measure the raw single-timeframe edge.
     */
    fun run(
        symbol: String,
        candles: List<Candle>,
        config: TradeProConfig = TradeProConfig(),
        baseTimeframe: Timeframe = Timeframe.H1,
        analysisWindow: Int = DEFAULT_ANALYSIS_WINDOW,
        multiTimeframe: Boolean = true,
    ): TradeProBacktestResult {
        if (candles.size <= TradeProSignalEngine.MIN_BARS) {
            return TradeProBacktestResult.empty(
                symbol,
                "Need more than ${TradeProSignalEngine.MIN_BARS} bars to backtest TRADEPRO.",
            )
        }

        val trades = ArrayList<TradeProBacktestTrade>()
        var open: OpenPosition? = null
        var requiredBreakevenWinRate = 0.0

        // Start once the signal engine has its minimum history; evaluate every bar thereafter.
        var i = TradeProSignalEngine.MIN_BARS
        while (i < candles.size) {
            val bar = candles[i]
            val position = open
            if (position == null) {
                // Flat: look for a fresh executable setup using history up to and including this bar.
                val from = max(0, i - analysisWindow + 1)
                val window = candles.subList(from, i + 1)
                val htfCandles: Map<Timeframe, List<Candle>> =
                    if (multiTimeframe) buildHtfContext(window, baseTimeframe) else emptyMap()
                val setup = signalEngine.analyze(symbol, window, config, htfCandles).setup
                if (setup != null && setup.isExecutable) {
                    if (trades.isEmpty()) {
                        requiredBreakevenWinRate = setup.managementPlan.breakevenWinRate
                    }
                    open = OpenPosition(
                        trade = managementEngine.openTrade(setup, config),
                        setup = setup,
                        entryTimestamp = bar.timestamp,
                    )
                }
                // Do NOT manage on the entry bar (avoid look-ahead); advance to the next bar.
            } else {
                val (updated, action) = managementEngine.tick(position.trade, bar)
                position.onTick(updated, action)
                if (updated.state == ManagedTradeState.CLOSED) {
                    trades += position.toTrade(exitTimestamp = bar.timestamp)
                    open = null
                }
            }
            i++
        }

        // Flatten any position still open at the end of the data at the last close.
        open?.let { position ->
            val last = candles.last()
            val closed = managementEngine.closeManually(position.trade, last.close)
            position.onTick(closed, TradeManagementAction.CloseManually)
            trades += position.toTrade(exitTimestamp = last.timestamp)
        }

        return aggregate(symbol, trades, requiredBreakevenWinRate)
    }

    // --- Aggregation ---

    private fun aggregate(
        symbol: String,
        trades: List<TradeProBacktestTrade>,
        requiredBreakevenWinRate: Double,
    ): TradeProBacktestResult {
        if (trades.isEmpty()) {
            return TradeProBacktestResult.empty(
                symbol,
                "No executable TRADEPRO setups triggered over this history — standing aside is a result too.",
            )
        }

        val total = trades.size
        val wins = trades.count { it.isWin }
        val losses = trades.count { it.isLoss }
        val breakeven = total - wins - losses
        val grossProfit = trades.filter { it.isWin }.sumOf { it.netPoints }
        val grossLoss = trades.filter { it.isLoss }.sumOf { -it.netPoints }
        val netPoints = trades.sumOf { it.netPoints }

        val profitFactor = when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

        // Equity + underwater (drawdown) curves in cumulative point-contracts.
        val equityCurve = ArrayList<Double>(total)
        val drawdownCurve = ArrayList<Double>(total)
        var running = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        for (t in trades) {
            running += t.netPoints
            equityCurve += running
            if (running > peak) peak = running
            val dd = peak - running
            drawdownCurve += dd
            if (dd > maxDrawdown) maxDrawdown = dd
        }

        var winStreak = 0
        var lossStreak = 0
        var maxWinStreak = 0
        var maxLossStreak = 0
        for (t in trades) {
            if (t.isWin) {
                winStreak++
                lossStreak = 0
            } else if (t.isLoss) {
                lossStreak++
                winStreak = 0
            } else {
                winStreak = 0
                lossStreak = 0
            }
            maxWinStreak = max(maxWinStreak, winStreak)
            maxLossStreak = max(maxLossStreak, lossStreak)
        }

        val winRate = wins.toDouble() / total
        val expectancy = netPoints / total
        val avgWin = if (wins > 0) grossProfit / wins else 0.0
        val avgLoss = if (losses > 0) grossLoss / losses else 0.0
        val rMultiples = trades.map { it.rMultiple }
        val avgR = rMultiples.sum() / total
        val payoffRatio = when {
            avgLoss > 0.0 -> avgWin / avgLoss
            avgWin > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

        // Van Tharp System Quality Number: mean(R) / sampleStdev(R) * sqrt(n).
        // Needs >= 2 trades and non-zero dispersion to be defined.
        val systemQualityNumber = if (total >= 2) {
            val variance = rMultiples.sumOf { (it - avgR) * (it - avgR) } / (total - 1)
            val stdev = sqrt(variance)
            if (stdev > 0.0) (avgR / stdev) * sqrt(total.toDouble()) else 0.0
        } else {
            0.0
        }

        val t1HitRate = trades.count { it.reachedT1 }.toDouble() / total
        val t2HitRate = trades.count { it.reachedT2 }.toDouble() / total
        val runnerHitRate = trades.count { it.reachedRunner }.toDouble() / total

        val narrative = buildString {
            append("$total trades: $wins W / $losses L (")
            append("${(winRate * 100).toInt()}% win rate). ")
            append("Net ${fmt(netPoints)} pts, expectancy ${fmt(expectancy)} pts/trade, ")
            append("PF ${if (profitFactor.isFinite()) fmt(profitFactor) else "∞"}. ")
            append("T1 ${(t1HitRate * 100).toInt()}% / T2 ${(t2HitRate * 100).toInt()}% / ")
            append("runner ${(runnerHitRate * 100).toInt()}%. ")
            if (requiredBreakevenWinRate > 0.0) {
                val verb = if (winRate >= requiredBreakevenWinRate) "clears" else "misses"
                append("Win rate $verb the ${(requiredBreakevenWinRate * 100).toInt()}% break-even bar.")
            }
        }

        return TradeProBacktestResult(
            symbol = symbol,
            trades = trades,
            totalTrades = total,
            wins = wins,
            losses = losses,
            breakeven = breakeven,
            winRate = winRate,
            netPoints = netPoints,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            profitFactor = profitFactor,
            expectancy = expectancy,
            avgWin = avgWin,
            avgLoss = avgLoss,
            avgR = avgR,
            maxDrawdownPoints = maxDrawdown,
            maxWinStreak = maxWinStreak,
            maxLossStreak = maxLossStreak,
            t1HitRate = t1HitRate,
            t2HitRate = t2HitRate,
            runnerHitRate = runnerHitRate,
            requiredBreakevenWinRate = requiredBreakevenWinRate,
            equityCurve = equityCurve,
            rMultiples = rMultiples,
            drawdownCurve = drawdownCurve,
            systemQualityNumber = systemQualityNumber,
            payoffRatio = payoffRatio,
            narrative = narrative,
        )
    }

    private fun fmt(v: Double): String = if (v.isFinite()) "%.1f".format(v) else "n/a"

    /**
     * Resample the trailing [window] into each higher timeframe in the ladder and keep those with
     * enough bars to read structure. The window ends at the current bar, so no future data leaks in.
     */
    private fun buildHtfContext(
        window: List<Candle>,
        baseTimeframe: Timeframe,
    ): Map<Timeframe, List<Candle>> {
        val ladder = higherTimeframes(baseTimeframe)
        if (ladder.isEmpty()) return emptyMap()
        val context = LinkedHashMap<Timeframe, List<Candle>>(ladder.size)
        for (tf in ladder) {
            val resampled = TimeframeResampler.resample(window, tf)
            if (resampled.size >= MIN_HTF_BARS) context[tf] = resampled
        }
        return context
    }

    /** The up-to-[MAX_HTF_COUNT] timeframes directly above [base], closest first (mirrors live MTF). */
    private fun higherTimeframes(base: Timeframe): List<Timeframe> {
        val idx = ORDERED_TIMEFRAMES.indexOf(base)
        if (idx < 0) return emptyList()
        return ORDERED_TIMEFRAMES.drop(idx + 1).take(MAX_HTF_COUNT)
    }

    /**
     * Mutable holder tracking one open position and which milestones it reached, so the final
     * [TradeProBacktestTrade] can report T1/T2/runner attainment without re-deriving it.
     */
    private class OpenPosition(
        var trade: ManagedTrade,
        val setup: TradeProSetup,
        val entryTimestamp: Long,
    ) {
        private var reachedT1 = false
        private var reachedT2 = false
        private var reachedRunner = false

        fun onTick(updated: ManagedTrade, action: TradeManagementAction?) {
            trade = updated
            when (action) {
                is TradeManagementAction.HitT1 -> reachedT1 = true
                is TradeManagementAction.HitT2 -> {
                    reachedT1 = true
                    reachedT2 = true
                }
                is TradeManagementAction.HitRunner -> {
                    reachedT1 = true
                    reachedT2 = true
                    reachedRunner = true
                }
                else -> Unit
            }
            // Safety net: infer from terminal state in case an action was coalesced.
            when (updated.state) {
                ManagedTradeState.T1_HIT -> reachedT1 = true
                ManagedTradeState.T2_HIT, ManagedTradeState.RUNNER -> {
                    reachedT1 = true
                    reachedT2 = true
                }
                else -> Unit
            }
        }

        fun toTrade(exitTimestamp: Long): TradeProBacktestTrade {
            val contracts = trade.contracts
            val totalRisk = setup.riskPoints * contracts
            val rMultiple = if (totalRisk > 0.0) trade.realizedPoints / totalRisk else 0.0
            return TradeProBacktestTrade(
                symbol = trade.symbol,
                direction = trade.direction,
                entryPrice = trade.entryPrice,
                entryTimestamp = entryTimestamp,
                exitTimestamp = exitTimestamp,
                contracts = contracts,
                riskPointsPerContract = setup.riskPoints,
                netPoints = trade.realizedPoints,
                rMultiple = rMultiple,
                reachedT1 = reachedT1,
                reachedT2 = reachedT2,
                reachedRunner = reachedRunner,
                exitReason = trade.exitReason ?: "Closed",
                confidence = setup.confidence,
                confluences = setup.confluences,
            )
        }
    }

    companion object {
        const val DEFAULT_ANALYSIS_WINDOW = 250

        /** Minimum resampled HTF bars needed before an HTF is trusted for a structural read. */
        const val MIN_HTF_BARS = 30

        /** Cap on higher timeframes fed per evaluation (mirrors MtfContextProvider). */
        const val MAX_HTF_COUNT = 3

        /** Timeframes ordered lowest -> highest, used to derive the HTF ladder. */
        private val ORDERED_TIMEFRAMES = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
    }
}
