package com.foxtrader.app.domain.usecase.watchlist

import com.foxtrader.app.domain.model.AssetClass
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * Watchlist Manager — organizes symbols into custom watchlists.
 *
 * Features:
 * - Multiple named watchlists
 * - Drag-and-drop reordering
 * - Asset class grouping
 * - Quick-add from scanner results
 * - Default watchlist (cannot be deleted)
 */
@Singleton
class WatchlistManager @Inject constructor() {

    data class Watchlist(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val symbols: List<WatchlistSymbol> = emptyList(),
        val isDefault: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class WatchlistSymbol(
        val symbol: String,
        val assetClass: AssetClass,
        val addedAt: Long = System.currentTimeMillis(),
        val notes: String = "",
    )

    private val watchlists = mutableListOf(
        Watchlist(name = "Main", isDefault = true, symbols = defaultSymbols())
    )

    fun getWatchlists(): List<Watchlist> = watchlists.toList()

    fun getDefault(): Watchlist = watchlists.first { it.isDefault }

    fun createWatchlist(name: String): Watchlist {
        val wl = Watchlist(name = name)
        watchlists.add(wl)
        return wl
    }

    fun deleteWatchlist(id: String): Boolean {
        val wl = watchlists.firstOrNull { it.id == id } ?: return false
        if (wl.isDefault) return false
        return watchlists.remove(wl)
    }

    fun addSymbol(watchlistId: String, symbol: String, assetClass: AssetClass) {
        val idx = watchlists.indexOfFirst { it.id == watchlistId }
        if (idx < 0) return
        val wl = watchlists[idx]
        if (wl.symbols.any { it.symbol == symbol }) return
        watchlists[idx] = wl.copy(
            symbols = wl.symbols + WatchlistSymbol(symbol, assetClass)
        )
    }

    fun removeSymbol(watchlistId: String, symbol: String) {
        val idx = watchlists.indexOfFirst { it.id == watchlistId }
        if (idx < 0) return
        val wl = watchlists[idx]
        watchlists[idx] = wl.copy(
            symbols = wl.symbols.filter { it.symbol != symbol }
        )
    }

    fun reorderSymbol(watchlistId: String, from: Int, to: Int) {
        val idx = watchlists.indexOfFirst { it.id == watchlistId }
        if (idx < 0) return
        val wl = watchlists[idx]
        val list = wl.symbols.toMutableList()
        if (from in list.indices && to in list.indices) {
            val item = list.removeAt(from)
            list.add(to, item)
            watchlists[idx] = wl.copy(symbols = list)
        }
    }

    private fun defaultSymbols() = listOf(
        WatchlistSymbol("EURUSD", AssetClass.FOREX),
        WatchlistSymbol("GBPUSD", AssetClass.FOREX),
        WatchlistSymbol("BTCUSDT", AssetClass.CRYPTO),
        WatchlistSymbol("XAUUSD", AssetClass.METALS),
        WatchlistSymbol("US30", AssetClass.INDICES),
    )
}
