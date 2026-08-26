package com.foxtrader.app.domain.usecase.binary

import com.foxtrader.app.domain.model.BinaryBacktestConfig
import com.foxtrader.app.domain.model.BinaryBacktestMetrics
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestTrade
import com.foxtrader.app.domain.model.BinaryOutcome
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Fixed-expiry backtester for 1-minute directional contracts.
 *
 * Signal on closed bar i -> entry at OPEN of i+1 -> settle at CLOSE of i+3
 * for the default three-minute expiry. No candle after i can influence signal i.
 */
@Singleton
class BinaryBacktestEngine @Inject constructor(
    private val signalEngine: DerivBinary3mSignalEngine,
) {
    /**
     * @param entryWindowStartMillis when set, no contract may be *signalled*
     *   before this timestamp. Earlier bars are still fed to the signal engine
     *   so its warm-up is identical to a full-history run — they simply cannot
     *   open a trade. This is what makes "test only March 2024" measure March
     *   rather than March plus whatever warm-up bars happened to be attached.
     * @param entryWindowEndMillis the inclusive upper bound for signal bars.
     *   Bars after it remain available for settlement, so a contract opened on
     *   the final day of the window still expires against real prices instead
     *   of being silently dropped.
     *
     * The window is expressed in timestamps rather than indices deliberately:
     * this engine re-sorts and de-duplicates its input, so caller-side indices
     * are not guaranteed to survive into the loop below.
     */
    operator fun invoke(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        config: BinaryBacktestConfig = BinaryBacktestConfig(),
        entryWindowStartMillis: Long? = null,
        entryWindowEndMillis: Long? = null,
    ): BinaryBacktestResult {
        require(timeframe == Timeframe.M1) { "Deriv 3-minute binary backtest requires 1-minute candles." }
        require(config.initialBalance.isFinite() && config.initialBalance > 0.0) { "Initial balance must be positive." }
        require(config.riskPercent.isFinite() && config.riskPercent in 0.01..10.0) { "Risk percent must be between 0.01 and 10." }
        require(config.payoutRatio.isFinite() && config.payoutRatio in 0.01..2.0) { "Payout ratio must be between 0.01 and 2.0." }
        require(config.expiryBars >= 1) { "Expiry bars must be at least 1." }

        val ordered = candles
            .asSequence()
            .filter(::isValidBar)
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()

        if (ordered.size <= DerivBinary3mSignalEngine.MIN_BARS + config.expiryBars + 1) {
            return emptyResult(ordered, symbol, timeframe, config)
        }

        val signalsByIndex = signalEngine.evaluateAll(ordered, config.minConfidence).associateBy { it.signalIndex }
        val trades = mutableListOf<BinaryBacktestTrade>()
        val equity = mutableListOf<EquityPoint>()
        var balance = config.initialBalance
        var peakBalance = balance
        var nextEligibleSignalIndex = 0
        var tradeId = 1

        for (signalIndex in ordered.indices) {
            val signal = signalsByIndex[signalIndex] ?: continue
            // Outside the selected research period this bar contributes warm-up
            // and settlement prices only — it may not open a contract.
            if (entryWindowStartMillis != null && signal.timestamp < entryWindowStartMillis) continue
            if (entryWindowEndMillis != null && signal.timestamp > entryWindowEndMillis) continue
            if (!config.allowOverlappingContracts && signalIndex < nextEligibleSignalIndex) continue

            val entryIndex = signalIndex + 1
            val expiryIndex = signalIndex + config.expiryBars
            if (entryIndex >= ordered.size || expiryIndex >= ordered.size) continue

            val entry = ordered[entryIndex]
            val expiry = ordered[expiryIndex]
            val entryPrice = entry.open
            val expiryPrice = expiry.close
            if (!entryPrice.isFinite() || !expiryPrice.isFinite() || entryPrice <= 0.0 || expiryPrice <= 0.0) continue

            val stake = (balance * (config.riskPercent / 100.0)).coerceAtMost(balance)
            if (!stake.isFinite() || stake <= 0.0) break

            val outcome = settle(signal.direction, entryPrice, expiryPrice)
            val pnl = when (outcome) {
                BinaryOutcome.WIN -> stake * config.payoutRatio
                BinaryOutcome.LOSS -> -stake
                BinaryOutcome.TIE -> 0.0
            }
            balance = (balance + pnl).coerceAtLeast(0.0)
            peakBalance = max(peakBalance, balance)
            val drawdown = peakBalance - balance
            val drawdownPct = if (peakBalance > 0.0) drawdown / peakBalance * 100.0 else 0.0

            trades += BinaryBacktestTrade(
                id = tradeId++,
                direction = signal.direction,
                signalIndex = signal.signalIndex,
                signalTime = signal.timestamp,
                entryIndex = entryIndex,
                entryTime = entry.timestamp,
                entryPrice = entryPrice,
                expiryIndex = expiryIndex,
                expiryTime = expiry.timestamp + 60_000L,
                expiryPrice = expiryPrice,
                outcome = outcome,
                stake = stake,
                pnl = pnl,
                balanceAfter = balance,
                confidence = signal.confidence,
                setupType = signal.setupType,
            )
            equity += EquityPoint(
                index = expiryIndex,
                timestamp = expiry.timestamp,
                balance = balance,
                drawdown = drawdown,
                drawdownPercent = drawdownPct,
            )

            if (!config.allowOverlappingContracts) {
                // A setup confirmed on the expiry bar may enter on the next bar.
                nextEligibleSignalIndex = expiryIndex
            }
            if (balance <= 0.0) break
        }

        return BinaryBacktestResult(
            config = config,
            symbol = symbol,
            timeframe = timeframe,
            trades = trades,
            metrics = calculateMetrics(trades, equity, config),
            equityCurve = equity,
            startDate = ordered.firstOrNull()?.timestamp ?: 0L,
            endDate = ordered.lastOrNull()?.timestamp ?: 0L,
        )
    }

    private fun settle(direction: Direction, entry: Double, expiry: Double): BinaryOutcome {
        val epsilon = max(abs(entry) * 1e-10, 1e-12)
        val delta = expiry - entry
        if (abs(delta) <= epsilon) return BinaryOutcome.TIE
        return when (direction) {
            Direction.BULLISH -> if (delta > 0.0) BinaryOutcome.WIN else BinaryOutcome.LOSS
            Direction.BEARISH -> if (delta < 0.0) BinaryOutcome.WIN else BinaryOutcome.LOSS
        }
    }

    private fun calculateMetrics(
        trades: List<BinaryBacktestTrade>,
        equity: List<EquityPoint>,
        config: BinaryBacktestConfig,
    ): BinaryBacktestMetrics {
        val wins = trades.count { it.outcome == BinaryOutcome.WIN }
        val losses = trades.count { it.outcome == BinaryOutcome.LOSS }
        val ties = trades.count { it.outcome == BinaryOutcome.TIE }
        val decided = wins + losses
        val winRate = if (decided > 0) wins.toDouble() / decided * 100.0 else 0.0
        val breakEven = 100.0 / (1.0 + config.payoutRatio)
        val net = trades.sumOf { it.pnl }
        val grossWin = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = abs(trades.filter { it.pnl < 0.0 }.sumOf { it.pnl })
        val maxDd = equity.maxOfOrNull { it.drawdown } ?: 0.0
        val maxDdPct = equity.maxOfOrNull { it.drawdownPercent } ?: 0.0
        val streaks = streaks(trades)
        val finalBalance = trades.lastOrNull()?.balanceAfter ?: config.initialBalance

        return BinaryBacktestMetrics(
            totalTrades = trades.size,
            wins = wins,
            losses = losses,
            ties = ties,
            winRate = winRate,
            breakEvenWinRate = breakEven,
            edgeVsBreakEven = winRate - breakEven,
            netProfit = net,
            returnPercent = net / config.initialBalance * 100.0,
            expectancyPerTrade = if (trades.isNotEmpty()) net / trades.size else 0.0,
            profitFactor = if (grossLoss > 0.0) grossWin / grossLoss else if (grossWin > 0.0) Double.MAX_VALUE else 0.0,
            maxDrawdown = maxDd,
            maxDrawdownPercent = maxDdPct,
            maxConsecutiveWins = streaks.first,
            maxConsecutiveLosses = streaks.second,
            finalBalance = finalBalance,
        )
    }

    private fun streaks(trades: List<BinaryBacktestTrade>): Pair<Int, Int> {
        var maxWins = 0
        var maxLosses = 0
        var wins = 0
        var losses = 0
        trades.forEach { trade ->
            when (trade.outcome) {
                BinaryOutcome.WIN -> {
                    wins++
                    losses = 0
                    maxWins = max(maxWins, wins)
                }
                BinaryOutcome.LOSS -> {
                    losses++
                    wins = 0
                    maxLosses = max(maxLosses, losses)
                }
                BinaryOutcome.TIE -> {
                    wins = 0
                    losses = 0
                }
            }
        }
        return maxWins to maxLosses
    }

    private fun emptyResult(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        config: BinaryBacktestConfig,
    ): BinaryBacktestResult = BinaryBacktestResult(
        config = config,
        symbol = symbol,
        timeframe = timeframe,
        trades = emptyList(),
        metrics = calculateMetrics(emptyList(), emptyList(), config),
        equityCurve = emptyList(),
        startDate = candles.firstOrNull()?.timestamp ?: 0L,
        endDate = candles.lastOrNull()?.timestamp ?: 0L,
    )

    private fun isValidBar(c: Candle): Boolean = c.timestamp > 0L &&
        c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
        c.open > 0.0 && c.high >= maxOf(c.open, c.close) && c.low <= minOf(c.open, c.close) && c.low > 0.0
}
