package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OptimizationObjective
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.tradepro.TradeProOptimizer
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
 * Drives the TRADEPRO parameter optimizer screen: sources candles, runs the sweep on the default
 * dispatcher, and provides "apply best config" persistence to AppPreferences.
 */
@HiltViewModel
class TradeProOptimizerViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val optimizer: TradeProOptimizer,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradeProOptimizerUiState())
    val uiState: StateFlow<TradeProOptimizerUiState> = _uiState.asStateFlow()

    fun setSymbol(symbol: String) {
        if (symbol == _uiState.value.symbol) return
        _uiState.update { it.copy(symbol = symbol, report = null, error = null, applied = false) }
    }

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe, report = null, error = null, applied = false) }
    }

    fun setObjective(objective: OptimizationObjective) {
        if (objective == _uiState.value.objective) return
        _uiState.update { it.copy(objective = objective, report = null, error = null, applied = false) }
    }

    fun runOptimization() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isRunning = true, error = null, applied = false) }
            try {
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                val candles = sourced.candles
                val baseConfig = appPreferences.tradeProConfig.value
                val report = withContext(defaultDispatcher) {
                    optimizer.optimize(
                        symbol = state.symbol,
                        candles = candles,
                        baseConfig = baseConfig,
                        baseTimeframe = state.timeframe,
                        objective = state.objective,
                    )
                }
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        report = report,
                        isSynthetic = sourced.isSynthetic,
                        error = null,
                    )
                }
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "Optimization failed.") }
            } catch (e: IllegalStateException) {
                _uiState.update { it.copy(isRunning = false, error = e.message ?: "Optimization failed.") }
            }
        }
    }

    fun applyBestConfig() {
        val best = _uiState.value.report?.best?.config ?: return
        appPreferences.setTradeProConfig(best)
        _uiState.update { it.copy(applied = true) }
    }
}
