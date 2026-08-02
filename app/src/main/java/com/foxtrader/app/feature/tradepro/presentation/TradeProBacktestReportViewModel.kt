package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.tradepro.TradeProBacktestEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs the full-lifecycle TRADEPRO backtest over sourced candles for the selected
 * symbol/timeframe, using the user's configured [com.foxtrader.app.domain.model.tradepro.TradeProConfig].
 *
 * Provenance is respected: the report flags synthetic data so an illustrative run
 * over generated bars is never mistaken for a real edge.
 */
@HiltViewModel
class TradeProBacktestReportViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val backtestEngine: TradeProBacktestEngine,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradeProBacktestReportUiState())
    val uiState: StateFlow<TradeProBacktestReportUiState> = _uiState.asStateFlow()

    init {
        runReport()
    }

    fun setSymbol(symbol: String) {
        if (symbol == _uiState.value.symbol) return
        _uiState.update { it.copy(symbol = symbol, result = null, error = null) }
        runReport()
    }

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe, result = null, error = null) }
        runReport()
    }

    fun runReport() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isRunning = true, error = null) }
            try {
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                val candles = sourced.candles
                val config = appPreferences.tradeProConfig.value
                val result = withContext(defaultDispatcher) {
                    backtestEngine.run(state.symbol, candles, config)
                }
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        result = result,
                        isSynthetic = sourced.isSynthetic,
                        error = null,
                    )
                }
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "Backtest failed.") }
            } catch (e: IllegalStateException) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "Backtest failed.") }
            }
        }
    }
}
