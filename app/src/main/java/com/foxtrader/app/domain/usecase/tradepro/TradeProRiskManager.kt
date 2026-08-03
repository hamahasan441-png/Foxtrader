package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AlertSeverity
import com.foxtrader.app.domain.model.tradepro.CorrelationGroup
import com.foxtrader.app.domain.model.tradepro.DailyPerformance
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.PortfolioRiskState
import com.foxtrader.app.domain.model.tradepro.PositionHeat
import com.foxtrader.app.domain.model.tradepro.PositionSizeResult
import com.foxtrader.app.domain.model.tradepro.RiskAlert
import com.foxtrader.app.domain.model.tradepro.RiskAlertType
import com.foxtrader.app.domain.model.tradepro.RiskDecision
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.repository.JournalRepository
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portfolio-level risk manager for the TRADEPRO framework.
 *
 * Responsibilities:
 * - Kelly-criterion position sizing with half-Kelly safety cap.
 * - Multi-factor risk alert generation (daily limits, streak, drawdown, correlation, tilt).
 * - Correlation exposure calculation for major pairs/indices/crypto.
 * - Aggregated portfolio state computation.
 * - Final trade-gate decision: PROCEED / REDUCE_SIZE / STAND_ASIDE.
 *
 * Design: all public methods accept their dependencies as parameters so the class is trivially
 * testable without mocks. [JournalRepository] is injected for use cases that need historical data
 * but is not required by the core sizing/alert logic tested in unit tests.
 */
class TradeProRiskManager @Inject constructor(
    private val journalRepository: JournalRepository,
) {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        /** Half-Kelly safety factor (industry standard conservative approach). */
        private const val HALF_KELLY_FACTOR = 0.5

        /** Minimum Kelly fraction to avoid zero-sizing on edge cases. */
        private const val MIN_KELLY_FRACTION = 0.01

        /** Maximum fraction of daily risk budget a single trade can consume. */
        private const val MAX_SINGLE_TRADE_BUDGET_FRACTION = 0.40

        /** Correlation threshold above which instruments are considered correlated. */
        private const val CORRELATION_THRESHOLD = 0.70

        /** Fraction of risk budget in correlated assets to trigger an alert. */
        private const val CORRELATION_ALERT_THRESHOLD = 0.60

        /** Drawdown (in points) as fraction of peak that triggers an alert. */
        private const val DRAWDOWN_ALERT_PERCENT = 0.15

        /** Number of consecutive losses that triggers a streak warning. */
        private const val STREAK_WARNING_THRESHOLD = 2

        /** Number of consecutive losses that triggers a streak critical alert. */
        private const val STREAK_CRITICAL_THRESHOLD = 3

        /** Trades per hour that suggest emotional tilt. */
        private const val TILT_TRADES_PER_HOUR_THRESHOLD = 4

        /** Maximum number of trades per day before tilt warning fires. */
        private const val TILT_DAILY_TRADE_THRESHOLD = 8

        /** Daily loss percentage (of budget) triggering warning vs critical. */
        private const val DAILY_LOSS_WARNING_PERCENT = 0.60
        private const val DAILY_LOSS_CRITICAL_PERCENT = 0.90

        // --- Hardcoded correlation matrix ---
        // Groups of instruments known to be highly correlated (>0.70).
        // Each group is a Pair(symbols, average correlation coefficient).
        private val CORRELATION_MATRIX: List<Pair<List<String>, Double>> = listOf(
            // Forex: EUR-correlated
            listOf("EURUSD", "GBPUSD", "AUDUSD", "NZDUSD") to 0.82,
            // Forex: USD-correlated (inverse to EUR group)
            listOf("USDJPY", "USDCAD", "USDCHF") to 0.76,
            // Equities: US indices
            listOf("NAS100", "US500", "US30", "SPX500", "NDX") to 0.91,
            // Crypto: major coins
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BTCUSD", "ETHUSD") to 0.85,
            // Commodities: metals
            listOf("XAUUSD", "XAGUSD") to 0.78,
            // Energy
            listOf("USOIL", "UKOIL", "CL", "BRENT") to 0.88,
        )
    }

    // -----------------------------------------------------------------------
    // Position Sizing (Kelly Criterion)
    // -----------------------------------------------------------------------

    /**
     * Assess optimal position size using half-Kelly criterion, capped by the config's max risk
     * budget and the remaining daily drawdown budget.
     *
     * @param symbol Instrument to trade.
     * @param direction Trade direction.
     * @param config Current TRADEPRO config (provides stopPoints, maxRiskPoints).
     * @param currentState Live portfolio risk snapshot.
     * @return Recommended position size and sizing metadata.
     */
    fun assessPosition(
        symbol: String,
        direction: Direction,
        config: TradeProConfig,
        currentState: PortfolioRiskState,
    ): PositionSizeResult {
        // Historical win rate from the portfolio state or default conservative estimate.
        val winRate = estimateWinRate(currentState)
        val lossRate = 1.0 - winRate

        // Average win/loss ratio from config targets: expected win = T1 points, loss = stop.
        val avgWin = config.target1Points
        val avgLoss = config.stopPoints

        // Kelly fraction: f* = (p * b - q) / b, where b = avgWin/avgLoss, p = winRate, q = lossRate
        val payoffRatio = if (avgLoss > 0.0) avgWin / avgLoss else 1.0
        val rawKelly = if (payoffRatio > 0.0) {
            (winRate * payoffRatio - lossRate) / payoffRatio
        } else {
            MIN_KELLY_FRACTION
        }

        // Apply half-Kelly safety and floor at minimum.
        val halfKelly = (rawKelly * HALF_KELLY_FACTOR).coerceIn(MIN_KELLY_FRACTION, 1.0)

        // Risk per contract in points.
        val riskPerContract = config.stopPoints * config.contracts.coerceAtLeast(1)

        // Maximum risk budget for a single trade.
        val maxSingleTradeRisk = config.maxRiskPoints * MAX_SINGLE_TRADE_BUDGET_FRACTION
        val remainingBudget = (currentState.maxRiskBudget - currentState.openRisk)
            .coerceAtLeast(0.0)
        val maxDrawdownBudget = (config.maxDailyLossPoints + currentState.dailyPnl)
            .coerceAtLeast(0.0)

        // Kelly-weighted risk allocation.
        val kellyRisk = halfKelly * config.maxRiskPoints

        // Cap at the most restrictive limit.
        val cappedRisk = minOf(kellyRisk, maxSingleTradeRisk, remainingBudget, maxDrawdownBudget)
            .coerceAtLeast(0.0)

        // Convert to contracts (at least 1 if any budget remains).
        val riskPerSingleContract = config.stopPoints.coerceAtLeast(0.01)
        val contracts = if (cappedRisk <= 0.0) {
            0
        } else {
            (cappedRisk / riskPerSingleContract).roundToInt().coerceIn(1, config.contracts * 2)
        }

        val totalRisk = contracts * riskPerSingleContract

        return PositionSizeResult(
            recommendedContracts = contracts,
            riskPerContract = riskPerSingleContract,
            totalRiskPoints = totalRisk,
            kellyFraction = halfKelly,
            maxDrawdownBudget = maxDrawdownBudget,
        )
    }

    // -----------------------------------------------------------------------
    // Daily Performance Tracking
    // -----------------------------------------------------------------------

    /**
     * Update the rolling daily performance record after a trade closes.
     *
     * @param trade The trade that just closed.
     * @param previous The performance record before this trade.
     * @return Updated performance record.
     */
    fun updateDailyPerformance(
        trade: ManagedTrade,
        previous: DailyPerformance,
    ): DailyPerformance {
        val isWin = trade.realizedPoints > 0.0
        val newWins = previous.wins + if (isWin) 1 else 0
        val newLosses = previous.losses + if (!isWin) 1 else 0
        val newNet = previous.netPoints + trade.realizedPoints
        val newEquity = previous.cumulativeEquity + trade.realizedPoints
        val newPeak = max(previous.peakEquity, newEquity)
        val newDrawdown = (newPeak - newEquity).coerceAtLeast(0.0)

        // Compliance: win-rate weighted by plan adherence (simplified as actual win-rate * 100).
        val totalTrades = newWins + newLosses
        val compliance = if (totalTrades > 0) {
            (newWins.toDouble() / totalTrades * 100.0).coerceIn(0.0, 100.0)
        } else {
            previous.complianceScore
        }

        return DailyPerformance(
            date = previous.date,
            tradesTaken = previous.tradesTaken + 1,
            wins = newWins,
            losses = newLosses,
            netPoints = newNet,
            cumulativeEquity = newEquity,
            peakEquity = newPeak,
            drawdown = newDrawdown,
            complianceScore = compliance,
        )
    }

    // -----------------------------------------------------------------------
    // Risk Alert Generation
    // -----------------------------------------------------------------------

    /**
     * Evaluate the current portfolio state and generate all applicable risk alerts.
     * Multi-factor: daily limits, streak, drawdown, correlation, tilt.
     *
     * @param state Current portfolio risk snapshot.
     * @return List of active alerts (may be empty if all clear).
     */
    fun checkRiskAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()

        // 1. Daily loss limit check
        alerts += checkDailyLimitAlerts(state)

        // 2. Consecutive loss streak
        alerts += checkStreakAlerts(state)

        // 3. Drawdown from peak
        alerts += checkDrawdownAlerts(state)

        // 4. Correlation concentration
        alerts += checkCorrelationAlerts(state)

        // 5. Emotional tilt (trade frequency)
        alerts += checkTiltAlerts(state)

        return alerts
    }

    private fun checkDailyLimitAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()
        val budget = state.maxRiskBudget
        if (budget <= 0.0) return alerts

        val lossRatio = abs(state.dailyPnl) / budget
        // Only fire when daily PnL is negative
        if (state.dailyPnl >= 0.0) return alerts

        if (lossRatio >= DAILY_LOSS_CRITICAL_PERCENT) {
            alerts += RiskAlert(
                type = RiskAlertType.DAILY_LIMIT,
                severity = AlertSeverity.CRITICAL,
                message = "Daily loss at ${"%.0f".format(lossRatio * 100)}% of budget. Stop trading.",
                value = abs(state.dailyPnl),
                threshold = budget * DAILY_LOSS_CRITICAL_PERCENT,
            )
        } else if (lossRatio >= DAILY_LOSS_WARNING_PERCENT) {
            alerts += RiskAlert(
                type = RiskAlertType.DAILY_LIMIT,
                severity = AlertSeverity.WARNING,
                message = "Daily loss at ${"%.0f".format(lossRatio * 100)}% of budget. Reduce size.",
                value = abs(state.dailyPnl),
                threshold = budget * DAILY_LOSS_WARNING_PERCENT,
            )
        }
        return alerts
    }

    private fun checkStreakAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()
        if (state.consecutiveLosses >= STREAK_CRITICAL_THRESHOLD) {
            alerts += RiskAlert(
                type = RiskAlertType.STREAK,
                severity = AlertSeverity.CRITICAL,
                message = "${state.consecutiveLosses} consecutive losses. Stand aside.",
                value = state.consecutiveLosses.toDouble(),
                threshold = STREAK_CRITICAL_THRESHOLD.toDouble(),
            )
        } else if (state.consecutiveLosses >= STREAK_WARNING_THRESHOLD) {
            alerts += RiskAlert(
                type = RiskAlertType.STREAK,
                severity = AlertSeverity.WARNING,
                message = "${state.consecutiveLosses} consecutive losses. Review process.",
                value = state.consecutiveLosses.toDouble(),
                threshold = STREAK_WARNING_THRESHOLD.toDouble(),
            )
        }
        return alerts
    }

    private fun checkDrawdownAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()
        if (state.currentEquity <= 0.0) return alerts

        val drawdownPercent = if (state.currentEquity > 0.0) {
            (state.maxRiskBudget - state.currentEquity).coerceAtLeast(0.0) / state.currentEquity
        } else {
            0.0
        }

        if (drawdownPercent >= DRAWDOWN_ALERT_PERCENT) {
            alerts += RiskAlert(
                type = RiskAlertType.DRAWDOWN,
                severity = AlertSeverity.WARNING,
                message = "Drawdown at ${"%.1f".format(drawdownPercent * 100)}% of equity.",
                value = drawdownPercent,
                threshold = DRAWDOWN_ALERT_PERCENT,
            )
        }
        return alerts
    }

    private fun checkCorrelationAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()
        if (state.correlationExposure > CORRELATION_ALERT_THRESHOLD) {
            alerts += RiskAlert(
                type = RiskAlertType.CORRELATION,
                severity = if (state.correlationExposure > 0.80) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                message = "Correlation exposure at ${"%.0f".format(state.correlationExposure * 100)}%. " +
                    "Diversify or reduce.",
                value = state.correlationExposure,
                threshold = CORRELATION_ALERT_THRESHOLD,
            )
        }
        return alerts
    }

    private fun checkTiltAlerts(state: PortfolioRiskState): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()
        if (state.tradesTakenToday >= TILT_DAILY_TRADE_THRESHOLD) {
            alerts += RiskAlert(
                type = RiskAlertType.TILT,
                severity = AlertSeverity.WARNING,
                message = "${state.tradesTakenToday} trades today suggests emotional tilt. " +
                    "Step back and review.",
                value = state.tradesTakenToday.toDouble(),
                threshold = TILT_DAILY_TRADE_THRESHOLD.toDouble(),
            )
        }
        return alerts
    }

    // -----------------------------------------------------------------------
    // Correlation Exposure
    // -----------------------------------------------------------------------

    /**
     * Calculate the correlation exposure across open positions using a hardcoded correlation
     * matrix for major forex pairs, equity indices, and crypto.
     *
     * @param openPositions Currently open managed trades.
     * @return List of correlation groups that have active multi-instrument exposure.
     */
    fun calculateCorrelationExposure(openPositions: List<ManagedTrade>): List<CorrelationGroup> {
        if (openPositions.isEmpty()) return emptyList()

        val activeSymbols = openPositions
            .filter { it.state != ManagedTradeState.CLOSED }
            .map { it.symbol.uppercase() }
            .toSet()

        val groups = mutableListOf<CorrelationGroup>()

        for ((symbols, coefficient) in CORRELATION_MATRIX) {
            val overlapping = symbols.filter { it in activeSymbols }
            if (overlapping.size >= 2) {
                // Calculate combined exposure for the overlapping instruments.
                val combinedRisk = openPositions
                    .filter { it.symbol.uppercase() in overlapping && it.state != ManagedTradeState.CLOSED }
                    .sumOf { riskPointsForTrade(it) }

                groups += CorrelationGroup(
                    symbols = overlapping,
                    correlationCoefficient = coefficient,
                    combinedExposure = combinedRisk,
                )
            }
        }
        return groups
    }

    // -----------------------------------------------------------------------
    // Portfolio State Aggregation
    // -----------------------------------------------------------------------

    /**
     * Compute the live portfolio risk state from open positions and daily performance.
     *
     * @param openPositions Currently open trades.
     * @param config TRADEPRO configuration.
     * @param dailyPerformance Today's rolling performance record.
     * @return Aggregated portfolio risk state.
     */
    fun getPortfolioState(
        openPositions: List<ManagedTrade>,
        config: TradeProConfig,
        dailyPerformance: DailyPerformance,
    ): PortfolioRiskState {
        val activePositions = openPositions.filter { it.state != ManagedTradeState.CLOSED }

        // Calculate open risk: sum of risk points across active positions.
        val openRisk = activePositions.sumOf { riskPointsForTrade(it) }

        // Calculate position heat.
        val maxBudget = config.maxDailyLossPoints.coerceAtLeast(1.0)
        val heat = activePositions.map { trade ->
            val risk = riskPointsForTrade(trade)
            PositionHeat(
                symbol = trade.symbol,
                direction = trade.direction,
                riskPoints = risk,
                heatPercent = (risk / maxBudget).coerceIn(0.0, 1.0),
            )
        }

        // Correlation exposure: fraction of total open risk in correlated groups.
        val correlationGroups = calculateCorrelationExposure(openPositions)
        val correlatedRisk = correlationGroups.sumOf { it.combinedExposure }
        val correlationExposure = if (openRisk > 0.0) {
            (correlatedRisk / openRisk).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // Utilization percentage.
        val utilization = if (maxBudget > 0.0) {
            (openRisk / maxBudget * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        // Consecutive losses from daily performance.
        val consecutiveLosses = countConsecutiveLosses(dailyPerformance)

        return PortfolioRiskState(
            currentEquity = dailyPerformance.cumulativeEquity,
            dailyPnl = dailyPerformance.netPoints,
            openRisk = openRisk,
            maxRiskBudget = config.maxDailyLossPoints,
            correlationExposure = correlationExposure,
            positionHeat = heat,
            riskUtilizationPercent = utilization,
            consecutiveLosses = consecutiveLosses,
            tradesTakenToday = dailyPerformance.tradesTaken,
        )
    }

    // -----------------------------------------------------------------------
    // Trade Gate
    // -----------------------------------------------------------------------

    /**
     * Final decision gate: should the trader proceed with a new position?
     *
     * Evaluates the full risk picture and returns one of:
     * - [RiskDecision.Proceed]: all clear, trade at full size.
     * - [RiskDecision.ReduceSize]: elevated risk, trade at reduced size.
     * - [RiskDecision.StandAside]: unacceptable risk, do not trade.
     *
     * @param symbol Instrument to trade.
     * @param direction Trade direction.
     * @param state Current portfolio risk snapshot.
     * @param config TRADEPRO configuration.
     * @return The risk decision with a human-readable reason.
     */
    fun shouldTrade(
        symbol: String,
        direction: Direction,
        state: PortfolioRiskState,
        config: TradeProConfig,
    ): RiskDecision {
        // Check absolute daily loss limit first.
        if (state.dailyPnl <= -config.maxDailyLossPoints) {
            return RiskDecision.StandAside(
                reason = "Daily loss limit reached (${"%.1f".format(abs(state.dailyPnl))} pts lost, " +
                    "limit ${config.maxDailyLossPoints} pts). No more trades today."
            )
        }

        // Consecutive loss streak hard stop.
        if (state.consecutiveLosses >= config.maxConsecutiveLosses) {
            return RiskDecision.StandAside(
                reason = "${state.consecutiveLosses} consecutive losses. " +
                    "Maximum ${config.maxConsecutiveLosses} reached. Stand aside."
            )
        }

        // Risk utilization check.
        if (state.riskUtilizationPercent >= 90.0) {
            return RiskDecision.StandAside(
                reason = "Risk utilization at ${"%.0f".format(state.riskUtilizationPercent)}%. " +
                    "Portfolio is fully loaded."
            )
        }

        // Correlation concentration: stand aside if adding to a correlated cluster.
        if (state.correlationExposure > 0.80) {
            val isCorrelated = isSymbolCorrelatedWithExisting(symbol, state)
            if (isCorrelated) {
                return RiskDecision.StandAside(
                    reason = "Adding $symbol would increase already-critical correlation exposure " +
                        "(${"%.0f".format(state.correlationExposure * 100)}%)."
                )
            }
        }

        // Tilt detection.
        if (state.tradesTakenToday >= TILT_DAILY_TRADE_THRESHOLD) {
            return RiskDecision.StandAside(
                reason = "${state.tradesTakenToday} trades today. Possible emotional tilt. Stand aside."
            )
        }

        // Moderate risk conditions: reduce size.
        if (state.riskUtilizationPercent >= 60.0) {
            return RiskDecision.ReduceSize(
                reason = "Risk utilization at ${"%.0f".format(state.riskUtilizationPercent)}%. " +
                    "Reduce position size."
            )
        }

        if (state.correlationExposure > CORRELATION_ALERT_THRESHOLD) {
            return RiskDecision.ReduceSize(
                reason = "Correlation exposure at ${"%.0f".format(state.correlationExposure * 100)}%. " +
                    "Consider reducing size or diversifying."
            )
        }

        if (state.consecutiveLosses >= STREAK_WARNING_THRESHOLD) {
            return RiskDecision.ReduceSize(
                reason = "${state.consecutiveLosses} consecutive losses. " +
                    "Reduce size and confirm edge."
            )
        }

        // All clear.
        return RiskDecision.Proceed(
            reason = "All risk checks passed. Proceed at full size."
        )
    }

    // -----------------------------------------------------------------------
    // Internal Helpers
    // -----------------------------------------------------------------------

    /**
     * Estimate win rate from the current state. Uses a conservative default if no data.
     */
    private fun estimateWinRate(state: PortfolioRiskState): Double {
        // Use a conservative baseline win rate for half-Kelly sizing.
        // In a more sophisticated version this would pull from JournalRepository history.
        return 0.55 // Conservative 55% baseline for TRADEPRO framework
    }

    /**
     * Calculate risk points for a single trade (distance from entry to stop * contracts).
     */
    private fun riskPointsForTrade(trade: ManagedTrade): Double {
        val distance = abs(trade.entryPrice - trade.stopPrice)
        return distance * trade.contracts
    }

    /**
     * Count consecutive losses from daily performance. Simplified: uses losses at tail.
     */
    private fun countConsecutiveLosses(performance: DailyPerformance): Int {
        // If losses exceed wins, approximate consecutive losses at end of session.
        // More precise tracking would require trade-by-trade history.
        val diff = performance.losses - performance.wins
        return if (diff > 0 && performance.tradesTaken > 0) {
            min(diff, performance.losses)
        } else {
            0
        }
    }

    /**
     * Check if a symbol belongs to the same correlation group as existing positions.
     */
    private fun isSymbolCorrelatedWithExisting(
        symbol: String,
        state: PortfolioRiskState,
    ): Boolean {
        val upperSymbol = symbol.uppercase()
        val existingSymbols = state.positionHeat.map { it.symbol.uppercase() }.toSet()
        if (existingSymbols.isEmpty()) return false

        for ((symbols, _) in CORRELATION_MATRIX) {
            if (upperSymbol in symbols && symbols.any { it in existingSymbols }) {
                return true
            }
        }
        return false
    }
}
