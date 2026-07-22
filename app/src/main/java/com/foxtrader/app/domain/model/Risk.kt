package com.foxtrader.app.domain.model

/**
 * Position sizing methods available in the Risk Engine.
 */
enum class PositionSizingMethod {
    FIXED_LOTS,
    FIXED_RISK,
    PERCENTAGE_RISK,
    KELLY,
    ATR_BASED,
    VOLATILITY,
}

/** Stop loss calculation method. */
enum class StopMethod { FIXED, ATR, VOLATILITY, STRUCTURE }

/**
 * Full risk configuration — persisted in user settings.
 */
data class RiskConfig(
    val accountBalance: Double = 100_000.0,
    val accountCurrency: String = "USD",
    val sizingMethod: PositionSizingMethod = PositionSizingMethod.PERCENTAGE_RISK,
    val riskPercentPerTrade: Double = 1.0,
    val fixedRiskAmount: Double = 1_000.0,
    val fixedLots: Double = 0.1,
    val kellyFraction: Double = 0.5,
    val atrStopMultiplier: Double = 1.5,
    val volatilityStopMultiplier: Double = 2.0,
    val maxDailyLossPercent: Double = 3.0,
    val maxWeeklyLossPercent: Double = 6.0,
    val maxConsecutiveLosses: Int = 4,
    val maxDrawdownPercent: Double = 15.0,
    val maxPortfolioExposurePercent: Double = 500.0,
    val maxCorrelatedExposurePercent: Double = 200.0,
    val correlationThreshold: Double = 0.7,
)

/**
 * Result of a position-size calculation.
 */
data class PositionSizeResult(
    val volume: Double,
    val riskAmount: Double,
    val riskPercent: Double,
    val stopDistance: Double,
    val method: PositionSizingMethod,
    val warnings: List<String>,
)

/**
 * Result of a pre-trade risk check.
 */
data class RiskCheckResult(
    val allowed: Boolean,
    val reasons: List<String>,
    val currentDailyLoss: Double,
    val currentWeeklyLoss: Double,
    val consecutiveLosses: Int,
    val currentDrawdown: Double,
    val portfolioExposure: Double,
)

/**
 * Snapshot of current risk status for display in UI.
 */
data class RiskStatus(
    val balance: Double,
    val peakBalance: Double,
    val drawdownPercent: Double,
    val dailyLoss: Double,
    val weeklyLoss: Double,
    val consecutiveLosses: Int,
    val exposurePercent: Double,
    val kellyPercent: Double,
    val halted: Boolean,
    val haltReason: String,
)

/**
 * A completed trade outcome (for history tracking).
 */
data class TradeOutcome(
    val timestamp: Long,
    val pnl: Double,
    val win: Boolean,
    val symbol: String,
)
