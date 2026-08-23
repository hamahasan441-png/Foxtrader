package com.foxtrader.app.domain.model

/**
 * Order-fill timing used by the historical SL/TP backtester.
 *
 * [SIGNAL_PRICE] preserves the legacy Foxtrader behavior: once a closed bar
 * confirms a signal, the engine treats the signal's declared entry price as
 * the fill price (plus directional slippage).
 *
 * [NEXT_BAR_OPEN] models TradingView-style market-order timing: a signal is
 * created from a closed bar and the market entry fills at the next bar open.
 * The signal bar remains recorded separately from the execution bar so AI and
 * any point-in-time analysis never gain access to the future fill candle.
 */
enum class BacktestExecutionMode(val displayName: String) {
    SIGNAL_PRICE("Signal price (legacy)"),
    NEXT_BAR_OPEN("Next bar open (TradingView)"),
}

/**
 * Backtester configuration — spread, commission, slippage, risk, and fill timing.
 */
data class BacktestConfig(
    val initialBalance: Double = 100_000.0,
    val spread: Double = 0.00002,
    val variableSpread: Boolean = true,
    val commissionPerLot: Double = 7.0,
    val slippage: Double = 0.00001,
    val riskPercent: Double = 1.0,
    val contractSize: Int = 100_000,
    /** Legacy by default so existing direct engine callers/tests remain stable. */
    val executionMode: BacktestExecutionMode = BacktestExecutionMode.SIGNAL_PRICE,
)

/**
 * A signal produced by a strategy at a given bar.
 */
data class StrategySignal(
    val index: Int,
    val timestamp: Long,
    val direction: Direction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val volume: Double? = null,
    val confidence: Int? = null,
    val setupType: String? = null,
)

/**
 * A completed backtest trade.
 */
data class BacktestTrade(
    val id: Int,
    val direction: Direction,
    /** Actual execution bar, which may differ from the signal bar. */
    val entryIndex: Int,
    /** Actual execution timestamp. */
    val entryTime: Long,
    val entryPrice: Double,
    val exitIndex: Int,
    val exitTime: Long,
    val exitPrice: Double,
    val volume: Double,
    val grossPnL: Double,
    val commission: Double,
    val netPnL: Double,
    val rMultiple: Double,
    val exitReason: ExitReason,
    val balanceAfter: Double,
    val setupType: String? = null,
    val holdingBars: Int,
    // --- AI scoring (populated by AiScoredBacktestEngine) ---
    val aiApproved: Boolean? = null,
    val aiGrade: String? = null,         // SignalGrade.name or null
    val aiConfidence: Double? = null,
    val aiConfluenceCount: Int? = null,
    /** Closed bar where the strategy decision was made. */
    val signalIndex: Int = entryIndex,
    /** Timestamp of the strategy decision bar. */
    val signalTime: Long = entryTime,
)

enum class ExitReason { TP, SL, END }

/** Outcome used by the on-chart backtest renderer. */
enum class BacktestOutcome { WIN, LOSS, BREAKEVEN }

/**
 * Lightweight chart projection of a completed backtest trade.
 *
 * Keeping this separate from [BacktestTrade] lets the chart render historical
 * entries/exits without depending on account-sizing details that are irrelevant
 * to the Canvas layer. Indices map directly onto the candle series used for the
 * backtest, so markers never drift from their source bars.
 */
data class BacktestChartMarker(
    val tradeId: Int,
    val direction: Direction,
    val entryIndex: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val exitIndex: Int,
    val exitTime: Long,
    val exitPrice: Double,
    val outcome: BacktestOutcome,
    val netPnL: Double,
    val rMultiple: Double,
    val exitReason: ExitReason,
)

/**
 * Full backtest performance metrics.
 */
data class BacktestMetrics(
    val netProfit: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val averageTrade: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val largestWin: Double,
    val largestLoss: Double,
    val maxDrawdown: Double,
    val maxDrawdownPercent: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val calmarRatio: Double,
    val recoveryFactor: Double,
    val avgHoldingBars: Double,
    val maxConsecutiveWins: Int,
    val maxConsecutiveLosses: Int,
    val finalBalance: Double,
    val returnPercent: Double,
    val totalCommission: Double,
)

/** A point on the equity curve. */
data class EquityPoint(
    val index: Int,
    val timestamp: Long,
    val balance: Double,
    val drawdown: Double,
    val drawdownPercent: Double,
)

/** Complete backtest result bundle. */
data class BacktestResult(
    val config: BacktestConfig,
    val trades: List<BacktestTrade>,
    val metrics: BacktestMetrics,
    val equityCurve: List<EquityPoint>,
    val startDate: Long,
    val endDate: Long,
    val durationDays: Double,
    val symbol: String,
    val timeframe: Timeframe,
    // --- AI scoring summary (populated by AiScoredBacktestEngine) ---
    val aiScoringEnabled: Boolean = false,
    val aiApprovalRate: Double? = null,       // % of trades AI would have approved
    val aiFilteredMetrics: BacktestMetrics? = null, // Metrics for AI-approved trades only
)
