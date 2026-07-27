package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.foxtrader.app.data.local.entity.WatchlistEntity
import com.foxtrader.app.data.local.entity.WatchlistSymbolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlists ORDER BY isDefault DESC, createdAt ASC")
    fun observeWatchlists(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist_symbols ORDER BY watchlistId, position ASC")
    fun observeAllSymbols(): Flow<List<WatchlistSymbolEntity>>

    @Query("SELECT * FROM watchlists ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getWatchlists(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist_symbols WHERE watchlistId = :id ORDER BY position ASC")
    suspend fun getSymbols(id: String): List<WatchlistSymbolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchlist(watchlist: WatchlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSymbols(symbols: List<WatchlistSymbolEntity>)

    @Query("DELETE FROM watchlists WHERE id = :id AND isDefault = 0")
    suspend fun deleteWatchlist(id: String): Int

    @Query("DELETE FROM watchlist_symbols WHERE watchlistId = :watchlistId AND symbol = :symbol")
    suspend fun deleteSymbol(watchlistId: String, symbol: String)

    @Query("DELETE FROM watchlist_symbols WHERE watchlistId = :watchlistId")
    suspend fun clearSymbols(watchlistId: String)

    @Query("SELECT COUNT(*) FROM watchlists")
    suspend fun countWatchlists(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM watchlist_symbols WHERE watchlistId = :watchlistId")
    suspend fun maxPosition(watchlistId: String): Int

    /**
     * Replace a watchlist's membership wholesale, in one transaction.
     *
     * Reordering renumbers every row's `position`; doing that as separate
     * writes would let an observer briefly see duplicate or missing positions.
     */
    @Transaction
    suspend fun replaceSymbols(watchlistId: String, symbols: List<WatchlistSymbolEntity>) {
        clearSymbols(watchlistId)
        upsertSymbols(symbols)
    }
}
