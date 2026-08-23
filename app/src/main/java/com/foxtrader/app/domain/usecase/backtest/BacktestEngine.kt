package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
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
import kotlinx.coroutines.CancellationException
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
 * - Explicit fill timing: legacy signal-price or TradingView-style next-bar open
 * - Variable spread modeling (widens with volatility)
 * - Commission and directional slippage
 * - Conservative SL-before-TP handling when both are touched in one candle
 * - Full metrics: Sharpe, Sortino, Calmar, Profit Factor, Win Rate, Drawdown, Expectancy
 * - Equity curve with per-bar drawdown tracking
 *
 * Pure domain logic — no platform dependencies.
 */
class BacktestEngine @Inject constructor() {

    private var config: BacktestConfig = BacktestConfig()

    private data class PendingEntry(
        val signal: StrategySignal,
        /** Position size is frozen on the decision bar, before the future open exists. */
        val volume: Double,
    )

    private data class OpenPosition(
        /** Signal rewritten to the actual execution bar/price. */
        val executedSignal: StrategySignal,
        val volume: Double,
        /** Original closed bar where the strategy made the decision. */
        val signalIndex: Int,
        val signalTime: Long,
    )

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
        var pendingEntry: PendingEntry? = null
        var openTrade: OpenPosition? = null

        for (i in candles.indices) {
            val candle = candles[i]
            var openedThisBar = false

            // TradingView-style market timing: an order created from the prior
            // closed bar becomes executable at this bar's open. Crucially, size
            // was frozen on the signal bar, so the future open cannot influence
            // risk sizing.
            if (
                config.executionMode == BacktestExecutionMode.NEXT_BAR_OPEN &&
                openTrade == null &&
                pendingEntry != null
            ) {
                val pending = pendingEntry
                pendingEntry = null
                val executed = fillAtBarOpen(pending.signal, candle, i)
                if (executed != null) {
                    openTrade = OpenPosition(
                        executedSignal = executed,
                        volume = pending.volume,
                        signalIndex = pending.signal.index,
                        signalTime = pending.signal.timestamp,
                    )
                    openedThisBar = true
                }
            }

            // Manage an existing position. A next-bar-open position is eligible
            // for stops/targets throughout the SAME candle because its fill was
            // at the candle open. If the open itself gaps through a protection
            // level, close at the executable open rather than granting a stale
            // historical stop/target price that the market skipped over.
            if (openTrade != null) {
                val position = openTrade
                val signal = position.executedSignal
                val exit = if (openedThisBar) {
                    checkOpeningGapExit(signal) ?: checkTradeExit(signal, candle)
                } else {
                    checkTradeExit(signal, candle)
                }
                if (exit != null) {
                    val trade = buildTrade(
                        id = ++tradeId,
                        signal = signal,
                        volume = position.volume,
                        exitIndex = i,
                        exitTime = candle.timestamp,
                        exitPrice = exit.first,
                        reason = exit.second,
                        balanceBefore = balance,
                        signalIndex = position.signalIndex,
                        signalTime = position.signalTime,
                    )
                    balance += trade.netPnL
                    trades += trade.copy(balanceAfter = balance)
                    openTrade = null
                    peakBalance = max(peakBalance, balance)
                }
            }

            // Evaluate a new setup only while flat and with no queued order.
            if (openTrade == null && pendingEntry == null) {
                // CRITICAL: pass only candles up to and including i (no look-ahead).
                val signal = try {
                    strategy(candles.subList(0, i + 1), i)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // A broken user strategy is isolated to this bar instead of
                    // aborting the complete research run.
                    null
                }

                // A setup on the final bar has no later market data in which a
                // new position can be meaningfully tested. This also guarantees
                // NEXT_BAR_OPEN never invents a future fill candle.
                if (
                    signal != null &&
                    i < candles.lastIndex &&
                    signal.isExecutable(expectedIndex = i, expectedTimestamp = candle.timestamp)
                ) {
                    when (config.executionMode) {
                        BacktestExecutionMode.SIGNAL_PRICE -> {
                            val slippedSignal = applyEntrySlippage(signal)
                            if (slippedSignal.isExecutable(expectedIndex = i, expectedTimestamp = candle.timestamp)) {
                                val volume = slippedSignal.volume ?: calculateVolume(balance, slippedSignal)
                                if (volume.isFinite() && volume > 0.0) {
                                    openTrade = OpenPosition(
                                        executedSignal = slippedSignal,
                                        volume = volume,
                                        signalIndex = signal.index,
                                        signalTime = signal.timestamp,
                                    )
                                }
                            }
                        }

                        BacktestExecutionMode.NEXT_BAR_OPEN -> {
                            // Freeze risk from information available NOW. Using
                            // tomorrow's open to determine size would itself be a
                            // subtle look-ahead leak.
                            val volume = signal.volume ?: calculateVolume(balance, signal)
                            if (volume.isFinite() && volume > 0.0) {
                                pendingEntry = PendingEntry(signal = signal, volume = volume)
                            }
                        }
                    }
                }
            }

            // Record realized-equity point. The engine intentionally preserves
            // its existing realized-balance semantics rather than silently
            // changing reports to mark-to-market equity in this execution patch.
            val dd = peakBalance - balance
            equityCurve += EquityPoint(
                index = i,
                timestamp = candle.timestamp,
                balance = balance,
                drawdown = dd,
                drawdownPercent = if (peakBalance > 0) (dd / peakBalance) * 100.0 else 0.0,
            )
        }

        // Close a remaining position at the last candle close. A pending order
        // cannot remain here: signals on the final bar are deliberately ignored,
        // and a signal from the penultimate bar executes at the final bar open.
        if (openTrade != null) {
            val position = openTrade
            val signal = position.executedSignal
            val lastCandle = candles.last()
            val trade = buildTrade(
                id = ++tradeId,
                signal = signal,
                volume = position.volume,
                exitIndex = candles.lastIndex,
                exitTime = lastCandle.timestamp,
                exitPrice = lastCandle.close,
                reason = ExitReason.END,
                balanceBefore = balance,
                signalIndex = position.signalIndex,
                signalTime = position.signalTime,
            )
            balance += trade.netPnL
            trades += trade.copy(balanceAfter = balance)
            peakBalance = max(peakBalance, balance)

            // The point for the last bar was recorded before END liquidation.
            if (equityCurve.isNotEmpty()) {
                val drawdown = peakBalance - balance
                equityCurve[equityCurve.lastIndex] = EquityPoint(
                    index = candles.lastIndex,
                    timestamp = lastCandle.timestamp,
                    balance = balance,
                    drawdown = drawdown,
                    drawdownPercent = if (peakBalance > 0.0) (drawdown / peakBalance) * 100.0 else 0.0,
                )
            }
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
    // ENTRY / EXIT EXECUTION
    // ========================================================================

    private fun applyEntrySlippage(signal: StrategySignal): StrategySignal =
        if (signal.direction == Direction.BULLISH) {
            signal.copy(entry = signal.entry + config.slippage)
        } else {
            signal.copy(entry = signal.entry - config.slippage)
        }

    /** Fill a queued market entry at the next bar open with directional slippage. */
    private fun fillAtBarOpen(
        signal: StrategySignal,
        candle: Candle,
        executionIndex: Int,
    ): StrategySignal? {
        if (!candle.open.isFinite() || candle.open <= 0.0) return null
        val fill = if (signal.direction == Direction.BULLISH) {
            candle.open + config.slippage
        } else {
            candle.open - config.slippage
        }
        if (!fill.isFinite() || fill <= 0.0) return null
        return signal.copy(
            index = executionIndex,
            timestamp = candle.timestamp,
            entry = fill,
        )
    }

    /**
     * Handle a bar that opens beyond a stop/target already defined on the signal
     * bar. Returning the executed entry price models an immediately marketable
     * protection order and, importantly, never awards the skipped stale level.
     */
    private fun checkOpeningGapExit(signal: StrategySignal): Pair<Double, ExitReason>? {
        return when (signal.direction) {
            Direction.BULLISH -> when {
                signal.entry <= signal.stopLoss -> signal.entry to ExitReason.SL
                signal.entry >= signal.takeProfit -> signal.entry to ExitReason.TP
                else -> null
            }
            Direction.BEARISH -> when {
                signal.entry >= signal.stopLoss -> signal.entry to ExitReason.SL
                signal.entry <= signal.takeProfit -> signal.entry to ExitReason.TP
                else -> null
            }
        }
    }

    private fun checkTradeExit(signal: StrategySignal, candle: Candle): Pair<Double, ExitReason>? {
        val spread = getSpread(candle)

        if (signal.direction == Direction.BULLISH) {
            // SL checked first (conservative) when one OHLC bar touches both.
            if (candle.low - spread <= signal.stopLoss) {
                return (signal.stopLoss - config.slippage) to ExitReason.SL
            }
            if (candle.high >= signal.takeProfit) {
                return (signal.takeProfit - config.slippage) to ExitReason.TP
            }
        } else {
            if (candle.high + spread >= signal.stopLoss) {
                return (signal.stopLoss + config.slippage) to ExitReason.SL
            }
            if (candle.low <= signal.takeProfit) {
                return (signal.takeProfit + config.slippage) to ExitReason.TP
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
        signalIndex: Int,
        signalTime: Long,
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
            signalIndex = signalIndex,
            signalTime = signalTime,
        )
    }

    /**
     * Position size is always derived from information available at the call
     * site. NEXT_BAR_OPEN calls this on the signal bar before the future open.
     */
    private fun calculateVolume(balance: Double, signal: StrategySignal): Double {
        if (!balance.isFinite() || balance <= 0.0 || config.contractSize <= 0) return 0.0
        if (!config.riskPercent.isFinite() || config.riskPercent <= 0.0) return 0.0
        val riskAmount = balance * (config.riskPercent / 100.0)
        val stopDistance = abs(signal.entry - signal.stopLoss)
        if (!riskAmount.isFinite() || !stopDistance.isFinite() || stopDistance <= MIN_PRICE_DISTANCE) return 0.0
        val volume = riskAmount / (stopDistance * config.contractSize)
        if (!volume.isFinite() || volume <= 0.0) return 0.0
        return max(MIN_VOLUME, (volume * 100).roundToInt() / 100.0)
    }

    /** Reject malformed, stale, or wrong-side strategy output before it reaches execution. */
    private fun StrategySignal.isExecutable(expectedIndex: Int, expectedTimestamp: Long): Boolean {
        if (index != expectedIndex || timestamp != expectedTimestamp) return false
        if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite()) return false
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return false
        if (volume != null && (!volume.isFinite() || volume <= 0.0)) return false
        return when (direction) {
            Direction.BULLISH -> stopLoss < entry && takeProfit > entry
            Direction.BEARISH -> stopLoss > entry && takeProfit < entry
        }
    }

    private fun getSpread(candle: Candle): Double {
        if (!config.variableSpread) return config.spread
        val range = candle.high - candle.low
        val spreadDenominator = config.spread * 100
        if (!range.isFinite() || spreadDenominator <= 0.0 || !spreadDenominator.isFinite()) {
            return config.spread.coerceAtLeast(0.0)
        }
        val multiplier = min(3.0, 1.0 + range / spreadDenominator)
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
        val returnPercent = if (config.initialBalance > 0.0) {
            (netProfit / config.initialBalance) * 100.0
        } else {
            0.0
        }
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
            returns[i] = if (balance != 0.0) t.netPnL / balance else 0.0
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
        var maxWins = 0
        var maxLosses = 0
        var curWins = 0
        var curLosses = 0
        for (t in trades) {
            if (t.netPnL > 0) {
                curWins++
                curLosses = 0
                maxWins = max(maxWins, curWins)
            } else if (t.netPnL < 0) {
                curLosses++
                curWins = 0
                maxLosses = max(maxLosses, curLosses)
            }
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

    fun updateConfig(newConfig: BacktestConfig) {
        config = newConfig
    }

    fun getConfig(): BacktestConfig = config

    private companion object {
        const val MIN_PRICE_DISTANCE = 1e-12
        const val MIN_VOLUME = 0.01
    }
}
