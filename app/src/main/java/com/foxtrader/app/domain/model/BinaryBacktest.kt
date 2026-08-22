package com.foxtrader.app.domain.model

/**
 * Directional fixed-expiry signal used by the Deriv 3-minute binary strategy.
 *
 * The signal is created only after [signalIndex] has closed. A backtester/live
 * executor must therefore enter no earlier than the next 1-minute bar.
 */
data class BinarySignal(
    val signalIndex: Int,
    val timestamp: Long,
    val direction: Direction,
    val confidence: Int,
    val setupType: String,
    val reasons: List<String> = emptyList(),
)

enum class BinaryOutcome { WIN, LOSS, TIE }

/** A settled fixed-expiry directional contract in the binary simulator. */
data class BinaryBacktestTrade(
    val id: Int,
    val direction: Direction,
    val signalIndex: Int,
    val signalTime: Long,
    val entryIndex: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val expiryIndex: Int,
    val expiryTime: Long,
    val expiryPrice: Double,
    val outcome: BinaryOutcome,
    val stake: Double,
    val pnl: Double,
    val balanceAfter: Double,
    val confidence: Int,
    val setupType: String,
)

/**
 * Configuration for fixed-expiry directional backtesting.
 *
 * [payoutRatio] is net profit as a fraction of stake on a winning contract;
 * e.g. 0.85 means a 100 stake wins 85 net and a loss loses 100.
 */
data class BinaryBacktestConfig(
    val initialBalance: Double = 10_000.0,
    val riskPercent: Double = 1.0,
    val payoutRatio: Double = 0.85,
    val expiryBars: Int = 3,
    val minConfidence: Int = 72,
    val allowOverlappingContracts: Boolean = false,
)

data class BinaryBacktestMetrics(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val winRate: Double,
    val breakEvenWinRate: Double,
    val edgeVsBreakEven: Double,
    val netProfit: Double,
    val returnPercent: Double,
    val expectancyPerTrade: Double,
    val profitFactor: Double,
    val maxDrawdown: Double,
    val maxDrawdownPercent: Double,
    val maxConsecutiveWins: Int,
    val maxConsecutiveLosses: Int,
    val finalBalance: Double,
)

data class BinaryBacktestResult(
    val config: BinaryBacktestConfig,
    val symbol: String,
    val timeframe: Timeframe,
    val trades: List<BinaryBacktestTrade>,
    val metrics: BinaryBacktestMetrics,
    val equityCurve: List<EquityPoint>,
    val startDate: Long,
    val endDate: Long,
)
