package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.WatchlistDao
import com.foxtrader.app.data.local.entity.WatchlistEntity
import com.foxtrader.app.data.local.entity.WatchlistSymbolEntity
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.AssetClassifier
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Watchlist
import com.foxtrader.app.domain.model.WatchlistSymbol
import com.foxtrader.app.domain.repository.MarketSymbolDirectory
import com.foxtrader.app.domain.repository.WatchlistRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed watchlists plus a non-persistent provider directory overlay. */
@Singleton
class WatchlistRepositoryImpl @Inject constructor(
    private val dao: WatchlistDao,
    private val appPreferences: AppPreferences,
    private val marketSymbolDirectory: MarketSymbolDirectory,
    @IoDispatcher private val io: CoroutineDispatcher,
) : WatchlistRepository {

    private val mutex = Mutex()
    private val directoryMutex = Mutex()
    private val cachedDirectories = mutableMapOf<DirectoryCacheKey, List<WatchlistSymbol>>()

    override fun observeWatchlists(): Flow<List<Watchlist>> =
        combine(
            dao.observeWatchlists(),
            dao.observeAllSymbols(),
            appPreferences.dataProvider,
            appPreferences.apiKeys,
        ) { lists, symbols, provider, _ ->
            val bySymbolList = symbols.groupBy { it.watchlistId }
            val persisted = lists.map { entity -> entity.toDomain(bySymbolList[entity.id].orEmpty()) }
            val providerSymbols = withContext(io) { resolveProviderDirectory(provider) }
            if (providerSymbols.isEmpty()) {
                persisted
            } else {
                // ChartWatchlistController selects the default watchlist. Overlay
                // the provider-native directory there without writing thousands
                // of discovered instruments into Room or mutating user lists.
                val activeIndex = persisted.indexOfFirst { it.isDefault }
                    .takeIf { it >= 0 } ?: persisted.indices.firstOrNull()
                if (activeIndex == null) {
                    persisted
                } else {
                    persisted.toMutableList().also { result ->
                        val active = result[activeIndex]
                        result[activeIndex] = active.copy(symbols = providerSymbols)
                    }
                }
            }
        }

    private suspend fun resolveProviderDirectory(provider: DataProvider): List<WatchlistSymbol> {
        if (!provider.implemented || provider == DataProvider.SAMPLE) return emptyList()
        val cacheKey = DirectoryCacheKey(
            provider = provider,
            credentialFingerprint = if (provider.requiresApiKey) {
                appPreferences.getApiKey(provider).orEmpty().hashCode()
            } else {
                0
            },
        )
        return directoryMutex.withLock {
            cachedDirectories[cacheKey]?.let { return@withLock it }
            val discovered = marketSymbolDirectory.discover(provider).getOrElse { return@withLock emptyList() }
            val mapped = discovered
                .filter { it.provider == provider && it.isTrading }
                .map { item ->
                    WatchlistSymbol(
                        symbol = item.providerSymbol,
                        assetClass = item.assetClass,
                        notes = "${provider.displayName} provider directory",
                    )
                }
                .distinctBy { it.symbol }
            if (mapped.isNotEmpty()) {
                // A changed API credential must not reuse a directory fetched
                // under the old entitlement. Keep only the current key for the
                // provider while retaining other providers for quick switching.
                cachedDirectories.keys.removeAll { it.provider == provider && it != cacheKey }
                cachedDirectories[cacheKey] = mapped
            }
            mapped
        }
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

    override suspend fun deleteWatchlist(id: String): Boolean = withContext(io) {
        dao.deleteWatchlist(id) > 0
    }

    override suspend fun addSymbol(watchlistId: String, symbol: String) = withContext(io) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isEmpty()) return@withContext
        mutex.withLock {
            val existing = dao.getSymbols(watchlistId)
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
        val DEFAULT_SYMBOLS = listOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD",
            "XAUUSD", "BTCUSDT", "ETHUSDT", "US30", "NAS100",
        )
    }

    private data class DirectoryCacheKey(
        val provider: DataProvider,
        val credentialFingerprint: Int,
    )
}
