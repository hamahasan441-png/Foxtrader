package com.foxtrader.app.domain.model

/**
 * A user-defined watchlist.
 *
 * Replaces the hardcoded `DEFAULT_SYMBOLS` list that the chart and backtest lab
 * previously compiled in — users could not add, remove or reorder instruments.
 */
data class Watchlist(
    val id: String,
    val name: String,
    val symbols: List<WatchlistSymbol> = emptyList(),
    /** The default list cannot be deleted, so the app always has one. */
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
) {
    val symbolNames: List<String> get() = symbols.map { it.symbol }
    val size: Int get() = symbols.size
}

data class WatchlistSymbol(
    val symbol: String,
    val assetClass: AssetClass,
    val notes: String = "",
    val addedAt: Long = 0L,
)

/**
 * Best-effort asset-class inference from a symbol's ticker.
 *
 * Used when adding a symbol from the chart or scanner, where the user has not
 * told us what kind of instrument it is. Order matters: the crypto quote-suffix
 * check must run before the forex pair check, or `BTCUSD` would be read as FX.
 */
object AssetClassifier {

    private val METALS = setOf("XAU", "XAG", "XPT", "XPD")
    private val ENERGY = setOf("WTI", "BRENT", "NGAS", "XTI", "XBR")
    private val INDICES = setOf(
        "US30", "US500", "NAS100", "SPX500", "GER40", "UK100",
        "JP225", "AUS200", "HK50", "FRA40", "EU50",
    )
    private val CRYPTO_BASES = setOf(
        "BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "AVAX",
        "MATIC", "DOT", "LINK", "LTC", "TRX", "SHIB",
    )
    private val CRYPTO_QUOTES = listOf("USDT", "USDC", "BUSD", "PERP")
    private val FIAT = setOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF",
        "SEK", "NOK", "MXN", "ZAR", "TRY", "SGD", "HKD", "CNH",
    )

    fun classify(rawSymbol: String): AssetClass {
        val symbol = rawSymbol.trim().uppercase()
        if (symbol.isEmpty()) return AssetClass.STOCKS

        if (symbol in INDICES) return AssetClass.INDICES
        if (ENERGY.any { symbol.startsWith(it) }) return AssetClass.ENERGY
        if (METALS.any { symbol.startsWith(it) }) return AssetClass.METALS

        // Crypto first: BTCUSD would otherwise match the 6-char FX shape below.
        if (CRYPTO_QUOTES.any { symbol.endsWith(it) }) return AssetClass.CRYPTO
        if (CRYPTO_BASES.any { symbol.startsWith(it) }) return AssetClass.CRYPTO

        if (symbol.length == 6 && symbol.take(3) in FIAT && symbol.drop(3) in FIAT) {
            return AssetClass.FOREX
        }
        return AssetClass.STOCKS
    }
}
