package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.di.PublicMarketDataClient
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Production-grade Dukascopy market data source.
 *
 * Downloads raw tick data archives from Dukascopy datafeed servers, decompresses
 * the LZMA `.bi5` payload, decodes binary 20-byte records, and aggregates
 * ticks into non-repainting OHLC candles.
 *
 * Datafeed URL format:
 * `https://datafeed.dukascopy.com/datafeed/{SYMBOL}/{YEAR}/{MONTH_0_INDEXED:02d}/{DAY:02d}/{HOUR:02d}h_ticks.bi5`
 *
 * Features:
 * - Pure Kotlin LZMA decompression + binary decoding
 * - Correct instrument point-value scaling (5-digit FX vs JPY vs Metals)
 * - Automatic UTC date/time alignment with 0-indexed month handling
 * - Weekend / holiday gap skipping (404 / empty file handling)
 * - Multi-hour tick gathering and timeframe aggregation
 * - Non-repainting tick-to-OHLC construction via [TickAggregator]
 */
@Singleton
class DukascopyDataSource @Inject constructor(
    @PublicMarketDataClient private val httpClient: OkHttpClient,
    private val tickDecoder: DukascopyTickDecoder,
    private val lzmaDecompressor: LzmaDecompressor,
    private val tickAggregator: TickAggregator,
) {

    /**
     * Fetch historical candles for [symbol] and [timeframe].
     */
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
    ): List<Candle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        fetchCandlesBefore(symbol, timeframe, beforeTimestamp = now, limit = limit)
    }

    /**
     * Fetch historical candles for [symbol] and [timeframe] strictly before [beforeTimestamp].
     */
    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
    ): List<Candle> = withContext(Dispatchers.IO) {
        val normSymbol = normalizeSymbol(symbol)
        val pointValue = getPointValue(normSymbol)
        val safeLimit = limit.coerceIn(1, 2000)

        val allTicks = mutableListOf<Tick>()
        var currentHourEpochMs = floorToHour(beforeTimestamp)

        val hoursNeededEstimate = max(2, calculateHoursNeeded(timeframe, safeLimit))
        // A tiny request (including Settings -> Test connection) used to search
        // only 6-12 hours backwards. On Saturday/Sunday that never reached the
        // last Friday session, so a healthy Dukascopy connection was reported as
        // "connected but returned no candle data". Keep a four-day minimum
        // search horizon for small requests while retaining the existing scaled
        // horizon for large history loads.
        val maxHourWalk = max(hoursNeededEstimate * 3, MIN_MARKET_GAP_LOOKBACK_HOURS)

        var hoursWalked = 0
        var emptyConsecutiveHours = 0

        // The old implementation fetched one .bi5 hour at a time. Loading 500
        // M15 bars therefore required ~125 serial HTTPS round-trips and could
        // easily hit the app/network timeout, which then caused the chart to
        // fall back to SIMULATED DATA. Fetch small bounded batches in parallel
        // while preserving chronological aggregation after each batch.
        val batchHours = if (safeLimit <= LIVE_POLL_LIMIT_THRESHOLD) {
            LIVE_POLL_BATCH_HOURS
        } else {
            FETCH_BATCH_HOURS
        }
        while (hoursWalked < maxHourWalk && emptyConsecutiveHours < MAX_EMPTY_HOURS) {
            val batchSize = minOf(batchHours, maxHourWalk - hoursWalked)
            val hourStarts = List(batchSize) { offset ->
                currentHourEpochMs - (offset * ONE_HOUR_MS)
            }
            val batchTicks = coroutineScope {
                hourStarts.map { hourStart ->
                    async {
                        val zdt = Instant.ofEpochMilli(hourStart).atZone(ZoneOffset.UTC)
                        fetchHourTicks(
                            symbol = normSymbol,
                            year = zdt.year,
                            month0 = zdt.monthValue - 1,
                            day = zdt.dayOfMonth,
                            hour = zdt.hour,
                            hourStartMs = hourStart,
                            pointValue = pointValue,
                        )
                    }
                }.awaitAll()
            }

            // Batches are requested newest -> oldest. TickAggregator sorts its
            // input, but sorting once here keeps every downstream consumer on a
            // deterministic ascending stream and avoids duplicate hour seams.
            val nonEmpty = batchTicks.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) {
                emptyConsecutiveHours += batchSize
            } else {
                emptyConsecutiveHours = 0
                nonEmpty.forEach(allTicks::addAll)
                allTicks.sortBy { it.timestampMs }

                val aggregated = tickAggregator.aggregate(allTicks, timeframe)
                if (aggregated.size >= safeLimit + 1) break
            }

            hoursWalked += batchSize
            currentHourEpochMs -= batchSize * ONE_HOUR_MS
        }

        val candles = tickAggregator.aggregate(allTicks, timeframe)
        candles
            .filter { it.timestamp < beforeTimestamp }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .takeLast(safeLimit)
    }

    /**
     * Download and decode raw ticks for a specific hour.
     */
    suspend fun fetchHourTicks(
        symbol: String,
        year: Int,
        month0: Int,
        day: Int,
        hour: Int,
        hourStartMs: Long,
        pointValue: Double,
    ): List<Tick> = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "%s/%s/%04d/%02d/%02d/%02dh_ticks.bi5",
            BASE_DATAFEED_URL,
            symbol,
            year,
            month0,
            day,
            hour,
        )

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (FoxTrader Financial Engine)")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                val rawBytes = response.body?.bytes() ?: return@withContext emptyList()
                if (rawBytes.isEmpty()) return@withContext emptyList()

                val decompressed = lzmaDecompressor.decompress(rawBytes)
                if (decompressed.isEmpty()) return@withContext emptyList()

                tickDecoder.decode(
                    decompressed = decompressed,
                    hourStartMs = hourStartMs,
                    pointValue = pointValue,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Determine instrument point divisor for Dukascopy integer pricing.
     */
    fun getPointValue(symbol: String): Double {
        val upper = symbol.uppercase()
        return when {
            upper.contains("JPY") -> 1_000.0
            upper.startsWith("XAU") || upper.startsWith("GOLD") -> 1_000.0
            upper.startsWith("XAG") || upper.startsWith("SILVER") -> 1_000.0
            upper.startsWith("XPT") || upper.startsWith("XPD") -> 1_000.0
            upper.startsWith("US30") || upper.startsWith("SPX") || upper.startsWith("NAS") || upper.startsWith("GER") -> 100.0
            upper.startsWith("BTC") || upper.startsWith("ETH") -> 100.0
            else -> 100_000.0 // Standard 5-decimal FX (EURUSD, GBPUSD, etc.)
        }
    }

    /**
     * Normalize ticker string to Dukascopy uppercase format (e.g. "EUR/USD" -> "EURUSD").
     */
    fun normalizeSymbol(symbol: String): String {
        return symbol.trim().uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun floorToHour(timestampMs: Long): Long {
        return Math.floorDiv(timestampMs, ONE_HOUR_MS) * ONE_HOUR_MS
    }

    private fun calculateHoursNeeded(timeframe: Timeframe, limit: Int): Int {
        return when (timeframe) {
            Timeframe.M1 -> ceil(limit / 60.0).toInt() + 1
            Timeframe.M5 -> ceil(limit / 12.0).toInt() + 1
            Timeframe.M15 -> ceil(limit / 4.0).toInt() + 1
            Timeframe.M30 -> ceil(limit / 2.0).toInt() + 1
            Timeframe.H1 -> limit + 1
            Timeframe.H4 -> limit * 4 + 1
            Timeframe.D1 -> limit * 24 + 1
            Timeframe.W1 -> limit * 168 + 1
            Timeframe.MN -> limit * 720 + 1
        }
    }

    companion object {
        const val BASE_DATAFEED_URL = "https://datafeed.dukascopy.com/datafeed"
        const val ONE_HOUR_MS = 3_600_000L
        private const val FETCH_BATCH_HOURS = 8
        private const val LIVE_POLL_BATCH_HOURS = 2
        private const val LIVE_POLL_LIMIT_THRESHOLD = 5
        private const val MIN_MARKET_GAP_LOOKBACK_HOURS = 96
        private const val MAX_EMPTY_HOURS = MIN_MARKET_GAP_LOOKBACK_HOURS
    }
}
