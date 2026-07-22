package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Strategy function type.
 * Called bar-by-bar with candles[0..index] (NO look-ahead). Returns a signal or null.
 */
typealias StrategyFunction = (candles: List<Candle>, index: Int) -> StrategySignal?

/**
 * Professional Backtester Engine.
 *
 * Features:
 * - Bar-by-bar execution, NO look-ahead (strategy only sees candles up to current bar)
 * - Variable spread modeling (widens with volatility)
 * - Commission and slippage
 * - Full metrics: Sharpe, Sortino, Calmar, Profit Factor, Win Rate, Drawdown, Expectancy
 * - Equity curve with per-bar drawdown tracking
 *
 * Pure domain logic — no platform dependencies.
 */
class BacktestEngine @Inject constructor() {

    private var config: BacktestConfig = BacktestConfig()

    /**
     * Run a backtest over candle data with a strategy function.
     */
    operator fun invoke(
        candles: List<Candle>,
        strategy: StrategyFunction,
        symbol: String = "UNKNOWN",
        timeframe: Timeframe = Timeframe.M15,
    ): BacktestResult {
        val trades = mutableListOf<BacktestTrade>()
        val equityCurve = mutableListOf<EquityPoint>()
        var balance = config.initialBalance
        var peakBalance = balance
        var tradeId = 0
        var openTrade: Pair<StrategySignal, Double>? = null // signal + volume

        for (i in candles.indices) {
            val candle = candles[i]

            // Manage open trade: check SL/TP hit intra-bar
            if (openTrade != null) {
                val (signal, volume) = openTrade
                val exit = checkTradeExit(signal, candle)
                if (exit != null) {
                    val trade = buildTrade(
                        id = ++tradeId,
                        signal = signal,
                        volume = volume,
                        exitIndex = i,
                        exitTime = candle.timestamp,
                        exitPrice = exit.first,
                        reason = exit.second,
                        balanceBefore = balance,
                    )
                    balance += trade.netPnL
                    trades += trade.copy(balanceAfter = balance)
                    openTrade = null
                    peakBalance = max(peakBalance, balance)
                }
            }

            // Look for new entry (only if flat)
            if (openTrade == null) {
                // CRITICAL: pass only candles up to and including i (no look-ahead)
                val signal = strategy(candles.subList(0, i + 1), i)
                if (signal != null) {
                    val volume = signal.volume ?: calculateVolume(balance, signal)
                    openTrade = signal to volume
                }
            }

            // Record equity point
            val dd = peakBalance - balance
            equityCurve += EquityPoint(
                index = i,
                timestamp = candle.timestamp,
                balance = balance,
                drawdown = dd,
                drawdownPercent = if (peakBalance > 0) (dd / peakBalance) * 100.0 else 0.0,
            )
        }

        // Close remaining open trade at last candle
        if (openTrade != null) {
            val (signal, volume) = openTrade
            val lastCandle = candles.last()
            val trade = buildTrade(
                id = ++tradeId,
                signal = signal,
                volume = volume,
                exitIndex = candles.lastIndex,
                exitTime = lastCandle.timestamp,
                exitPrice = lastCandle.close,
                reason = ExitReason.END,
                balanceBefore = balance,
            )
            balance += trade.netPnL
            trades += trade.copy(balanceAfter = balance)
        }

        val metrics = calculateMetrics(trades, equityCurve)

        return BacktestResult(
            config = config,
            trades = trades,
            metrics = metrics,
            equityCurve = equityCurve,
            startDate = candles.firstOrNull()?.timestamp ?: 0L,
            endDate = candles.lastOrNull()?.timestamp ?: 0L,
            durationDays = if (candles.size > 1) {
                (candles.last().timestamp - candles.first().timestamp) / 86_400_000.0
            } else 0.0,
            symbol = symbol,
            timeframe = timeframe,
        )
    }

    // ========================================================================
    // TRADE EXIT LOGIC
    // ========================================================================

    private fun checkTradeExit(signal: StrategySignal, candle: Candle): Pair<Double, ExitReason>? {
        val spread = getSpread(candle)

        if (signal.direction == Direction.BULLISH) {
            // SL checked first (conservative)
            if (candle.low - spread <= signal.stopLoss) {
                return (signal.stopLoss - config.slippage) to ExitReason.SL
            }
            if (candle.high >= signal.takeProfit) {
                return signal.takeProfit to ExitReason.TP
            }
        } else {
            if (candle.high + spread >= signal.stopLoss) {
                return (signal.stopLoss + config.slippage) to ExitReason.SL
            }
            if (candle.low <= signal.takeProfit) {
                return signal.takeProfit to ExitReason.TP
            }
        }
        return null
    }

    private fun buildTrade(
        id: Int,
        signal: StrategySignal,
        volume: Double,
        exitIndex: Int,
        exitTime: Long,
        exitPrice: Double,
        reason: ExitReason,
        balanceBefore: Double,
    ): BacktestTrade {
        val priceDiff = if (signal.direction == Direction.BULLISH) {
            exitPrice - signal.entry
        } else {
            signal.entry - exitPrice
        }

        val grossPnL = priceDiff * volume * config.contractSize
        val commission = config.commissionPerLot * volume
        val netPnL = grossPnL - commission

        val risk = abs(signal.entry - signal.stopLoss)
        val rMultiple = if (risk > 0) priceDiff / risk else 0.0

        return BacktestTrade(
            id = id,
            direction = signal.direction,
            entryIndex = signal.index,
            entryTime = signal.timestamp,
            entryPrice = signal.entry,
            exitIndex = exitIndex,
            exitTime = exitTime,
            exitPrice = exitPrice,
            volume = volume,
            grossPnL = grossPnL,
            commission = commission,
            netPnL = netPnL,
            rMultiple = rMultiple,
            exitReason = reason,
            balanceAfter = balanceBefore, // Updated by caller
            setupType = signal.setupType,
            holdingBars = exitIndex - signal.index,
        )
    }

    private fun calculateVolume(balance: Double, signal: StrategySignal): Double {
        val riskAmount = balance * (config.riskPercent / 100.0)
        val stopDistance = abs(signal.entry - signal.stopLoss)
        if (stopDistance == 0.0) return 0.01
        val volume = riskAmount / (stopDistance * config.contractSize)
        return max(0.01, (volume * 100).roundToInt() / 100.0)
    }

    private fun getSpread(candle: Candle): Double {
        if (!config.variableSpread) return config.spread
        val range = candle.high - candle.low
        val multiplier = min(3.0, 1.0 + range / (config.spread * 100))
        return config.spread * multiplier
    }

    // ========================================================================
    // METRICS CALCULATION
    // ========================================================================

    private fun calculateMetrics(trades: List<BacktestTrade>, equity: List<EquityPoint>): BacktestMetrics {
        if (trades.isEmpty()) return emptyMetrics()

        val wins = trades.filter { it.netPnL > 0 }
        val losses = trades.filter { it.netPnL < 0 }

        val grossProfit = wins.sumOf { it.netPnL }
        val grossLoss = abs(losses.sumOf { it.netPnL })
        val netProfit = grossProfit - grossLoss

        val winRate = (wins.size.toDouble() / trades.size) * 100.0
        val avgWin = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
        val avgLoss = if (losses.isNotEmpty()) grossLoss / losses.size else 0.0

        val lossRate = losses.size.toDouble() / trades.size
        val expectancy = (winRate / 100.0) * avgWin - lossRate * avgLoss

        val maxDD = equity.maxOfOrNull { it.drawdown } ?: 0.0
        val maxDDPercent = equity.maxOfOrNull { it.drawdownPercent } ?: 0.0

        val returns = computeReturns(trades)
        val sharpe = calculateSharpe(returns)
        val sortino = calculateSortino(returns)

        val finalBalance = config.initialBalance + netProfit
        val returnPercent = (netProfit / config.initialBalance) * 100.0
        val calmar = if (maxDDPercent > 0) returnPercent / maxDDPercent else 0.0
        val recoveryFactor = if (maxDD > 0) netProfit / maxDD else 0.0

        val streaks = calculateStreaks(trades)

        return BacktestMetrics(
            netProfit = netProfit,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            totalTrades = trades.size,
            winningTrades = wins.size,
            losingTrades = losses.size,
            winRate = winRate,
            profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) Double.MAX_VALUE else 0.0,
            expectancy = expectancy,
            averageTrade = netProfit / trades.size,
            averageWin = avgWin,
            averageLoss = avgLoss,
            largestWin = wins.maxOfOrNull { it.netPnL } ?: 0.0,
            largestLoss = losses.minOfOrNull { it.netPnL } ?: 0.0,
            maxDrawdown = maxDD,
            maxDrawdownPercent = maxDDPercent,
            sharpeRatio = sharpe,
            sortinoRatio = sortino,
            calmarRatio = calmar,
            recoveryFactor = recoveryFactor,
            avgHoldingBars = trades.sumOf { it.holdingBars }.toDouble() / trades.size,
            maxConsecutiveWins = streaks.first,
            maxConsecutiveLosses = streaks.second,
            finalBalance = finalBalance,
            returnPercent = returnPercent,
            totalCommission = trades.sumOf { it.commission },
        )
    }

    private fun computeReturns(trades: List<BacktestTrade>): DoubleArray {
        val returns = DoubleArray(trades.size)
        var balance = config.initialBalance
        for ((i, t) in trades.withIndex()) {
            returns[i] = t.netPnL / balance
            balance += t.netPnL
        }
        return returns
    }

    /** Sharpe Ratio — annualized (assuming ~252 trading periods). */
    private fun calculateSharpe(returns: DoubleArray): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val stdDev = sqrt(variance)
        if (stdDev == 0.0) return 0.0
        return (mean / stdDev) * sqrt(252.0)
    }

    /** Sortino Ratio — uses downside deviation only. */
    private fun calculateSortino(returns: DoubleArray): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val downsideVar = returns.filter { it < 0 }.sumOf { it * it } / returns.size
        val downsideDev = sqrt(downsideVar)
        if (downsideDev == 0.0) return if (mean > 0) Double.MAX_VALUE else 0.0
        return (mean / downsideDev) * sqrt(252.0)
    }

    private fun calculateStreaks(trades: List<BacktestTrade>): Pair<Int, Int> {
        var maxWins = 0; var maxLosses = 0; var curWins = 0; var curLosses = 0
        for (t in trades) {
            if (t.netPnL > 0) { curWins++; curLosses = 0; maxWins = max(maxWins, curWins) }
            else if (t.netPnL < 0) { curLosses++; curWins = 0; maxLosses = max(maxLosses, curLosses) }
        }
        return maxWins to maxLosses
    }

    private fun emptyMetrics(): BacktestMetrics = BacktestMetrics(
        netProfit = 0.0, grossProfit = 0.0, grossLoss = 0.0, totalTrades = 0,
        winningTrades = 0, losingTrades = 0, winRate = 0.0, profitFactor = 0.0,
        expectancy = 0.0, averageTrade = 0.0, averageWin = 0.0, averageLoss = 0.0,
        largestWin = 0.0, largestLoss = 0.0, maxDrawdown = 0.0, maxDrawdownPercent = 0.0,
        sharpeRatio = 0.0, sortinoRatio = 0.0, calmarRatio = 0.0, recoveryFactor = 0.0,
        avgHoldingBars = 0.0, maxConsecutiveWins = 0, maxConsecutiveLosses = 0,
        finalBalance = config.initialBalance, returnPercent = 0.0, totalCommission = 0.0,
    )

    // ========================================================================
    // CONFIG
    // ========================================================================

    fun updateConfig(newConfig: BacktestConfig) { config = newConfig }
    fun getConfig(): BacktestConfig = config
}
