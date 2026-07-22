package com.foxtrader.app.feature.chart.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chart screen ViewModel (MVVM).
 * - Observes candles from the repository (offline-first, single source of truth)
 * - Runs non-repainting market-structure analysis on each update
 * - Exposes a single immutable [ChartUiState]
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChartUiState())
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    private val symbolFlow = MutableStateFlow(ChartUiState().symbol)
    private val timeframeFlow = MutableStateFlow(ChartUiState().timeframe)

    init {
        observeMarket()
        refresh()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMarket() {
        combine(symbolFlow, timeframeFlow) { symbol, tf -> symbol to tf }
            .flatMapLatest { (symbol, tf) -> repository.observeCandles(symbol, tf) }
            .onEach { candles ->
                val structure = analyzeStructure(candles)
                _uiState.value = _uiState.value.copy(
                    candles = candles,
                    bias = structure.bias,
                    structureBreaks = structure.breaks,
                    isLoading = candles.isEmpty() && _uiState.value.error == null,
                )
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.refreshCandles(symbolFlow.value, timeframeFlow.value)
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load market data",
                    )
                }
        }
    }

    fun onSymbolChange(symbol: String) {
        symbolFlow.value = symbol
        _uiState.value = _uiState.value.copy(symbol = symbol)
        refresh()
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        timeframeFlow.value = timeframe
        _uiState.value = _uiState.value.copy(timeframe = timeframe)
        refresh()
    }
}
