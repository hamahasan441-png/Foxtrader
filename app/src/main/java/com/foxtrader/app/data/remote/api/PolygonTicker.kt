package com.foxtrader.app.data.remote.api

/** Polygon market clusters supported by the aggregate REST and WebSocket APIs. */
internal enum class PolygonMarket {
    STOCKS,
    FOREX,
    CRYPTO,
    INDICES,
}

/**
 * Canonical Polygon ticker translation shared by REST and WebSocket paths.
 *
 * FoxTrader keeps user-facing symbols compact (`EURUSD`, `BTCUSDT`, `US500`),
 * while Polygon uses asset-class prefixes and pair separators. Keeping this in
 * one seam prevents the historical and live paths from silently subscribing to
 * different instruments.
 */
internal object PolygonTicker {

    fun normalize(symbol: String): String {
        val compact = symbol.trim().uppercase().replace("/", "").replace("-", "")
        require(compact.isNotEmpty()) { "Polygon symbol must not be blank." }
        if (compact.startsWith("C:") || compact.startsWith("I:")) return compact
        if (compact.startsWith("X:")) return normalize(compact.removePrefix("X:"))

        INDEX_TICKERS[compact]?.let { return "I:$it" }
        if (isForexPair(compact)) return "C:$compact"

        val cryptoQuote = CRYPTO_QUOTES.firstOrNull {
            compact.endsWith(it) && compact.length > it.length
        }
        if (cryptoQuote != null) {
            val base = compact.removeSuffix(cryptoQuote)
            val quote = if (cryptoQuote == "USDT" || cryptoQuote == "USDC" || cryptoQuote == "BUSD") {
                "USD"
            } else {
                cryptoQuote
            }
            return "X:$base$quote"
        }
        return compact
    }

    fun market(ticker: String): PolygonMarket = when {
        ticker.startsWith("C:") -> PolygonMarket.FOREX
        ticker.startsWith("X:") -> PolygonMarket.CRYPTO
        ticker.startsWith("I:") -> PolygonMarket.INDICES
        else -> PolygonMarket.STOCKS
    }

    /** Convert a canonical ticker to the pair format used by Polygon WS topics. */
    fun subscriptionSymbol(ticker: String, market: PolygonMarket): String = when (market) {
        PolygonMarket.FOREX -> ticker.removePrefix("C:").let { "${it.take(3)}/${it.takeLast(3)}" }
        PolygonMarket.CRYPTO -> {
            val pair = ticker.removePrefix("X:")
            val quote = CRYPTO_QUOTES.firstOrNull { pair.endsWith(it) } ?: "USD"
            "${pair.removeSuffix(quote)}-${if (quote == "USDT" || quote == "USDC" || quote == "BUSD") "USD" else quote}"
        }
        PolygonMarket.INDICES, PolygonMarket.STOCKS -> ticker
    }

    private fun isForexPair(symbol: String): Boolean =
        symbol.length == 6 &&
            COMMON_CURRENCIES.contains(symbol.substring(0, 3)) &&
            COMMON_CURRENCIES.contains(symbol.substring(3))

    private val COMMON_CURRENCIES = setOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD",
        "SEK", "NOK", "DKK", "SGD", "HKD", "ZAR", "MXN", "TRY",
    )
    private val CRYPTO_QUOTES = listOf("USDT", "USDC", "BUSD", "USD", "EUR", "BTC", "ETH")
    private val INDEX_TICKERS = mapOf(
        "US500" to "SPX",
        "SPX" to "SPX",
        "NAS100" to "NDX",
        "NDX" to "NDX",
        "US30" to "DJI",
        "DJI" to "DJI",
        "GER40" to "DAX",
        "DAX" to "DAX",
    )
}
