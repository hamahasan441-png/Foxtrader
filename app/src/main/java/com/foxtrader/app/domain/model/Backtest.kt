package com.foxtrader.app.domain.model

/**
 * Backtester configuration — spread, commission, slippage, risk.
 */
data class BacktestConfig(
    val initialBalance: Double = 100_000.0,
    val spread: Double = 0.00002,
    val variableSpread: Boolean = true,
    val commissionPerLot: Double = 7.0,
    val slippage: Double = 0.00001,
    val riskPercent: Double = 1.0,
    val contractSize: Int = 100_000,
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
    val entryIndex: Int,
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
)

enum class ExitReason { TP, SL, END }

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
)
