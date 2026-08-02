package com.foxtrader.app.feature.trademanagement.presentation

import androidx.lifecycle.ViewModel
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.tradepro.TradeManagementEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the Trade Management dashboard. Holds active managed trades in memory
 * and exposes actions to open, close, select, and tick trades through their lifecycle.
 */
@HiltViewModel
class TradeManagementViewModel @Inject constructor(
    private val engine: TradeManagementEngine,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradeManagementUiState())
    val uiState: StateFlow<TradeManagementUiState> = _uiState.asStateFlow()

    private val trades = mutableListOf<ManagedTrade>()

    /**
     * Opens a new managed trade from a confirmed setup using the persisted config.
     */
    fun openTrade(setup: TradeProSetup) {
        val config = appPreferences.tradeProConfig.value
        val trade = engine.openTrade(setup, config)
        trades.add(trade)
        emitState()
    }

    /**
     * Closes the trade with the given [id] at the specified [price].
     */
    fun closeTrade(id: String, price: Double) {
        val index = trades.indexOfFirst { it.id == id }
        if (index < 0) return
        val closed = engine.closeManually(trades[index], price)
        trades[index] = closed
        emitState()
    }

    /**
     * Selects a trade for detail viewing.
     */
    fun selectTrade(id: String) {
        _uiState.update { state ->
            val selected = trades.firstOrNull { it.id == id }
            state.copy(selectedTrade = selected)
        }
    }

    /**
     * Advances all active trades by one candle bar. Called each time a new bar arrives.
     */
    fun tickAll(candle: Candle) {
        for (i in trades.indices) {
            val trade = trades[i]
            if (trade.state == ManagedTradeState.CLOSED) continue
            val (updated, _) = engine.tick(trade, candle)
            trades[i] = updated
        }
        emitState()
    }

    private fun emitState() {
        val snapshot = trades.toList()
        val active = snapshot.any { it.state != ManagedTradeState.CLOSED }
        _uiState.update { state ->
            state.copy(
                managedTrades = snapshot.toPersistentList(),
                hasActiveTrades = active,
                selectedTrade = state.selectedTrade?.let { sel ->
                    snapshot.firstOrNull { it.id == sel.id }
                },
            )
        }
    }
}
