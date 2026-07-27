package com.foxtrader.app.feature.portfolio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import com.foxtrader.app.domain.usecase.portfolio.CorrelationClusterBuilder
import com.foxtrader.app.domain.usecase.portfolio.JournalPositionMapper
import com.foxtrader.app.domain.usecase.portfolio.PortfolioEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Portfolio screen ViewModel.
 *
 * Activates [PortfolioEngine], which shipped fully tested but with **zero call
 * sites** (masterplan Class B). The engine expects broker [Position] snapshots;
 * since no broker adapter exists yet, [JournalPositionMapper] supplies open
 * journal trades as the position book — the only honest record of what the user
 * is actually holding.
 *
 * Exposure and correlation maths run on [DefaultDispatcher]: correlation is
 * O(symbols² × period) and must not touch the main thread.
 */
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val journalRepository: JournalRepository,
    private val marketRepository: MarketRepository,
    private val portfolioEngine: PortfolioEngine,
    private val correlationMatrix: CorrelationMatrix,
    private val clusterBuilder: CorrelationClusterBuilder,
    private val positionMapper: JournalPositionMapper,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortfolioUiState())
    val uiState: StateFlow<PortfolioUiState> = _uiState.asStateFlow()

    init {
        journalRepository.observeEntries()
            .onEach { entries -> recompute(entries) }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch { recompute(journalRepository.getAllEntries()) }
    }

    /**
     * Rebuild the exposure snapshot and correlation clusters for the current
     * set of open trades.
     */
    private suspend fun recompute(entries: List<JournalEntry>) {
        val open = entries.filter { it.isOpen }
        if (open.isEmpty()) {
            _uiState.value = PortfolioUiState(
                snapshot = null,
                accountEquity = appPreferences.riskConfig.value.accountBalance,
                isLoading = false,
            )
            return
        }

        val symbols = open.map { it.symbol.uppercase() }.distinct()

        // Fetch the candle history each held symbol needs for correlation, and
        // the latest price for mark-to-market. Provenance is tracked so a
        // portfolio priced off synthetic bars is labelled, not trusted.
        val seriesBySymbol = mutableMapOf<String, List<Candle>>()
        var worstSource = CandleSource.LIVE
        symbols.forEach { symbol ->
            val sourced = marketRepository.getSourcedCandles(symbol, CORRELATION_TIMEFRAME)
            if (sourced.candles.isNotEmpty()) {
                seriesBySymbol[symbol] = sourced.candles
                worstSource = CandleSource.worstOf(listOf(worstSource, sourced.source))
            }
        }

        val livePrices = seriesBySymbol.mapValues { (_, candles) -> candles.last().close }
        val equity = appPreferences.riskConfig.value.accountBalance

        val (snapshot, clusters) = withContext(defaultDispatcher) {
            val positions = positionMapper.toPositions(open, livePrices)

            // Correlation needs at least two held symbols with usable history.
            val matrix = if (seriesBySymbol.size >= 2) {
                correlationMatrix.computeMatrix(seriesBySymbol, CORRELATION_PERIOD)
            } else null

            val result = portfolioEngine.analyze(
                positions = positions,
                accountEquity = equity,
                correlationMatrix = matrix,
            )

            val exposureBySymbol = result.positions
                .groupBy { it.symbol.uppercase() }
                .mapValues { (_, items) -> items.sumOf { it.exposurePercent } }

            result to clusterBuilder.build(exposureBySymbol, matrix).map { cluster ->
                CorrelationCluster(
                    symbols = cluster.symbols,
                    peakCorrelation = cluster.peakCorrelation,
                    combinedExposurePercent = cluster.combinedExposurePercent,
                    strength = cluster.strength,
                )
            }
        }

        _uiState.value = PortfolioUiState(
            snapshot = snapshot,
            correlationClusters = clusters,
            accountEquity = equity,
            dataSource = if (seriesBySymbol.isEmpty()) CandleSource.CACHED else worstSource,
            isLoading = false,
        )
    }

    private companion object {
        /** H1 balances a stable correlation read against intraday responsiveness. */
        val CORRELATION_TIMEFRAME = Timeframe.H1

        /** Bars of history used for the correlation window. */
        const val CORRELATION_PERIOD = 100
    }
}
