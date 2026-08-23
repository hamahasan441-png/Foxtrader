package com.foxtrader.app.feature.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.LitXSignalRecord
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.LitXSignalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.heatmap.MarketHeatmap
import com.foxtrader.app.domain.usecase.scanner.ScannerContextEnricher
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.strategies.StrategyMarketContextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerUseCase: ScannerUseCase,
    private val marketRepository: MarketRepository,
    private val litXSignalRepository: LitXSignalRepository,
    private val marketHeatmap: MarketHeatmap,
    private val strategyContextProvider: StrategyMarketContextProvider,
    private val scannerContextEnricher: ScannerContextEnricher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, historyWarning = null) }
            try {
                // Freeze one wall-clock boundary for the entire scan. Base strategy
                // analysis and external SMT/HTF context must see the exact same
                // confirmed primary prefix even if a candle closes mid-scan.
                val scanNow = System.currentTimeMillis()
                val watchlist = scannerUseCase.getWatchlist()
                val rawDataMap = mutableMapOf<String, List<Candle>>()
                val confirmedDataMap = mutableMapOf<String, List<Candle>>()
                val sourceBySymbol = mutableMapOf<String, CandleSource>()
                val heatmapInput = mutableMapOf<String, Pair<AssetClass, List<Candle>>>()
                var worstSource = CandleSource.LIVE

                for (ws in watchlist) {
                    if (!ws.enabled) continue
                    val sourced = marketRepository.getSourcedCandles(ws.symbol, SCANNER_TIMEFRAME)
                    if (sourced.candles.isEmpty()) continue
                    rawDataMap[ws.symbol] = sourced.candles
                    val confirmed = ConfirmedBarPolicy.confirmedPrefix(
                        candles = sourced.candles,
                        timeframe = SCANNER_TIMEFRAME,
                        nowMillis = scanNow,
                    )
                    if (confirmed.isNotEmpty()) confirmedDataMap[ws.symbol] = confirmed
                    sourceBySymbol[ws.symbol] = sourced.source
                    heatmapInput[ws.symbol] = ws.assetClass to sourced.candles
                    worstSource = CandleSource.worstOf(listOf(worstSource, sourced.source))
                }

                // ScannerUseCase applies ConfirmedBarPolicy too; passing the frozen
                // prefix here makes that second guard idempotent and prevents a new
                // bar from appearing between base and context analysis.
                val output = scannerUseCase(
                    dataMap = confirmedDataMap,
                    strategy = _uiState.value.selectedStrategy,
                    timeframe = SCANNER_TIMEFRAME,
                )

                // External context now goes through the same canonical provider
                // used by other strategy consumers. Scanner no longer fetches a
                // separate hand-written H4/D1 set or reruns its own SMT detector.
                val contextualResults = mutableListOf<com.foxtrader.app.domain.model.ScreenerResult>()
                for (result in output.results) {
                    val source = sourceBySymbol[result.symbol] ?: CandleSource.CACHED
                    val context = strategyContextProvider.load(
                        symbol = result.symbol,
                        timeframe = SCANNER_TIMEFRAME,
                        // CandleEntity currently persists source trust but not the
                        // originating provider id. Do not mislabel cached bars as
                        // the user's current provider until provider provenance is
                        // persisted alongside the series.
                        provider = null,
                        freshness = source.toMarketDataFreshness(),
                        refreshMissingPeers = false,
                    )
                    val enriched = withContext(defaultDispatcher) {
                        scannerContextEnricher.enrich(
                            base = result,
                            baseCandles = confirmedDataMap[result.symbol].orEmpty(),
                            timeframe = SCANNER_TIMEFRAME,
                            context = context,
                        )
                    }
                    contextualResults += enriched
                }
                contextualResults.sortByDescending { it.score }

                // Persist only validated signals whose exact candle series is
                // trustworthy. Per-symbol provenance is required here: one
                // synthetic symbol must neither authorise nor suppress another
                // symbol's durable history.
                val historyRecords = output.validatedLitXSignals
                    .filter { sourceBySymbol[it.symbol]?.isTrustworthy == true }
                    .map { LitXSignalRecord.from(it) }
                val historyWarning = try {
                    if (historyRecords.isNotEmpty()) litXSignalRepository.saveAll(historyRecords)
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    "Scan completed, but LIT X history could not be saved."
                }

                // Heatmap remains a view of the freshest sourced market snapshot;
                // its provenance warning is independent of strategy causality.
                val heatmap = withContext(defaultDispatcher) {
                    marketHeatmap.computeHeatmap(heatmapInput, HEATMAP_PERIOD)
                }

                _uiState.update {
                    it.copy(
                        results = contextualResults.toPersistentList(),
                        heatmap = heatmap,
                        dataSource = if (rawDataMap.isEmpty()) CandleSource.CACHED else worstSource,
                        isLoading = false,
                        lastScanTime = output.scannedAt,
                        historyWarning = historyWarning,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
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

    fun toggleConfirmedOnly() {
        _uiState.update { it.copy(confirmedOnly = !it.confirmedOnly) }
    }

    fun selectStrategy(strategy: StrategyType) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
        scan()
    }

    /** Toggling the view mode reuses the existing scan — no refetch needed. */
    fun selectViewMode(mode: ScannerViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    private fun CandleSource.toMarketDataFreshness(): MarketDataFreshness = when (this) {
        CandleSource.LIVE -> MarketDataFreshness.LIVE
        CandleSource.CACHED -> MarketDataFreshness.CACHED
        CandleSource.SYNTHETIC -> MarketDataFreshness.SIMULATED
    }

    private companion object {
        /**
         * Bars the heatmap measures change over. Matches the scanner's own
         * lookback so the list and grid cannot disagree about a mover.
         */
        const val HEATMAP_PERIOD = 20
        val SCANNER_TIMEFRAME = Timeframe.H1
    }
}
