package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns the chart's watchlist concern: seeding, observing the active list, and
 * add/remove of symbols. Holds the active-list id internally so the ViewModel
 * no longer has to thread it through UI state for mutations.
 *
 * This is a plain class instantiated by [ChartViewModel]; [onWatchlistChange]
 * pushes the resolved symbols + active id back into UI state.
 */
internal class ChartWatchlistController(
    private val watchlistRepository: WatchlistRepository,
    private val scope: CoroutineScope,
    private val onWatchlistChange: (symbols: List<String>, activeWatchlistId: String?) -> Unit,
) {
    @Volatile
    private var activeWatchlistId: String? = null

    fun observe() {
        scope.launch { watchlistRepository.ensureSeeded() }
        watchlistRepository.observeWatchlists()
            .onEach { lists ->
                val active = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
                activeWatchlistId = active?.id
                onWatchlistChange(active?.symbolNames.orEmpty(), active?.id)
            }
            .launchIn(scope)
    }

    fun addSymbol(symbol: String) {
        val listId = activeWatchlistId ?: return
        scope.launch { watchlistRepository.addSymbol(listId, symbol) }
    }

    fun removeSymbol(symbol: String) {
        val listId = activeWatchlistId ?: return
        scope.launch { watchlistRepository.removeSymbol(listId, symbol) }
    }
}
