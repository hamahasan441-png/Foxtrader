package com.foxtrader.app.domain.model

/** Price-only watchlist snapshot used by Home; contains no scanner score or trade direction. */
data class MarketMover(
    val symbol: String,
    val assetClass: AssetClass,
    val lastPrice: Double,
    val changePercent: Double,
)
