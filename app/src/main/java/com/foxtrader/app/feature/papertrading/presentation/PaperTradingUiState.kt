package com.foxtrader.app.feature.papertrading.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.usecase.orders.PaperPosition
import com.foxtrader.app.domain.usecase.orders.PaperTradingSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Immutable UI state for the Paper Trading screen. */
@Immutable
data class PaperTradingUiState(
    val startingBalance: Double = 0.0,
    val balance: Double = 0.0,
    val equity: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val positions: ImmutableList<PaperPosition> = persistentListOf(),
    val closedTradeCount: Int = 0,
    val market: PaperTradingSession.MarketSnapshot? = null,
    val orderVolume: Double = 0.1,
    /** Risk-gate rejection reasons from the most recent order attempt. */
    val rejectionReasons: ImmutableList<String> = persistentListOf(),
    /** The risk-computed volume that actually filled on the last accepted order. */
    val lastFilledVolume: Double? = null,
    val isLoading: Boolean = true,
) {
    val hasPositions: Boolean get() = positions.isNotEmpty()
    val canTrade: Boolean get() = market != null
}
