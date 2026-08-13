package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/** Asset class categories for the screener watchlist. */
@Serializable
enum class AssetClass {
    FOREX, CRYPTO, STOCKS, INDICES, METALS, ENERGY, COMMODITIES
}

/** Watchlist ranking categories. */
enum class WatchlistCategory {
    BEST_BUY, BEST_SELL, BEST_SWING, BEST_SCALP, BEST_LONG_TERM
}

/** Scanner risk regime for a result. */
enum class ScannerRiskLevel {
    LOW,
    MODERATE,
    HIGH,
}

/** A symbol on the screener watchlist. */
data class ScreenerSymbol(
    val symbol: String,
    val assetClass: AssetClass,
    val name: String = "",
    val enabled: Boolean = true,
)

/** Result of scanning a single symbol. */
data class ScreenerResult(
    val symbol: String,
    val assetClass: AssetClass,
    val strategy: StrategyType,
    val direction: Direction,
    val score: Int,              // 0-100
    val bias: Bias,
    val trendStrength: Double,
    val momentum: Double,
    val volatility: Double,
    val setupQuality: Double,
    val categories: List<WatchlistCategory>,
    val tags: List<String>,
    val lastPrice: Double,
    val changePercent: Double,
    val riskLevel: ScannerRiskLevel = ScannerRiskLevel.MODERATE,
    val rationale: String = "",
)

/** Aggregate screener output. */
data class ScreenerOutput(
    val results: List<ScreenerResult>,
    val bestBuy: ScreenerResult?,
    val bestSell: ScreenerResult?,
    val bestSwing: ScreenerResult?,
    val bestScalp: ScreenerResult?,
    val bestLongTerm: ScreenerResult?,
    val scannedAt: Long,
    val totalSymbols: Int,
    /** Validated LIT X signals returned separately for provenance-gated persistence. */
    val validatedLitXSignals: List<LitXSignal> = emptyList(),
)
