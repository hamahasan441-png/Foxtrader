package com.foxtrader.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.MarketMover
import com.foxtrader.app.domain.model.WatchlistSymbol
import com.foxtrader.app.domain.repository.AlertRepository
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.repository.WatchlistRepository
import com.foxtrader.app.domain.usecase.home.HomeInsightComposer
import com.foxtrader.app.domain.usecase.portfolio.JournalPositionMapper
import com.foxtrader.app.domain.usecase.portfolio.PortfolioEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.watchlist.ActiveWatchlistSymbols
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
import kotlin.math.abs
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val activeWatchlistSymbols: ActiveWatchlistSymbols,
    private val marketRepository: MarketRepository,
    private val journalRepository: JournalRepository,
    private val alertRepository: AlertRepository,
    private val watchlistRepository: WatchlistRepository,
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
        viewModelScope.launch { refreshMarketSnapshot() }
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
            val open = books.entries.filter { it.isOpen }
            val livePrices = open.associate { it.symbol.uppercase() to it.entryPrice }
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
                    unreadAlerts = books.alerts.count { !it.acknowledged },
                    openPositions = open.size,
                    profile = books.profile,
                    synthetic = current.isSyntheticData,
                )
                current.copy(
                    profile = books.profile,
                    subscription = books.subscription,
                    watchlist = books.watchlist,
                    recentAlerts = books.alerts.take(4).toPersistentList(),
                    unreadAlerts = books.alerts.count { !it.acknowledged },
                    openPositions = open.size,
                    portfolio = snapshot,
                    accountEquity = equity,
                    insights = insights.toPersistentList(),
                )
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun refreshMarketSnapshot() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val watchlist = activeWatchlistSymbols(HOME_MARKET_LIMIT)
            val symbols = preferredSymbols(watchlist)
            val movers = mutableListOf<MarketMover>()
            var worstSource = CandleSource.LIVE
            var sourcedCount = 0

            for (item in symbols) {
                val sourced = marketRepository.getSourcedCandles(item.symbol)
                val candles = sourced.candles
                if (candles.isEmpty()) continue
                sourcedCount += 1
                worstSource = CandleSource.worstOf(listOf(worstSource, sourced.source))
                val last = candles.last()
                val startIndex = (candles.lastIndex - CHANGE_LOOKBACK_BARS).coerceAtLeast(0)
                val startPrice = candles[startIndex].close
                val change = if (
                    last.close.isFinite() &&
                    startPrice.isFinite() &&
                    abs(startPrice) > MIN_PRICE
                ) {
                    ((last.close - startPrice) / startPrice) * 100.0
                } else {
                    0.0
                }
                movers += MarketMover(
                    symbol = item.symbol,
                    assetClass = item.assetClass,
                    lastPrice = last.close,
                    changePercent = change,
                )
            }

            val sortedMovers = movers
                .sortedByDescending { abs(it.changePercent) }
                .take(HOME_MOVER_LIMIT)
            _uiState.update { current ->
                val source = if (sourcedCount == 0) CandleSource.CACHED else worstSource
                val insights = HomeInsightComposer.compose(
                    results = movers,
                    unreadAlerts = current.unreadAlerts,
                    openPositions = current.openPositions,
                    profile = current.profile,
                    synthetic = source == CandleSource.SYNTHETIC,
                )
                current.copy(
                    movers = sortedMovers.toPersistentList(),
                    dataSource = source,
                    insights = insights.toPersistentList(),
                    isLoading = false,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoading = false, error = error.message ?: "Snapshot failed") }
        }
    }

    private fun preferredSymbols(watchlist: List<WatchlistSymbol>): List<WatchlistSymbol> {
        val preferred = _uiState.value.profile.markets
        val filtered = if (preferred.isEmpty()) watchlist else watchlist.filter { it.assetClass in preferred }
        return filtered.ifEmpty { watchlist }.take(HOME_MARKET_LIMIT)
    }

    private data class LocalBooks(
        val entries: List<com.foxtrader.app.domain.model.JournalEntry>,
        val alerts: List<com.foxtrader.app.domain.model.FoxAlert>,
        val watchlist: com.foxtrader.app.domain.model.Watchlist?,
        val profile: com.foxtrader.app.domain.model.WorkspaceProfile,
        val subscription: com.foxtrader.app.domain.model.SubscriptionState,
    )

    private companion object {
        const val HOME_MARKET_LIMIT = 18
        const val HOME_MOVER_LIMIT = 6
        const val CHANGE_LOOKBACK_BARS = 20
        const val MIN_PRICE = 1e-9
    }
}
