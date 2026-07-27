package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.WatchlistDao
import com.foxtrader.app.data.local.entity.WatchlistEntity
import com.foxtrader.app.data.local.entity.WatchlistSymbolEntity
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.AssetClassifier
import com.foxtrader.app.domain.model.Watchlist
import com.foxtrader.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed watchlists.
 *
 * Replaces the in-memory `WatchlistManager`, whose `mutableListOf` state was
 * lost on every process death.
 */
@Singleton
class WatchlistRepositoryImpl @Inject constructor(
    private val dao: WatchlistDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : WatchlistRepository {

    /**
     * Guards read-modify-write sequences (seeding, append, reorder).
     *
     * Room gives per-statement atomicity, not per-sequence: two concurrent
     * `addSymbol` calls could both read the same `maxPosition` and write
     * duplicate positions, and two callers racing `ensureSeeded` could both
     * see an empty table and create two default watchlists.
     */
    private val mutex = Mutex()

    override fun observeWatchlists(): Flow<List<Watchlist>> =
        combine(dao.observeWatchlists(), dao.observeAllSymbols()) { lists, symbols ->
            val bySymbolList = symbols.groupBy { it.watchlistId }
            lists.map { entity -> entity.toDomain(bySymbolList[entity.id].orEmpty()) }
        }

    override suspend fun ensureSeeded() = withContext(io) {
        mutex.withLock {
            if (dao.countWatchlists() > 0) return@withLock
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.upsertWatchlist(
                WatchlistEntity(id = id, name = DEFAULT_NAME, isDefault = true, createdAt = now)
            )
            dao.upsertSymbols(
                DEFAULT_SYMBOLS.mapIndexed { index, symbol ->
                    WatchlistSymbolEntity(
                        watchlistId = id,
                        symbol = symbol,
                        assetClass = AssetClassifier.classify(symbol).name,
                        position = index,
                        notes = "",
                        addedAt = now,
                    )
                }
            )
        }
    }

    override suspend fun createWatchlist(name: String): Watchlist = withContext(io) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = WatchlistEntity(
            id = id,
            name = name.trim().ifBlank { "Untitled" },
            isDefault = false,
            createdAt = now,
        )
        dao.upsertWatchlist(entity)
        entity.toDomain(emptyList())
    }

    /** The DAO's `isDefault = 0` predicate is what makes the default undeletable. */
    override suspend fun deleteWatchlist(id: String): Boolean = withContext(io) {
        dao.deleteWatchlist(id) > 0
    }

    override suspend fun addSymbol(watchlistId: String, symbol: String) = withContext(io) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isEmpty()) return@withContext
        mutex.withLock {
            val existing = dao.getSymbols(watchlistId)
            // Idempotent: re-adding must not duplicate or reshuffle the list.
            if (existing.any { it.symbol == normalized }) return@withLock
            dao.upsertSymbols(
                listOf(
                    WatchlistSymbolEntity(
                        watchlistId = watchlistId,
                        symbol = normalized,
                        assetClass = AssetClassifier.classify(normalized).name,
                        position = dao.maxPosition(watchlistId) + 1,
                        notes = "",
                        addedAt = System.currentTimeMillis(),
                    )
                )
            )
        }
    }

    override suspend fun removeSymbol(watchlistId: String, symbol: String) = withContext(io) {
        mutex.withLock {
            dao.deleteSymbol(watchlistId, symbol.trim().uppercase())
            // Renumber so positions stay dense; a gap would make a later
            // index-based move target the wrong row.
            val remaining = dao.getSymbols(watchlistId)
            dao.replaceSymbols(
                watchlistId,
                remaining.mapIndexed { index, entity -> entity.copy(position = index) },
            )
        }
    }

    override suspend fun moveSymbol(watchlistId: String, from: Int, to: Int) = withContext(io) {
        mutex.withLock {
            val current = dao.getSymbols(watchlistId).toMutableList()
            if (from !in current.indices || to !in current.indices || from == to) return@withLock
            val moved = current.removeAt(from)
            current.add(to, moved)
            dao.replaceSymbols(
                watchlistId,
                current.mapIndexed { index, entity -> entity.copy(position = index) },
            )
        }
    }

    private companion object {
        const val DEFAULT_NAME = "Main"

        /**
         * Seeded once into the default watchlist. Mirrors the old hardcoded
         * `ChartUiState.DEFAULT_SYMBOLS`, but is now a starting point the user
         * can edit rather than a fixed list.
         */
        val DEFAULT_SYMBOLS = listOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD",
            "XAUUSD", "BTCUSDT", "ETHUSDT", "US30", "NAS100",
        )
    }
}
