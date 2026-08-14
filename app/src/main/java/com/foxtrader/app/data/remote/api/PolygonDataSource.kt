package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import kotlin.math.max

/**
 * Polygon.io aggregate-bar adapter.
 *
 * This is the next client-side provider in FoxTrader that covers both
 * non-crypto instruments and intraday history without relying on the optional
 * FoxTrader backend. Provider credentials are passed only to the Retrofit call;
 * the Polygon Retrofit client deliberately has no HTTP logging interceptor so an
 * API key cannot appear in a debug request URL.
 *
 * The adapter owns Polygon-specific ticker and timeframe translation. The
 * repository and domain layers continue to deal only in FoxTrader symbols,
 * [Timeframe], and immutable [Candle] values.
 */
class PolygonDataSource @Inject constructor(
    private val api: PolygonApi,
) {

    /**
     * Fetch the newest [limit] bars ending at [endTimestamp].
     *
     * [endTimestamp] is injectable as a method argument rather than a
     * constructor dependency so the production Hilt graph stays simple while
     * tests can use a deterministic clock.
     */
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = DEFAULT_LIMIT,
        apiKey: String,
        endTimestamp: Long = System.currentTimeMillis(),
    ): List<Candle> {
        require(apiKey.isNotBlank()) { "Polygon API key must not be blank." }
        require(endTimestamp > 0L) { "Polygon end timestamp must be positive." }
        val safeLimit = limit.coerceIn(1, MAX_REQUEST_LIMIT)
        val range = rangeFor(timeframe, safeLimit, endTimestamp)
        return request(
            symbol = symbol,
            timeframe = timeframe,
            limit = safeLimit,
            apiKey = apiKey,
            fromTimestamp = range.first,
            toTimestamp = range.last,
        ).takeLast(safeLimit)
    }

    /**
     * Fetch a page strictly before [beforeTimestamp] for chart history paging.
     * Polygon's aggregate endpoint is inclusive at both ends, therefore the
     * upper bound is one millisecond before the requested candle boundary.
     */
    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = DEFAULT_LIMIT,
        apiKey: String,
    ): List<Candle> {
        require(apiKey.isNotBlank()) { "Polygon API key must not be blank." }
        require(beforeTimestamp > 0L) { "Polygon paging timestamp must be positive." }
        val safeLimit = limit.coerceIn(1, MAX_REQUEST_LIMIT)
        val toTimestamp = beforeTimestamp - 1L
        val range = rangeFor(timeframe, safeLimit, toTimestamp)
        return request(
            symbol = symbol,
            timeframe = timeframe,
            limit = safeLimit,
            apiKey = apiKey,
            fromTimestamp = range.first,
            toTimestamp = toTimestamp,
        ).filter { it.timestamp < beforeTimestamp }.takeLast(safeLimit)
    }

    private suspend fun request(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        apiKey: String,
        fromTimestamp: Long,
        toTimestamp: Long,
    ): List<Candle> {
        val aggregation = aggregationFor(timeframe)
        val response = api.aggregateBars(
            ticker = normalizeTicker(symbol),
            multiplier = aggregation.multiplier,
            timespan = aggregation.timespan,
            from = fromTimestamp,
            to = toTimestamp,
            // `adjusted` applies to equities; Polygon ignores it for feeds where
            // adjustment is not applicable. Ascending order makes prepend paging
            // and the Room mapper deterministic.
            adjusted = true,
            sort = "asc",
            // The calendar range is buffered, so request enough rows to retain
            // the newest `limit` bars after weekends and exchange closures.
            limit = requestLimit(limit),
            apiKey = apiKey,
        )
        return parseResponse(response)
    }

    private fun parseResponse(response: JsonElement): List<Candle> {
        val root = response.jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (status.equals("ERROR", ignoreCase = true)) {
            val message = root["error"]?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: "Unknown Polygon error"
            throw IllegalStateException("Polygon: $message")
        }

        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val timestamp = item["t"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val open = item["o"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val high = item["h"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val low = item["l"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val close = item["c"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (!listOf(open, high, low, close).all { it.isFinite() }) return@mapNotNull null
            val volume = item["v"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0
            Candle(
                timestamp = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
            )
        }.sortedBy { it.timestamp }
    }

    private fun requestLimit(limit: Int): Int =
        (limit.toLong() * RANGE_BUFFER_MULTIPLIER)
            .coerceAtMost(MAX_REQUEST_LIMIT.toLong())
            .toInt()

    private fun rangeFor(timeframe: Timeframe, limit: Int, endTimestamp: Long): LongRange {
        val intervalMs = aggregationFor(timeframe).durationMs
        // A wider request accounts for weekends, exchange holidays, and partial
        // sessions while the final takeLast() still honours the caller's limit.
        val lookbackMs = intervalMs * max(limit.toLong(), 1L) * RANGE_BUFFER_MULTIPLIER
        val from = (endTimestamp - lookbackMs).coerceAtLeast(1L)
        return from..endTimestamp
    }

    /** Translate FoxTrader symbols into Polygon asset-prefixed tickers. */
    internal fun normalizeTicker(symbol: String): String {
        val compact = symbol.trim().uppercase().replace("/", "")
        require(compact.isNotEmpty()) { "Polygon symbol must not be blank." }
        if (compact.startsWith("C:") || compact.startsWith("X:") || compact.startsWith("I:")) {
            return compact
        }

        INDEX_TICKERS[compact]?.let { return "I:$it" }
        if (isForexPair(compact)) return "C:$compact"

        val cryptoQuote = CRYPTO_QUOTES.firstOrNull { compact.endsWith(it) && compact.length > it.length }
        if (cryptoQuote != null) {
            val base = compact.removeSuffix(cryptoQuote)
            val quote = if (cryptoQuote == "USDT" || cryptoQuote == "BUSD") "USD" else cryptoQuote
            return "X:$base$quote"
        }
        return compact
    }

    private fun isForexPair(symbol: String): Boolean =
        symbol.length == 6 &&
            COMMON_CURRENCIES.contains(symbol.substring(0, 3)) &&
            COMMON_CURRENCIES.contains(symbol.substring(3))

    private data class Aggregation(
        val multiplier: Int,
        val timespan: String,
        val durationMs: Long,
    )

    private fun aggregationFor(timeframe: Timeframe): Aggregation = when (timeframe) {
        Timeframe.M1 -> Aggregation(1, "minute", 60_000L)
        Timeframe.M5 -> Aggregation(5, "minute", 5 * 60_000L)
        Timeframe.M15 -> Aggregation(15, "minute", 15 * 60_000L)
        Timeframe.M30 -> Aggregation(30, "minute", 30 * 60_000L)
        Timeframe.H1 -> Aggregation(1, "hour", 60 * 60_000L)
        Timeframe.H4 -> Aggregation(4, "hour", 4 * 60 * 60_000L)
        Timeframe.D1 -> Aggregation(1, "day", 24 * 60 * 60_000L)
        Timeframe.W1 -> Aggregation(1, "week", 7 * 24 * 60 * 60_000L)
        // Polygon supports month aggregates; 31 days is a safe range estimate,
        // and the response itself remains the source of truth for bar count.
        Timeframe.MN -> Aggregation(1, "month", 31 * 24 * 60 * 60_000L)
    }

    private companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_REQUEST_LIMIT = 50_000
        const val RANGE_BUFFER_MULTIPLIER = 3L

        val COMMON_CURRENCIES = setOf(
            "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD",
            "SEK", "NOK", "DKK", "SGD", "HKD", "ZAR", "MXN", "TRY",
        )
        val CRYPTO_QUOTES = listOf("USDT", "USDC", "BUSD", "USD", "EUR", "BTC", "ETH")
        val INDEX_TICKERS = mapOf(
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
}
