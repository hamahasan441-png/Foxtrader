package com.foxtrader.app.data.remote.api

/**
 * Shared base/quote splitter for exchanges that address instruments as
 * `BASE-QUOTE` (KuCoin, OKX) rather than as a concatenated pair.
 *
 * This logic was previously duplicated verbatim in `KuCoinDataSource` and
 * `OkxDataSource`, which meant a defect had to be found and fixed twice. Two
 * defects were in fact present in both copies:
 *
 * 1. **Quote assets whose name ends in another quote asset were mis-split.**
 *    The candidate list was scanned in declaration order, and `USD` appeared
 *    before the stablecoins that end in it. `BTCBUSD` therefore matched `USD`
 *    and produced `BTCB-USD` — a real, tradable, *different* instrument on some
 *    venues, and a 404 on others. `BTCTUSD` produced `BTCT-USD` and `BTCFDUSD`
 *    produced `BTCFD-USD` the same way. Candidates are now matched
 *    longest-first, which makes the result independent of declaration order.
 *
 * 2. **Common fiat and exchange quotes were missing entirely.** `BTCTRY`,
 *    `ETHBRL`, `BNBJPY` and similar matched nothing, fell through unchanged,
 *    and were rejected by the venue — the symbol simply returned no data with
 *    no diagnostic.
 *
 * An already-delimited symbol (`BTC-USDT`, `BTC/USDT`) is passed through, so
 * callers may hand over either form.
 */
internal object CryptoSymbolNormalizer {

    /**
     * Known quote assets, matched longest-first.
     *
     * [sortedByDescending] fixes precedence *across* lengths, which is what
     * defect (1) needed: no 3-character candidate can ever pre-empt a
     * 4- or 5-character one. Within a single length the declaration order still
     * decides, but two same-length quotes cannot both suffix-match the same
     * symbol, so that case does not arise.
     */
    private val QUOTE_ASSETS: List<String> = listOf(
        // Stablecoins. FDUSD/TUSD/BUSD/USDC must beat a bare USD match.
        "FDUSD", "TUSD", "BUSD", "USDD", "USDT", "USDC", "USDE", "DAI", "PYUSD", "USD",
        // Crypto quotes.
        "BTC", "ETH", "BNB", "SOL", "TRX",
        // Fiat quotes.
        "EUR", "GBP", "JPY", "TRY", "BRL", "AUD", "CAD", "CHF", "KRW", "IDR", "INR",
    ).sortedByDescending { it.length }

    /**
     * Returns `BASE-QUOTE` when the pair can be split with confidence, otherwise
     * the uppercased input unchanged.
     *
     * Failing closed matters here: emitting a *guessed* split would send a
     * plausible-looking but wrong instrument to the venue, which is worse than
     * an honest miss because the resulting candles would be silently attributed
     * to the symbol the user asked for.
     */
    fun toDashPair(symbol: String): String {
        val upper = symbol.uppercase().replace('/', '-').trim()
        if (upper.isEmpty()) return upper
        if (upper.contains('-')) return upper

        val quote = QUOTE_ASSETS.firstOrNull { candidate ->
            upper.length > candidate.length && upper.endsWith(candidate)
        } ?: return upper

        return upper.dropLast(quote.length) + "-" + quote
    }

    /** Concatenated form (`BTCUSDT`) for venues that do not use a delimiter. */
    fun toConcatenatedPair(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").trim()
}
