package com.foxtrader.app.domain.usecase.marketdata

/** Broad market family used to route symbols to compatible public feeds. */
enum class MarketAssetClass {
    CRYPTO,
    FOREX,
    METAL,
    INDEX,
    STOCK,
    UNKNOWN,
}

/** Pure symbol normalizer/classifier shared by routing and unit tests. */
object MarketSymbolClassifier {
    fun canonicalSymbol(symbol: String): String = symbol.trim().uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")

    fun classify(symbol: String): MarketAssetClass {
        val s = canonicalSymbol(symbol)
        if (s.isBlank()) return MarketAssetClass.UNKNOWN
        if (METAL_PREFIXES.any { s.startsWith(it) }) return MarketAssetClass.METAL
        if (INDEX_SYMBOLS.any { s == it || s.startsWith(it) }) return MarketAssetClass.INDEX
        if (isCrypto(s)) return MarketAssetClass.CRYPTO
        if (s.length == 6 && s.take(3) in FIAT_CODES && s.takeLast(3) in FIAT_CODES) {
            return MarketAssetClass.FOREX
        }
        if (s.length in 1..6 && s.all { it.isLetter() }) return MarketAssetClass.STOCK
        return MarketAssetClass.UNKNOWN
    }

    private fun isCrypto(symbol: String): Boolean {
        if (CRYPTO_QUOTES.any { symbol.endsWith(it) }) return true
        return CRYPTO_BASES.any { base ->
            symbol.startsWith(base) && (symbol.endsWith("USD") || symbol.endsWith("EUR"))
        }
    }

    private val CRYPTO_QUOTES = setOf("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "DAI", "BTC", "ETH", "BNB")
    private val CRYPTO_BASES = setOf("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "BNB", "LTC", "AVAX", "DOT", "LINK")
    private val METAL_PREFIXES = setOf("XAU", "XAG", "XPT", "XPD", "GOLD", "SILVER")
    private val INDEX_SYMBOLS = setOf(
        "US30", "DJI", "DJ30", "SPX", "SP500", "US500", "NAS100", "NDX", "USTEC",
        "GER40", "DE40", "DAX", "UK100", "FTSE", "JP225", "NIKKEI", "AUS200",
    )
    private val FIAT_CODES = setOf(
        "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK",
        "DKK", "PLN", "CZK", "HUF", "TRY", "ZAR", "MXN", "SGD", "HKD", "CNH",
    )
}
