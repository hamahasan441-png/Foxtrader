package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.WatchlistEntity
import com.foxtrader.app.data.local.entity.WatchlistSymbolEntity
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Watchlist
import com.foxtrader.app.domain.model.WatchlistSymbol

fun WatchlistSymbolEntity.toDomain(): WatchlistSymbol = WatchlistSymbol(
    symbol = symbol,
    // An unrecognised stored class must not crash the list; STOCKS is the
    // same neutral fallback AssetClassifier uses.
    assetClass = runCatching { AssetClass.valueOf(assetClass) }
        .getOrDefault(AssetClass.STOCKS),
    notes = notes,
    addedAt = addedAt,
)

fun WatchlistEntity.toDomain(symbols: List<WatchlistSymbolEntity>): Watchlist = Watchlist(
    id = id,
    name = name,
    symbols = symbols.sortedBy { it.position }.map { it.toDomain() },
    isDefault = isDefault,
    createdAt = createdAt,
)

fun WatchlistSymbol.toEntity(watchlistId: String, position: Int): WatchlistSymbolEntity =
    WatchlistSymbolEntity(
        watchlistId = watchlistId,
        symbol = symbol,
        assetClass = assetClass.name,
        position = position,
        notes = notes,
        addedAt = addedAt,
    )
