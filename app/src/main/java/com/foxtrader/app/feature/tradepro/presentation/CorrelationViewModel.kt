package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.tradepro.CorrelationEngine
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
 * Loads the watchlist candle sets and computes the correlation matrix off the main thread.
 */
@HiltViewModel
class CorrelationViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val scannerUseCase: ScannerUseCase,
    private val correlationEngine: CorrelationEngine,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CorrelationUiState())
    val uiState: StateFlow<CorrelationUiState> = _uiState.asStateFlow()

    init {
        compute()
    }

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe) }
        compute()
    }

    fun compute() {
        viewModelScope.launch {
            val timeframe = _uiState.value.timeframe
            _uiState.update { it.copy(isComputing = true, error = null) }
            try {
                val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(MAX_SYMBOLS)
                val candlesBySymbol = LinkedHashMap<String, List<Candle>>(watchlist.size)
                for (entry in watchlist) {
                    val candles = repository.getSourcedCandles(entry.symbol, timeframe).candles
                    if (candles.size > MIN_BARS) candlesBySymbol[entry.symbol] = candles
                }
                val matrix = withContext(defaultDispatcher) {
                    correlationEngine.compute(candlesBySymbol)
                }
                _uiState.update { it.copy(isComputing = false, matrix = matrix) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isComputing = false, error = e.message ?: "Failed to compute correlations.") }
            }
        }
    }

    companion object {
        private const val MAX_SYMBOLS = 14
        private const val MIN_BARS = 10
    }
}
