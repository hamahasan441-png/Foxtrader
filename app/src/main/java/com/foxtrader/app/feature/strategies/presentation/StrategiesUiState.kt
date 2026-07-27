package com.foxtrader.app.feature.strategies.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Direction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A single actionable strategy signal shown on the Strategies screen.
 */
@Immutable
data class StrategySignalItem(
    val id: String,
    val symbol: String,
    val strategyName: String,   // e.g. "Harmonic Gartley", "Order Block", "BOS Continuation"
    val direction: Direction,
    val confidence: Int,        // 0-100
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskReward: Double,
    val signalProvider: String = "",
    val note: String = "",
)

/**
 * Immutable UI state for the Strategies screen.
 */
@Immutable
data class StrategiesUiState(
    val signals: ImmutableList<StrategySignalItem> = persistentListOf(),
    val isScanning: Boolean = false,
    val lastScanTime: Long = 0L,
    val error: String? = null,
    // --- AI-scored backtest comparison ---
    val aiBacktestEnabled: Boolean = false,
    val aiApprovalRate: Double? = null,       // % of strategy trades the AI approved
    val allTradesWinRate: Double? = null,     // standard backtest win rate
    val aiFilteredWinRate: Double? = null,    // win rate of AI-approved trades only
    val allTradesProfitFactor: Double? = null,
    val aiFilteredProfitFactor: Double? = null,
    val backtestTradeCount: Int? = null,
    val aiApprovedTradeCount: Int? = null,
) {
    val hasSignals: Boolean get() = signals.isNotEmpty()
    val bullishCount: Int get() = signals.count { it.direction == Direction.BULLISH }
    val bearishCount: Int get() = signals.count { it.direction == Direction.BEARISH }
}
