package com.foxtrader.app.domain.usecase.watchlist

import com.foxtrader.app.domain.model.WatchlistSymbol
import com.foxtrader.app.domain.repository.WatchlistRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Returns the persisted active/default watchlist without depending on scanner code.
 *
 * Several features need a bounded market universe (TradePro, strategies, home),
 * but that is a watchlist concern rather than a scanner concern. Keeping this
 * query here lets the scanner feature be removed without losing the user's
 * persisted symbol list.
 */
class ActiveWatchlistSymbols @Inject constructor(
    private val repository: WatchlistRepository,
) {
    suspend operator fun invoke(limit: Int = Int.MAX_VALUE): List<WatchlistSymbol> {
        repository.ensureSeeded()
        val lists = repository.observeWatchlists().first()
        val active = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
        return active?.symbols.orEmpty().take(limit.coerceAtLeast(0))
    }
}
