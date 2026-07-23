package com.foxtrader.app.feature.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerUseCase: ScannerUseCase,
    private val marketRepository: MarketRepository,
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
                // Gather candle data for all watchlist symbols
                val watchlist = scannerUseCase.getWatchlist()
                val dataMap = mutableMapOf<String, List<Candle>>()
                for (ws in watchlist) {
                    if (!ws.enabled) continue
                    val candles = marketRepository.getCandles(ws.symbol)
                    if (candles.isNotEmpty()) dataMap[ws.symbol] = candles
                }

                val output = scannerUseCase(dataMap, _uiState.value.selectedStrategy)
                _uiState.update {
                    it.copy(
                        results = output.results,
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

    fun selectStrategy(strategy: StrategyType) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
        scan()
    }
}
