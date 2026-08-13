package com.foxtrader.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ScreenerSymbol
import com.foxtrader.app.domain.repository.AlertRepository
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.repository.WatchlistRepository
import com.foxtrader.app.domain.usecase.home.HomeInsightComposer
import com.foxtrader.app.domain.usecase.journal.JournalEngine
import com.foxtrader.app.domain.usecase.portfolio.JournalPositionMapper
import com.foxtrader.app.domain.usecase.portfolio.PortfolioEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scannerUseCase: ScannerUseCase,
    private val marketRepository: MarketRepository,
    private val journalRepository: JournalRepository,
    private val alertRepository: AlertRepository,
    private val watchlistRepository: WatchlistRepository,
    private val journalEngine: JournalEngine,
    private val portfolioEngine: PortfolioEngine,
    private val positionMapper: JournalPositionMapper,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { watchlistRepository.ensureSeeded() }
        observeLocalBooks()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { scanMarkets() }
    }

    private fun observeLocalBooks() {
        combine(
            journalRepository.observeEntries(),
            alertRepository.observeAlerts(),
            watchlistRepository.observeWatchlists(),
            appPreferences.workspaceProfile,
            appPreferences.subscription,
        ) { entries, alerts, lists, profile, subscription ->
            LocalBooks(
                entries = entries,
                alerts = alerts,
                watchlist = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull(),
                profile = profile,
                subscription = subscription,
            )
        }.onEach { books ->
            val stats = journalEngine.computeStats(books.entries)
            val open = books.entries.filter { it.isOpen }
            val livePrices = open.associate { it.symbol.uppercase() to (it.entryPrice) }
            val equity = appPreferences.riskConfig.value.accountBalance
            val snapshot = if (open.isEmpty()) {
                null
            } else {
                withContext(defaultDispatcher) {
                    portfolioEngine.analyze(
                        positions = positionMapper.toPositions(open, livePrices),
                        accountEquity = equity,
                        correlationMatrix = null,
                    )
                }
            }
            _uiState.update { current ->
                val insights = HomeInsightComposer.compose(
                    results = current.movers,
                    stats = stats,
                    unreadAlerts = books.alerts.count { !it.acknowledged },
                    openTrades = open.size,
                    profile = books.profile,
                    synthetic = current.isSyntheticData,
                )
                current.copy(
                    profile = books.profile,
                    subscription = books.subscription,
                    watchlist = books.watchlist,
                    recentAlerts = books.alerts.take(4).toPersistentList(),
                    unreadAlerts = books.alerts.count { !it.acknowledged },
                    recentTrades = books.entries.take(4).toPersistentList(),
                    journalStats = stats,
                    openTrades = open.size,
                    portfolio = snapshot,
                    accountEquity = equity,
                    insights = insights.toPersistentList(),
                )
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun scanMarkets() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val watchlist = scannerUseCase.getWatchlist()
            val dataMap = linkedMapOf<String, List<com.foxtrader.app.domain.model.Candle>>()
            var worstSource = CandleSource.LIVE
            val symbols = preferredSymbols(watchlist)
            for (ws in symbols) {
                if (!ws.enabled) continue
                val sourced = marketRepository.getSourcedCandles(ws.symbol)
                if (sourced.candles.isEmpty()) continue
                dataMap[ws.symbol] = sourced.candles
                worstSource = CandleSource.worstOf(listOf(worstSource, sourced.source))
            }
            val output = withContext(defaultDispatcher) {
                scannerUseCase(dataMap)
            }
            val movers = output.results.sortedByDescending { kotlin.math.abs(it.changePercent) }.take(6)
            val signals = output.results.sortedByDescending { it.score }.take(5)
            _uiState.update { current ->
                val insights = HomeInsightComposer.compose(
                    results = output.results,
                    stats = current.journalStats,
                    unreadAlerts = current.unreadAlerts,
                    openTrades = current.openTrades,
                    profile = current.profile,
                    synthetic = worstSource == CandleSource.SYNTHETIC,
                )
                current.copy(
                    movers = movers.toPersistentList(),
                    signals = signals.toPersistentList(),
                    dataSource = if (dataMap.isEmpty()) CandleSource.CACHED else worstSource,
                    insights = insights.toPersistentList(),
                    isLoading = false,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoading = false, error = error.message ?: "Scan failed") }
        }
    }

    private fun preferredSymbols(watchlist: List<ScreenerSymbol>): List<ScreenerSymbol> {
        val preferred = _uiState.value.profile.markets
        val filtered = if (preferred.isEmpty()) watchlist else watchlist.filter { it.assetClass in preferred }
        return (filtered.ifEmpty { watchlist }).take(HOME_SCAN_LIMIT)
    }

    private data class LocalBooks(
        val entries: List<com.foxtrader.app.domain.model.JournalEntry>,
        val alerts: List<com.foxtrader.app.domain.model.FoxAlert>,
        val watchlist: com.foxtrader.app.domain.model.Watchlist?,
        val profile: com.foxtrader.app.domain.model.WorkspaceProfile,
        val subscription: com.foxtrader.app.domain.model.SubscriptionState,
    )

    private companion object {
        const val HOME_SCAN_LIMIT = 18
    }
}
