package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Watchlist
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for user-defined watchlists.
 *
 * Watchlists are user-authored data: they must survive process death and app
 * upgrades, which the previous in-memory `WatchlistManager` could not do.
 */
interface WatchlistRepository {

    /** Observe all watchlists (default first), each with its ordered symbols. */
    fun observeWatchlists(): Flow<List<Watchlist>>

    /** Ensure a default watchlist exists. Safe to call repeatedly. */
    suspend fun ensureSeeded()

    suspend fun createWatchlist(name: String): Watchlist

    /** @return false if the id is unknown or refers to the undeletable default. */
    suspend fun deleteWatchlist(id: String): Boolean

    suspend fun addSymbol(watchlistId: String, symbol: String)

    suspend fun removeSymbol(watchlistId: String, symbol: String)

    /** Move a symbol within its list, persisting the new order. */
    suspend fun moveSymbol(watchlistId: String, from: Int, to: Int)
}
