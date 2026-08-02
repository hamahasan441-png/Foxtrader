package com.foxtrader.app.feature.trademanagement.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable UI state for the Trade Management dashboard.
 */
@Immutable
data class TradeManagementUiState(
    val managedTrades: ImmutableList<ManagedTrade> = persistentListOf(),
    val selectedTrade: ManagedTrade? = null,
    val hasActiveTrades: Boolean = false,
)
