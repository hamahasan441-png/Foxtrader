package com.foxtrader.app.feature.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.heatmap.MarketHeatmap
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerUseCase: ScannerUseCase,
    private val marketRepository: MarketRepository,
    private val marketHeatmap: MarketHeatmap,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Gather candle data for all watchlist symbols.
                //
                // Sourced fetch: the scanner ranks opportunities and the heatmap
                // implies sector rotation, so both must know whether they are
                // reading real prices or generated seed bars.
                val watchlist = scannerUseCase.getWatchlist()
                val dataMap = mutableMapOf<String, List<Candle>>()
                val heatmapInput = mutableMapOf<String, Pair<AssetClass, List<Candle>>>()
                var worstSource = CandleSource.LIVE

                for (ws in watchlist) {
                    if (!ws.enabled) continue
                    val sourced = marketRepository.getSourcedCandles(ws.symbol)
                    if (sourced.candles.isEmpty()) continue
                    dataMap[ws.symbol] = sourced.candles
                    heatmapInput[ws.symbol] = ws.assetClass to sourced.candles
                    worstSource = CandleSource.worstOf(listOf(worstSource, sourced.source))
                }

                val output = scannerUseCase(dataMap, _uiState.value.selectedStrategy)

                // Scoring and heatmap aggregation are CPU-bound across every
                // watchlist symbol; keep them off the main thread.
                val heatmap = withContext(defaultDispatcher) {
                    marketHeatmap.computeHeatmap(heatmapInput, HEATMAP_PERIOD)
                }

                _uiState.update {
                    it.copy(
                        results = output.results.toPersistentList(),
                        heatmap = heatmap,
                        dataSource = if (dataMap.isEmpty()) CandleSource.CACHED else worstSource,
                        isLoading = false,
                        lastScanTime = output.scannedAt,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectAssetClass(assetClass: AssetClass?) {
        _uiState.update { it.copy(selectedAssetClass = assetClass) }
    }

    fun selectRiskLevel(riskLevel: ScannerRiskLevel?) {
        _uiState.update { it.copy(selectedRiskLevel = riskLevel) }
    }

    fun selectSortMode(sortMode: ScannerSortMode) {
        _uiState.update { it.copy(selectedSortMode = sortMode) }
    }

    fun selectStrategy(strategy: StrategyType) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
        scan()
    }

    /** Toggling the view mode reuses the existing scan — no refetch needed. */
    fun selectViewMode(mode: ScannerViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    private companion object {
        /**
         * Bars the heatmap measures change over. Matches the scanner's own
         * lookback so the list and grid cannot disagree about a mover.
         */
        const val HEATMAP_PERIOD = 20
    }
}
