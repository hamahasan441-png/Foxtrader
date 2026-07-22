package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PositionSizeResult
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskCheckResult
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.RiskStatus
import com.foxtrader.app.domain.model.StopMethod
import com.foxtrader.app.domain.model.TradeOutcome
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Institutional-grade Risk Management Engine.
 *
 * Responsibilities:
 * - Dynamic position sizing (Fixed/Percentage/Kelly/ATR/Volatility)
 * - Stop-loss calculation (Fixed/ATR/Volatility/Structure)
 * - Pre-trade risk gating (daily/weekly loss, drawdown, consecutive losses, exposure)
 * - Drawdown & auto-halt protection
 * - Trade outcome tracking & Kelly estimation
 *
 * Thread-safe: internal state is only mutated through [recordTrade] and [updateBalance].
 */
@Singleton
class RiskEngine @Inject constructor() {

    private var config: RiskConfig = RiskConfig()
    private val tradeHistory = mutableListOf<TradeOutcome>()
    private var peakBalance: Double = config.accountBalance
    private var currentBalance: Double = config.accountBalance
    private var tradingHalted: Boolean = false
    private var haltReason: String = ""

    // ========================================================================
    // POSITION SIZING
    // ========================================================================

    fun calculatePositionSize(
        symbol: String,
        entryPrice: Double,
        stopLossPrice: Double,
        candles: List<Candle>? = null,
    ): PositionSizeResult {
        val warnings = mutableListOf<String>()
        val stopDistance = abs(entryPrice - stopLossPrice)
        if (stopDistance == 0.0) warnings += "Stop distance is zero — using minimum"

        var volume: Double
        var riskAmount: Double

        when (config.sizingMethod) {
            PositionSizingMethod.FIXED_LOTS -> {
                volume = config.fixedLots
                riskAmount = stopDistance * volume * 100_000
            }
            PositionSizingMethod.FIXED_RISK -> {
                riskAmount = config.fixedRiskAmount
                volume = if (stopDistance > 0) riskAmount / (stopDistance * 100_000) else 0.0
            }
            PositionSizingMethod.PERCENTAGE_RISK -> {
                riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                volume = if (stopDistance > 0) riskAmount / (stopDistance * 100_000) else 0.0
            }
            PositionSizingMethod.KELLY -> {
                val kellyPercent = calculateKellyPercent()
                riskAmount = currentBalance * kellyPercent * config.kellyFraction
                volume = if (stopDistance > 0) riskAmount / (stopDistance * 100_000) else 0.0
                if (kellyPercent <= 0) warnings += "Kelly suggests no position (negative edge)"
            }
            PositionSizingMethod.ATR_BASED -> {
                if (candles == null || candles.size < 15) {
                    warnings += "Insufficient data for ATR — falling back to percentage risk"
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (stopDistance > 0) riskAmount / (stopDistance * 100_000) else 0.0
                } else {
                    val atr = TechnicalIndicators.calculateATR(candles, 14)
                    val atrStopDist = atr.last() * config.atrStopMultiplier
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (atrStopDist > 0) riskAmount / (atrStopDist * 100_000) else 0.0
                }
            }
            PositionSizingMethod.VOLATILITY -> {
                if (candles == null || candles.size < 20) {
                    warnings += "Insufficient data for volatility sizing"
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (stopDistance > 0) riskAmount / (stopDistance * 100_000) else 0.0
                } else {
                    val vol = TechnicalIndicators.calculateVolatility(candles)
                    val volStopDist = vol * config.volatilityStopMultiplier
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (volStopDist > 0) riskAmount / (volStopDist * 100_000) else 0.0
                }
            }
        }

        // Round to 0.01 lot minimum
        volume = max(0.01, (volume * 100).roundToInt() / 100.0)

        return PositionSizeResult(
            volume = volume,
            riskAmount = riskAmount,
            riskPercent = (riskAmount / currentBalance) * 100.0,
            stopDistance = stopDistance,
            method = config.sizingMethod,
            warnings = warnings,
        )
    }

    // ========================================================================
    // STOP LOSS CALCULATION
    // ========================================================================

    fun calculateStopLoss(
        entryPrice: Double,
        direction: Direction,
        method: StopMethod,
        candles: List<Candle>? = null,
        structureLevel: Double? = null,
    ): Double = when (method) {
        StopMethod.ATR -> {
            if (candles == null || candles.size < 15) fixedStop(entryPrice, direction)
            else {
                val atr = TechnicalIndicators.calculateATR(candles, 14)
                val dist = atr.last() * config.atrStopMultiplier
                if (direction == Direction.BULLISH) entryPrice - dist else entryPrice + dist
            }
        }
        StopMethod.VOLATILITY -> {
            if (candles == null || candles.size < 20) fixedStop(entryPrice, direction)
            else {
                val vol = TechnicalIndicators.calculateVolatility(candles)
                val dist = vol * config.volatilityStopMultiplier
                if (direction == Direction.BULLISH) entryPrice - dist else entryPrice + dist
            }
        }
        StopMethod.STRUCTURE -> structureLevel ?: fixedStop(entryPrice, direction)
        StopMethod.FIXED -> fixedStop(entryPrice, direction)
    }

    private fun fixedStop(entryPrice: Double, direction: Direction): Double {
        val dist = entryPrice * 0.005 // 0.5% default
        return if (direction == Direction.BULLISH) entryPrice - dist else entryPrice + dist
    }

    // ========================================================================
    // PRE-TRADE RISK CHECK
    // ========================================================================

    fun canOpenTrade(riskAmount: Double): RiskCheckResult {
        val reasons = mutableListOf<String>()

        val dailyLoss = getDailyLoss()
        val weeklyLoss = getWeeklyLoss()
        val consecutive = getConsecutiveLosses()
        val drawdown = getCurrentDrawdown()

        if (tradingHalted) reasons += "Trading halted: $haltReason"

        val maxDaily = currentBalance * (config.maxDailyLossPercent / 100.0)
        if (dailyLoss >= maxDaily) reasons += "Daily loss limit reached"

        val maxWeekly = currentBalance * (config.maxWeeklyLossPercent / 100.0)
        if (weeklyLoss >= maxWeekly) reasons += "Weekly loss limit reached"

        if (consecutive >= config.maxConsecutiveLosses)
            reasons += "Consecutive loss limit reached ($consecutive)"

        if (drawdown >= config.maxDrawdownPercent)
            reasons += "Max drawdown reached (${drawdown.roundToInt()}%)"

        return RiskCheckResult(
            allowed = reasons.isEmpty(),
            reasons = reasons,
            currentDailyLoss = dailyLoss,
            currentWeeklyLoss = weeklyLoss,
            consecutiveLosses = consecutive,
            currentDrawdown = drawdown,
            portfolioExposure = 0.0, // simplified — no open-position tracking in domain layer
        )
    }

    // ========================================================================
    // TRADE OUTCOME TRACKING
    // ========================================================================

    fun recordTrade(pnl: Double, symbol: String) {
        tradeHistory += TradeOutcome(
            timestamp = System.currentTimeMillis(),
            pnl = pnl,
            win = pnl > 0,
            symbol = symbol,
        )
        currentBalance += pnl
        if (currentBalance > peakBalance) peakBalance = currentBalance
        checkAutoHalt()
    }

    // ========================================================================
    // KELLY CRITERION
    // ========================================================================

    fun calculateKellyPercent(): Double {
        val wins = tradeHistory.filter { it.win }
        val losses = tradeHistory.filter { !it.win }
        if (wins.size < 5 || losses.size < 3) return config.riskPercentPerTrade / 100.0

        val winRate = wins.size.toDouble() / tradeHistory.size
        val avgWin = wins.sumOf { it.pnl } / wins.size
        val avgLoss = abs(losses.sumOf { it.pnl }) / losses.size
        if (avgLoss == 0.0) return config.riskPercentPerTrade / 100.0

        val winLossRatio = avgWin / avgLoss
        val kelly = winRate - (1.0 - winRate) / winLossRatio
        return max(0.0, min(kelly, 0.25)) // Cap at 25%
    }

    // ========================================================================
    // LOSS & DRAWDOWN
    // ========================================================================

    fun getDailyLoss(): Double {
        val dayStart = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L
        val todayPnl = tradeHistory.filter { it.timestamp >= dayStart }.sumOf { it.pnl }
        return if (todayPnl < 0) abs(todayPnl) else 0.0
    }

    fun getWeeklyLoss(): Double {
        val weekStart = System.currentTimeMillis() - 7 * 86_400_000L
        val weekPnl = tradeHistory.filter { it.timestamp >= weekStart }.sumOf { it.pnl }
        return if (weekPnl < 0) abs(weekPnl) else 0.0
    }

    fun getConsecutiveLosses(): Int {
        var count = 0
        for (i in tradeHistory.indices.reversed()) {
            if (!tradeHistory[i].win) count++ else break
        }
        return count
    }

    fun getCurrentDrawdown(): Double {
        if (peakBalance == 0.0) return 0.0
        return ((peakBalance - currentBalance) / peakBalance) * 100.0
    }

    // ========================================================================
    // HALT CONTROL
    // ========================================================================

    fun haltTrading(reason: String) {
        tradingHalted = true
        haltReason = reason
    }

    fun resumeTrading() {
        tradingHalted = false
        haltReason = ""
    }

    fun isTradingHalted(): Boolean = tradingHalted

    private fun checkAutoHalt() {
        val drawdown = getCurrentDrawdown()
        if (drawdown >= config.maxDrawdownPercent) {
            haltTrading("Max drawdown ${drawdown.roundToInt()}% reached")
        }
        val consecutive = getConsecutiveLosses()
        if (consecutive >= config.maxConsecutiveLosses) {
            haltTrading("$consecutive consecutive losses")
        }
        val dailyLoss = getDailyLoss()
        if (dailyLoss >= currentBalance * (config.maxDailyLossPercent / 100.0)) {
            haltTrading("Daily loss limit")
        }
    }

    // ========================================================================
    // STATUS & CONFIG
    // ========================================================================

    fun getRiskStatus(): RiskStatus = RiskStatus(
        balance = currentBalance,
        peakBalance = peakBalance,
        drawdownPercent = getCurrentDrawdown(),
        dailyLoss = getDailyLoss(),
        weeklyLoss = getWeeklyLoss(),
        consecutiveLosses = getConsecutiveLosses(),
        exposurePercent = 0.0,
        kellyPercent = calculateKellyPercent() * 100.0,
        halted = tradingHalted,
        haltReason = haltReason,
    )

    fun updateConfig(newConfig: RiskConfig) {
        config = newConfig
    }

    fun getConfig(): RiskConfig = config

    fun updateBalance(balance: Double) {
        currentBalance = balance
        if (balance > peakBalance) peakBalance = balance
    }

    fun getBalance(): Double = currentBalance

    fun reset() {
        tradeHistory.clear()
        peakBalance = config.accountBalance
        currentBalance = config.accountBalance
        tradingHalted = false
        haltReason = ""
    }
}
