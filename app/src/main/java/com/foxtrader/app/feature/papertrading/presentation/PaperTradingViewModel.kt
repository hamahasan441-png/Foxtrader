package com.foxtrader.app.feature.papertrading.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.usecase.orders.PaperTradingSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paper Trading screen ViewModel — a thin reactive shell over
 * [PaperTradingSession]. Combines the shared account/market flows with the
 * locally-chosen order volume; all trading actions delegate to the session.
 */
@HiltViewModel
class PaperTradingViewModel @Inject constructor(
    private val session: PaperTradingSession,
) : ViewModel() {

    private val _orderVolume = MutableStateFlow(DEFAULT_VOLUME)

    val uiState: StateFlow<PaperTradingUiState> =
        combine(session.account, session.market, _orderVolume) { account, market, volume ->
            PaperTradingUiState(
                startingBalance = account.startingBalance,
                balance = account.balance,
                equity = account.equity,
                realizedPnl = account.realizedPnl,
                unrealizedPnl = account.unrealizedPnl,
                positions = account.positions.toPersistentList(),
                closedTradeCount = account.closedTrades.size,
                market = market,
                orderVolume = volume,
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PaperTradingUiState())

    fun increaseVolume() {
        _orderVolume.value = roundVolume(_orderVolume.value + VOLUME_STEP)
    }

    fun decreaseVolume() {
        _orderVolume.value = roundVolume((_orderVolume.value - VOLUME_STEP).coerceAtLeast(VOLUME_STEP))
    }

    fun buy() {
        val symbol = session.market.value?.symbol ?: return
        viewModelScope.launch { session.buy(symbol, _orderVolume.value) }
    }

    fun sell() {
        val symbol = session.market.value?.symbol ?: return
        viewModelScope.launch { session.sell(symbol, _orderVolume.value) }
    }

    fun close(positionId: String) {
        viewModelScope.launch { session.close(positionId) }
    }

    fun reset() {
        viewModelScope.launch { session.reset() }
    }

    private fun roundVolume(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private companion object {
        const val DEFAULT_VOLUME = 0.1
        const val VOLUME_STEP = 0.1
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
