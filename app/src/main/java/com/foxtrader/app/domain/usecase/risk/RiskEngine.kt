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
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
 * Thread-safe: internal mutable state is protected by [synchronized] on [lock];
 * [tradeHistory] uses [CopyOnWriteArrayList] for safe concurrent iteration.
 */
@Singleton
class RiskEngine @Inject constructor(
    /**
     * Resolves a symbol to its instrument contract size so sizing is correct
     * across asset classes. Defaulted so unit tests can construct the engine
     * without a DI graph; Hilt injects the singleton in production.
     */
    private val instrumentTypeResolver: InstrumentTypeResolver,
) {

    private val lock = Any()

    // Written from the settings/UI thread via [updateConfig] and read from
    // background analysis threads during sizing and gating. Without @Volatile a
    // worker thread can keep serving a stale risk configuration indefinitely —
    // e.g. still sizing against the old risk-per-trade after the user lowered
    // it. RiskConfig is an immutable data class, so a volatile reference swap
    // publishes the whole configuration atomically.
    @Volatile private var config: RiskConfig = RiskConfig()
    private val tradeHistory = CopyOnWriteArrayList<TradeOutcome>()
    @Volatile private var peakBalance: Double = config.accountBalance
    @Volatile private var currentBalance: Double = config.accountBalance
    private val _tradingHalted = AtomicBoolean(false)
    @Volatile private var haltReason: String = ""

    private val tradingHalted get() = _tradingHalted.get()

    // ========================================================================
    // POSITION SIZING
    // ========================================================================

    fun calculatePositionSize(
        symbol: String,
        entryPrice: Double,
        stopLossPrice: Double,
        candles: List<Candle>? = null,
    ): PositionSizeResult {
        require(entryPrice > 0.0) { "Entry price must be positive" }
        require(stopLossPrice >= 0.0) { "Stop loss price must not be negative" }

        val warnings = mutableListOf<String>()
        val stopDistance = abs(entryPrice - stopLossPrice)
        if (stopDistance == 0.0) warnings += "Stop distance is zero — using minimum"

        // Contract size is the units-per-1.0-volume for this instrument. It is
        // the conversion between a price move and money, and it varies by asset
        // class: an FX standard lot is 100k units, a crypto coin is 1, gold is
        // 100 oz. Hardcoding the FX lot here (as this engine previously did)
        // mis-sizes every non-forex trade by orders of magnitude, corrupting the
        // very risk gates that are meant to keep the account solvent.
        val contractSize = instrumentTypeResolver.resolve(symbol).contractSize

        var volume: Double
        var riskAmount: Double

        when (config.sizingMethod) {
            PositionSizingMethod.FIXED_LOTS -> {
                volume = config.fixedLots
                riskAmount = stopDistance * volume * contractSize
            }
            PositionSizingMethod.FIXED_RISK -> {
                riskAmount = config.fixedRiskAmount
                volume = if (stopDistance > 0) riskAmount / (stopDistance * contractSize) else 0.0
            }
            PositionSizingMethod.PERCENTAGE_RISK -> {
                riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                volume = if (stopDistance > 0) riskAmount / (stopDistance * contractSize) else 0.0
            }
            PositionSizingMethod.KELLY -> {
                val kellyPercent = calculateKellyPercent()
                riskAmount = currentBalance * kellyPercent * config.kellyFraction
                volume = if (stopDistance > 0) riskAmount / (stopDistance * contractSize) else 0.0
                if (kellyPercent <= 0) warnings += "Kelly suggests no position (negative edge)"
            }
            PositionSizingMethod.ATR_BASED -> {
                if (candles == null || candles.size < 15) {
                    warnings += "Insufficient data for ATR — falling back to percentage risk"
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (stopDistance > 0) riskAmount / (stopDistance * contractSize) else 0.0
                } else {
                    val atr = TechnicalIndicators.calculateATR(candles, 14)
                    val atrStopDist = atr.last() * config.atrStopMultiplier
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (atrStopDist > 0) riskAmount / (atrStopDist * contractSize) else 0.0
                }
            }
            PositionSizingMethod.VOLATILITY -> {
                if (candles == null || candles.size < 20) {
                    warnings += "Insufficient data for volatility sizing"
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (stopDistance > 0) riskAmount / (stopDistance * contractSize) else 0.0
                } else {
                    val vol = TechnicalIndicators.calculateVolatility(candles)
                    val volStopDist = vol * config.volatilityStopMultiplier
                    riskAmount = currentBalance * (config.riskPercentPerTrade / 100.0)
                    volume = if (volStopDist > 0) riskAmount / (volStopDist * contractSize) else 0.0
                }
            }
        }

        // A non-finite intermediate (an Infinity from a near-zero stop distance,
        // or a NaN from a degenerate ATR/volatility read) must never reach the
        // rounding step: (Infinity * 100).roundToInt() silently becomes
        // Int.MAX_VALUE, i.e. a ~21 million lot order that looks like a real
        // number all the way to the broker.
        if (!volume.isFinite()) {
            warnings += "Computed volume was not finite — falling back to minimum size"
            volume = 0.0
        }
        if (!riskAmount.isFinite()) {
            warnings += "Computed risk amount was not finite — reported as zero"
            riskAmount = 0.0
        }

        // `OVERFLOW` The isFinite() guard above catches Infinity and NaN, but a
        // *finite* enormous volume slips straight through it and then saturates
        // in exactly the way that comment warns about: a stop distance that is
        // tiny but non-zero (a one-pipette stop, a near-zero ATR read) sizes to
        // ~1e9 lots, and `(1e9 * 100).roundToInt()` returns Int.MAX_VALUE
        // rather than throwing — the same ~21 million lot order, reached by a
        // path the non-finite check does not cover. Round in Double space and
        // cap explicitly.
        val roundedVolume = kotlin.math.round(volume * 100.0) / 100.0
        volume = when {
            !roundedVolume.isFinite() -> {
                warnings += "Computed volume was not finite — falling back to minimum size"
                MIN_TRADE_VOLUME
            }
            roundedVolume > MAX_TRADE_VOLUME -> {
                // Never silently downsize to the cap: a size this far out means
                // the stop distance is unusable, and reporting the cap would
                // look like a deliberate, sanctioned position.
                warnings += "Stop distance is too small to size a position safely — " +
                    "widen the stop before trading"
                MIN_TRADE_VOLUME
            }
            else -> max(MIN_TRADE_VOLUME, roundedVolume)
        }

        // riskPercent is only meaningful against a positive balance. With a
        // zero balance the division yields Infinity/NaN and with a negative
        // balance (a blown account still being reconciled) it yields a negative
        // percentage — which trips `require(riskPercent >= 0.0)` inside
        // PositionSizeResult and crashes the caller instead of sizing a trade.
        val riskPercent = if (currentBalance > 0.0) {
            ((riskAmount / currentBalance) * 100.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        } else {
            warnings += "Account balance is not positive — risk percent is undefined"
            0.0
        }

        return PositionSizeResult(
            volume = volume,
            riskAmount = riskAmount,
            riskPercent = riskPercent,
            stopDistance = stopDistance,
            method = config.sizingMethod,
            warnings = warnings,
            contractSize = contractSize,
        )
    }

    /**
     * Sizes a live (broker-bound) position from a broker [InstrumentSpec] and an
     * explicit quote-currency -> account-currency conversion.
     *
     * Returns `null` — rather than silently assuming a 1.0 rate — whenever the
     * quote-currency to account-currency conversion is missing, non-positive, or
     * non-finite. Assuming 1.0 would size a non-USD-quoted instrument in USD and
     * be wrong by the FX rate, defeating the risk gates that are meant to keep
     * the account solvent. Callers must treat `null` as "cannot size, do not
     * submit".
     *
     * @param spec broker-authoritative instrument metadata.
     * @param entryPrice intended entry price.
     * @param stopLossPrice stop-loss price.
     * @param riskAmountInAccountCurrency monetary risk budget, in account currency.
     * @param quoteToAccountRate account-currency units per 1 quote-currency unit.
     * @return the volume to submit, or `null` when sizing cannot be performed
     *   safely.
     */
    fun calculateLivePositionSize(
        spec: InstrumentSpec,
        entryPrice: Double,
        stopLossPrice: Double,
        riskAmountInAccountCurrency: Double,
        quoteToAccountRate: Double?,
    ): Double? {
        // Fail closed on a missing/broken conversion instead of assuming 1.0.
        if (quoteToAccountRate == null || !quoteToAccountRate.isFinite() || quoteToAccountRate <= 0.0) {
            return null
        }
        require(entryPrice > 0.0) { "Entry price must be positive" }
        require(stopLossPrice >= 0.0) { "Stop loss price must not be negative" }
        require(riskAmountInAccountCurrency > 0.0) { "Risk amount must be positive" }

        val stopDistance = abs(entryPrice - stopLossPrice)
        if (stopDistance <= 0.0) return null

        // Monetary risk per 1.0 volume, expressed in account currency.
        val riskPerUnitVolume = stopDistance * spec.contractSize * quoteToAccountRate
        if (!riskPerUnitVolume.isFinite() || riskPerUnitVolume <= 0.0) return null

        val rawVolume = riskAmountInAccountCurrency / riskPerUnitVolume
        if (!rawVolume.isFinite()) return null

        return spec.sanitizeVolume(rawVolume)
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

    fun canOpenTrade(
        riskAmount: Double,
        currentPortfolioExposurePercent: Double = 0.0,
        proposedExposurePercent: Double = 0.0,
        currentCorrelatedExposurePercent: Double = 0.0,
        proposedCorrelatedExposurePercent: Double = 0.0,
    ): RiskCheckResult {
        val reasons = mutableListOf<String>()

        val dailyLoss = getDailyLoss()
        val weeklyLoss = getWeeklyLoss()
        val consecutive = getConsecutiveLosses()
        val drawdown = getCurrentDrawdown()
        val portfolioExposure = (currentPortfolioExposurePercent + proposedExposurePercent).coerceAtLeast(0.0)
        val correlatedExposure = (currentCorrelatedExposurePercent + proposedCorrelatedExposurePercent).coerceAtLeast(0.0)

        if (tradingHalted) reasons += "Trading halted: $haltReason"

        val maxRiskPerTrade = currentBalance * (config.riskPercentPerTrade / 100.0)
        if (riskAmount <= 0.0) reasons += "Risk amount must be positive"
        if (riskAmount > maxRiskPerTrade) {
            reasons += "Proposed risk ${riskAmount.roundToInt()} exceeds per-trade limit ${maxRiskPerTrade.roundToInt()}"
        }

        if (portfolioExposure > config.maxPortfolioExposurePercent) {
            reasons += "Portfolio exposure ${portfolioExposure.roundToInt()}% exceeds limit ${config.maxPortfolioExposurePercent.roundToInt()}%"
        }

        if (correlatedExposure > config.maxCorrelatedExposurePercent) {
            reasons += "Correlated exposure ${correlatedExposure.roundToInt()}% exceeds limit ${config.maxCorrelatedExposurePercent.roundToInt()}%"
        }

        val maxDaily = config.accountBalance * (config.maxDailyLossPercent / 100.0)
        if (dailyLoss >= maxDaily) reasons += "Daily loss limit reached"

        val maxWeekly = config.accountBalance * (config.maxWeeklyLossPercent / 100.0)
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
            portfolioExposure = portfolioExposure,
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
        synchronized(lock) {
            currentBalance += pnl
            if (currentBalance > peakBalance) peakBalance = currentBalance
        }
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
        return tradeHistory
            .filter { it.timestamp >= dayStart && it.pnl < 0.0 }
            .sumOf { abs(it.pnl) }
    }

    fun getWeeklyLoss(): Double {
        val weekStart = System.currentTimeMillis() - 7 * 86_400_000L
        return tradeHistory
            .filter { it.timestamp >= weekStart && it.pnl < 0.0 }
            .sumOf { abs(it.pnl) }
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
        haltReason = reason
        _tradingHalted.set(true)
    }

    fun resumeTrading() {
        _tradingHalted.set(false)
        haltReason = ""
    }

    fun isTradingHalted(): Boolean = _tradingHalted.get()

    private fun checkAutoHalt() {
        val drawdown = getCurrentDrawdown()
        if (drawdown >= config.maxDrawdownPercent) {
            haltTrading("Max drawdown ${drawdown.roundToInt()}% reached")
            return
        }
        val consecutive = getConsecutiveLosses()
        if (consecutive >= config.maxConsecutiveLosses) {
            haltTrading("$consecutive consecutive losses")
            return
        }
        val dailyLoss = getDailyLoss()
        val maxDailyLoss = config.accountBalance * (config.maxDailyLossPercent / 100.0)
        if (dailyLoss >= maxDailyLoss) {
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
        halted = _tradingHalted.get(),
        haltReason = haltReason,
    )

    /**
     * Replaces the risk configuration without resetting runtime state (balance,
     * peak, trade history). Call [updateBalance] separately to re-seed the live
     * balance when the account changes.
     */
    fun updateConfig(newConfig: RiskConfig) {
        config = newConfig
    }

    fun getConfig(): RiskConfig = config

    /**
     * Resets both [currentBalance] and [peakBalance] to [balance]. Use after an
     * account change or when re-syncing with a live broker balance. Does not
     * modify [config].
     */
    fun updateBalance(balance: Double) {
        synchronized(lock) {
            currentBalance = balance
            peakBalance = balance
        }
    }

    fun getBalance(): Double = currentBalance

    fun reset() {
        tradeHistory.clear()
        synchronized(lock) {
            peakBalance = config.accountBalance
            currentBalance = config.accountBalance
        }
        _tradingHalted.set(false)
        haltReason = ""
    }

    companion object {
        /** Smallest tradable size (one micro lot). */
        const val MIN_TRADE_VOLUME = 0.01

        /**
         * Ceiling on a sized position, in lots.
         *
         * Far above any real single fill, so it never rejects a legitimate
         * size. It exists to catch a degenerate stop distance producing an
         * absurd notional — the failure mode a plain `roundToInt()` turns into
         * a plausible-looking number instead of an error.
         */
        const val MAX_TRADE_VOLUME = 10_000.0
    }
}
