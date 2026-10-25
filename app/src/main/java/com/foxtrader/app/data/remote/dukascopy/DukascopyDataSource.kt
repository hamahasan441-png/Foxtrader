package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.di.PublicMarketDataClient
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Free Dukascopy historical and near-real-time market data.
 *
 * Live history is the jetta JSON candle service. Since October 2026 that host
 * answers a plain HTTP client with an empty AWS WAF challenge, and the legacy
 * `datafeed.dukascopy.com` `.bi5` archive accepts the TCP connection and then
 * never sends a byte. The chart therefore has to reuse a browser-minted
 * `aws-waf-token`. The archive is only a short, fail-fast fallback.
 */
@Singleton
class DukascopyDataSource @Inject constructor(
    @PublicMarketDataClient private val httpClient: OkHttpClient,
    private val tickDecoder: DukascopyTickDecoder,
    private val lzmaDecompressor: LzmaDecompressor,
    private val tickAggregator: TickAggregator,
    private val candleDecoder: DukascopyCandleDecoder,
    private val bi5CandleDecoder: DukascopyBi5CandleDecoder,
    private val instrumentCatalog: DukascopyInstrumentCatalog,
    private val wafSession: DukascopyWafSession = DukascopyWafSession.None,
) {
    private enum class CandleSource(val path: String, val approximateBucketMs: Long) {
        MINUTE("minute", ONE_DAY_MS),
        HOUR("hour", 30L * ONE_DAY_MS),
        DAY("day", 365L * ONE_DAY_MS),
    }

    private data class CandleRequest(val url: String, val cacheable: Boolean)

    /**
     * LRU cache bounded by the number of *rows* it retains, not by entry count.
     *
     * `MEMORY` Bucket payloads here are wildly uneven: one completed minute
     * bucket is ~1 440 candles, while one completed `.bi5` hour of a major FX
     * pair is routinely 20 000–100 000 ticks. Capping only the entry count let
     * this singleton retain millions of objects (hundreds of MB) for the whole
     * process lifetime, which is what pushed the app into `OutOfMemoryError`
     * after a few provider/timeframe switches. Bounding by total rows keeps the
     * worst case flat regardless of how dense the requested history is.
     */
    private class RowBoundedCache<T>(
        private val maxRows: Int,
        private val rowsOf: (List<T>) -> Int = { it.size },
    ) {
        private val entries = LinkedHashMap<String, List<T>>(64, 0.75f, true)
        private var rows = 0

        operator fun get(key: String): List<T>? = entries[key]

        operator fun set(key: String, value: List<T>) {
            val weight = rowsOf(value)
            // A single payload larger than the whole budget is never worth
            // retaining: caching it would evict everything else and still be
            // dropped on the next insert.
            if (weight > maxRows) {
                entries.remove(key)?.let { rows -= rowsOf(it) }
                return
            }
            entries.put(key, value)?.let { rows -= rowsOf(it) }
            rows += weight
            val iterator = entries.entries.iterator()
            while (rows > maxRows && iterator.hasNext()) {
                val eldest = iterator.next()
                rows -= rowsOf(eldest.value)
                iterator.remove()
            }
        }

        fun clear() {
            entries.clear()
            rows = 0
        }
    }

    private val cacheLock = Any()
    private val completedCandleCache = RowBoundedCache<Candle>(MAX_CACHED_CANDLE_ROWS)
    private val completedTickCache = RowBoundedCache<Tick>(MAX_CACHED_TICK_ROWS)

    /**
     * `datafeed.dukascopy.com` has not returned a body since mid-2026. One
     * timed-out probe is enough; walking hundreds of silent hours is what
     * made the chart spin forever.
     */
    private val archiveDead = AtomicBoolean(false)
    /** Set when jetta answers with a WAF challenge. The archive host is dead, so don't walk it. */
    private val jettaBlocked = AtomicBoolean(false)
    private val archiveClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(ARCHIVE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(ARCHIVE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(ARCHIVE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /** Drop every cached bucket. Called when the process is under memory pressure. */
    fun clearCaches() {
        synchronized(cacheLock) {
            completedCandleCache.clear()
            completedTickCache.clear()
        }
    }

    fun discoverSymbols(): List<ProviderMarketSymbol> = instrumentCatalog.discoverSymbols()

    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
    ): List<Candle> = fetchCandlesBefore(symbol, timeframe, System.currentTimeMillis(), limit)

    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
    ): List<Candle> = withContext(Dispatchers.IO) {
        require(beforeTimestamp > 0L) { "Dukascopy history boundary must be positive." }
        val instrument = instrumentCatalog.require(symbol)
        val safeLimit = limit.coerceIn(1, MAX_CANDLE_LIMIT)

        val primary = try {
            fetchCompactCandles(instrument, timeframe, beforeTimestamp, safeLimit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        if (primary.isNotEmpty()) return@withContext primary
        // A challenge means the browser token was missing or rejected. The
        // .bi5 host will not answer either; falling through to it is a hang.
        if (jettaBlocked.get()) return@withContext emptyList()

        val native = try {
            fetchNativeBi5Candles(instrument, timeframe, beforeTimestamp, safeLimit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        if (native.isNotEmpty()) return@withContext native

        fetchBi5CandlesBefore(instrument, timeframe, beforeTimestamp, safeLimit)
    }

    private suspend fun fetchCompactCandles(
        instrument: DukascopyInstrument,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): List<Candle> {
        val source = sourceFor(timeframe)
        val requests = buildCandleRequests(instrument, source, timeframe, beforeTimestamp, limit)
        val durationMs = timeframe.minutes.toLong() * 60_000L
        val all = ArrayList<Candle>()

        // `PERF` The completion test tracks distinct *target* buckets in a hash
        // set updated as rows arrive. The previous implementation re-ran the
        // full filter/distinct/sort/group aggregation after every batch over a
        // list that grows past 100 000 rows on a minute-sourced request — an
        // O(batches · n log n) allocation storm that dominated chart load time
        // and shredded the young generation while the user waited.
        val bucketKeys = HashSet<Long>(limit * 2)

        for (batch in requests.chunked(CANDLE_FETCH_CONCURRENCY)) {
            val fetched = coroutineScope {
                batch.map { request -> async { fetchCandleBucket(request) } }.awaitAll()
            }
            for (bucket in fetched) {
                for (candle in bucket) {
                    if (candle.timestamp >= beforeTimestamp) continue
                    all += candle
                    bucketKeys += Math.floorDiv(candle.timestamp, durationMs)
                }
            }
            if (bucketKeys.size >= limit) break
        }

        return aggregateCandles(all, timeframe, beforeTimestamp).takeLast(limit)
    }

    private fun buildCandleRequests(
        instrument: DukascopyInstrument,
        source: CandleSource,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): List<CandleRequest> {
        val targetSpanMs = timeframe.minutes.toLong() * 60_000L * limit
        val estimated = ceil(targetSpanMs * MARKET_GAP_SEARCH_FACTOR / source.approximateBucketMs)
            .toInt()
            .plus(2)
            .coerceIn(2, MAX_CANDLE_BUCKET_REQUESTS)
        val now = System.currentTimeMillis()
        var bucket = bucketStart(Instant.ofEpochMilli(beforeTimestamp - 1L).atZone(ZoneOffset.UTC), source)

        return List(estimated) {
            val next = nextBucket(bucket, source)
            val active = now >= bucket.toInstant().toEpochMilli() && now < next.toInstant().toEpochMilli()
            val base = "$CANDLE_API_URL/candles/${source.path}/${instrument.apiCode}/BID"
            val url = if (active) {
                "$base?from=${bucket.toInstant().toEpochMilli()}"
            } else {
                val suffix = when (source) {
                    CandleSource.MINUTE -> "${bucket.year}/${bucket.monthValue}/${bucket.dayOfMonth}"
                    CandleSource.HOUR -> "${bucket.year}/${bucket.monthValue}"
                    CandleSource.DAY -> "${bucket.year}"
                }
                "$base/$suffix"
            }
            bucket = previousBucket(bucket, source)
            CandleRequest(url = url, cacheable = !active)
        }
    }

    private suspend fun fetchCandleBucket(request: CandleRequest): List<Candle> {
        if (request.cacheable) {
            synchronized(cacheLock) { completedCandleCache[request.url] }?.let { return it }
        }

        var lastError: Exception? = null
        var refreshedToken = false
        repeat(HTTP_ATTEMPTS) { attempt ->
            try {
                val token = wafSession.token()
                val httpRequest = Request.Builder()
                    .url(request.url)
                    .header("User-Agent", wafSession.userAgent ?: USER_AGENT)
                    .header("Accept", "application/json,text/plain,*/*")
                    .apply {
                        if (!token.isNullOrBlank()) header("Cookie", "$WAF_COOKIE_PREFIX$token")
                    }
                    .get()
                    .build()
                val decoded = httpClient.newCall(httpRequest).execute().use { response ->
                    val challenged = !response.header("x-amzn-waf-action").isNullOrBlank()
                    val payload = response.body?.string().orEmpty()
                    when (classifyDukascopyResponse(response.code, payload.isBlank(), challenged)) {
                        DukascopyHttpOutcome.ABSENT -> emptyList()
                        DukascopyHttpOutcome.RETRY -> {
                            if (challenged) {
                                jettaBlocked.set(true)
                                if (!refreshedToken) {
                                    refreshedToken = true
                                    wafSession.invalidate(token)
                                }
                            }
                            throw IllegalStateException("Dukascopy candle HTTP ${response.code} was not usable.")
                        }
                        DukascopyHttpOutcome.DATA -> {
                            jettaBlocked.set(false)
                            require(payload.length <= MAX_CANDLE_RESPONSE_CHARS) {
                                "Dukascopy candle response exceeds the safety limit."
                            }
                            candleDecoder.decode(payload)
                        }
                    }
                }
                if (request.cacheable) synchronized(cacheLock) {
                    completedCandleCache[request.url] = decoded
                }
                return decoded
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                lastError = error
                if (attempt + 1 < HTTP_ATTEMPTS) delay(RETRY_BASE_DELAY_MS * (1L shl attempt))
            }
        }
        throw lastError ?: IllegalStateException("Dukascopy candle request failed.")
    }

    private fun aggregateCandles(
        candles: List<Candle>,
        timeframe: Timeframe,
        beforeTimestamp: Long,
    ): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val durationMs = timeframe.minutes.toLong() * 60_000L

        // `PERF` One dedup pass, one sort, one fold. The previous sequence
        // chain (`distinctBy → sortedBy → groupBy → map → sortedBy`) built a
        // hash set, two full lists, a map of per-bucket sublists and then
        // sorted an already-ordered result — several times the allocation for
        // the same output.
        val deduplicated = HashMap<Long, Candle>(candles.size * 2)
        for (candle in candles) {
            if (candle.timestamp >= beforeTimestamp) continue
            // Overlapping buckets repeat edge rows; keep the first observation,
            // matching the previous `distinctBy` semantics.
            if (!deduplicated.containsKey(candle.timestamp)) deduplicated[candle.timestamp] = candle
        }
        if (deduplicated.isEmpty()) return emptyList()

        val ordered = deduplicated.values.sortedBy { it.timestamp }
        val out = ArrayList<Candle>()
        var bucketStart = Long.MIN_VALUE
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0.0
        for (candle in ordered) {
            val key = Math.floorDiv(candle.timestamp, durationMs) * durationMs
            if (key != bucketStart) {
                if (bucketStart != Long.MIN_VALUE) {
                    out += Candle(bucketStart, open, high, low, close, volume)
                }
                bucketStart = key
                open = candle.open
                high = candle.high
                low = candle.low
                close = candle.close
                volume = candle.volume
            } else {
                if (candle.high > high) high = candle.high
                if (candle.low < low) low = candle.low
                close = candle.close
                volume += candle.volume
            }
        }
        if (bucketStart != Long.MIN_VALUE) out += Candle(bucketStart, open, high, low, close, volume)
        return out
    }

    private suspend fun fetchBi5CandlesBefore(
        instrument: DukascopyInstrument,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): List<Candle> {
        val allTicks = ArrayList<Tick>()
        val durationMs = timeframe.minutes.toLong() * 60_000L
        // `PERF`/`MEMORY` Distinct bar buckets are tracked as ticks arrive, so
        // the walk can stop without re-sorting and re-aggregating the whole
        // accumulated tick list on every batch. A dense FX hour is tens of
        // thousands of ticks; the previous per-batch `sortBy` + `aggregate`
        // was quadratic in batches and the unbounded accumulator could reach
        // millions of objects before the loop's own limits fired.
        val bucketKeys = HashSet<Long>()
        var currentHourEpochMs = floorToHour(beforeTimestamp)
        val hoursNeededEstimate = max(2, calculateHoursNeeded(timeframe, limit))
        val maxHourWalk = max(hoursNeededEstimate * 3, MIN_MARKET_GAP_LOOKBACK_HOURS)
            .coerceAtMost(MAX_BI5_FALLBACK_HOURS)
        var hoursWalked = 0
        var emptyConsecutiveHours = 0
        val batchHours = if (limit <= LIVE_POLL_LIMIT_THRESHOLD) LIVE_POLL_BATCH_HOURS else FETCH_BATCH_HOURS

        while (hoursWalked < maxHourWalk && emptyConsecutiveHours < MAX_EMPTY_HOURS && !archiveDead.get()) {
            val batchSize = minOf(batchHours, maxHourWalk - hoursWalked)
            val hourStarts = List(batchSize) { currentHourEpochMs - it * ONE_HOUR_MS }
            val batchTicks = coroutineScope {
                hourStarts.map { hourStart ->
                    async {
                        val zdt = Instant.ofEpochMilli(hourStart).atZone(ZoneOffset.UTC)
                        fetchHourTicks(
                            symbol = instrument.providerSymbol,
                            year = zdt.year,
                            month0 = zdt.monthValue - 1,
                            day = zdt.dayOfMonth,
                            hour = zdt.hour,
                            hourStartMs = hourStart,
                            pointValue = instrument.bi5PointValue,
                        )
                    }
                }.awaitAll()
            }
            val nonEmpty = batchTicks.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) emptyConsecutiveHours += batchSize else {
                emptyConsecutiveHours = 0
                for (hourTicks in nonEmpty) {
                    for (tick in hourTicks) {
                        allTicks += tick
                        bucketKeys += Math.floorDiv(tick.timestampMs, durationMs)
                    }
                }
                if (bucketKeys.size >= limit + 1) break
                // Hard ceiling on the working set: a handful of very dense
                // hours must not be allowed to exhaust the heap before the
                // bar-count target is reached.
                if (allTicks.size >= MAX_ACCUMULATED_TICKS) break
            }
            hoursWalked += batchSize
            currentHourEpochMs -= batchSize * ONE_HOUR_MS
        }

        return tickAggregator.aggregate(allTicks, timeframe) { it.bid }
            .filter { it.timestamp < beforeTimestamp }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    suspend fun fetchHourTicks(
        symbol: String,
        year: Int,
        month0: Int,
        day: Int,
        hour: Int,
        hourStartMs: Long,
        pointValue: Double,
    ): List<Tick> = withContext(Dispatchers.IO) {
        val nativeSymbol = normalizeSymbol(symbol)
        val cacheKey = "$nativeSymbol|$hourStartMs|$pointValue"
        val completed = hourStartMs < floorToHour(System.currentTimeMillis())
        if (completed) synchronized(cacheLock) { completedTickCache[cacheKey] }?.let { return@withContext it }

        val path = String.format(
            Locale.US,
            "%s/%04d/%02d/%02d/%02dh_ticks.bi5",
            nativeSymbol,
            year,
            month0,
            day,
            hour,
        )
        val ticks = downloadBi5(path)?.let { raw ->
            val decompressed = lzmaDecompressor.decompress(raw)
            if (decompressed.isEmpty()) emptyList() else tickDecoder.decode(decompressed, hourStartMs, pointValue)
        } ?: emptyList()
        if (completed && ticks.isNotEmpty()) synchronized(cacheLock) { completedTickCache[cacheKey] = ticks }
        ticks
    }

    /**
     * Native OHLC archives. One M1 file is a day, one H1 file is a month and
     * one D1 file is a year — orders of magnitude less traffic than walking
     * hourly tick files, and it does not depend on the WAF-blocked JSON API.
     */
    private suspend fun fetchNativeBi5Candles(
        instrument: DukascopyInstrument,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): List<Candle> {
        val source = sourceFor(timeframe)
        val requests = buildNativeCandleRequests(instrument, source, beforeTimestamp, limit)
        val durationMs = timeframe.minutes.toLong() * 60_000L
        val all = ArrayList<Candle>()
        val bucketKeys = HashSet<Long>(limit * 2)
        for (batch in requests.chunked(CANDLE_FETCH_CONCURRENCY)) {
            if (archiveDead.get()) break
            val fetched = coroutineScope {
                batch.map { request -> async { fetchNativeCandleBucket(request, instrument.bi5PointValue) } }.awaitAll()
            }
            for (bucket in fetched) {
                for (candle in bucket) {
                    if (candle.timestamp >= beforeTimestamp) continue
                    all += candle
                    bucketKeys += Math.floorDiv(candle.timestamp, durationMs)
                }
            }
            if (bucketKeys.size >= limit) break
        }
        return aggregateCandles(all, timeframe, beforeTimestamp).takeLast(limit)
    }

    private data class NativeCandleRequest(
        val path: String,
        val bucketStartMs: Long,
        val cacheable: Boolean,
    )

    private fun buildNativeCandleRequests(
        instrument: DukascopyInstrument,
        source: CandleSource,
        beforeTimestamp: Long,
        limit: Int,
    ): List<NativeCandleRequest> {
        val targetSpanMs = timeframeSpan(source, limit)
        val estimated = ceil(targetSpanMs * MARKET_GAP_SEARCH_FACTOR / source.approximateBucketMs)
            .toInt()
            .plus(2)
            .coerceIn(2, MAX_CANDLE_BUCKET_REQUESTS)
        val now = System.currentTimeMillis()
        var bucket = bucketStart(Instant.ofEpochMilli(beforeTimestamp - 1L).atZone(ZoneOffset.UTC), source)
        return List(estimated) {
            val next = nextBucket(bucket, source)
            val active = now >= bucket.toInstant().toEpochMilli() && now < next.toInstant().toEpochMilli()
            val month0 = bucket.monthValue - 1
            val path = when (source) {
                CandleSource.MINUTE -> String.format(
                    Locale.US,
                    "%s/%04d/%02d/%02d/BID_candles_min_1.bi5",
                    instrument.providerSymbol,
                    bucket.year,
                    month0,
                    bucket.dayOfMonth,
                )
                CandleSource.HOUR -> String.format(
                    Locale.US,
                    "%s/%04d/%02d/BID_candles_hour_1.bi5",
                    instrument.providerSymbol,
                    bucket.year,
                    month0,
                )
                CandleSource.DAY -> String.format(
                    Locale.US,
                    "%s/%04d/BID_candles_day_1.bi5",
                    instrument.providerSymbol,
                    bucket.year,
                )
            }
            val request = NativeCandleRequest(
                path = path,
                bucketStartMs = bucket.toInstant().toEpochMilli(),
                cacheable = !active,
            )
            bucket = previousBucket(bucket, source)
            request
        }
    }

    private fun timeframeSpan(source: CandleSource, limit: Int): Long {
        val barMs = when (source) {
            CandleSource.MINUTE -> 60_000L
            CandleSource.HOUR -> ONE_HOUR_MS
            CandleSource.DAY -> ONE_DAY_MS
        }
        return barMs * limit
    }

    private suspend fun fetchNativeCandleBucket(
        request: NativeCandleRequest,
        pointValue: Double,
    ): List<Candle> {
        val cacheKey = "native|${request.path}|$pointValue"
        if (request.cacheable) {
            synchronized(cacheLock) { completedCandleCache[cacheKey] }?.let { return it }
        }
        val raw = downloadBi5(request.path) ?: return emptyList()
        val decoded = bi5CandleDecoder.decode(
            decompressed = lzmaDecompressor.decompress(raw),
            bucketStartMs = request.bucketStartMs,
            pointValue = pointValue,
        )
        if (request.cacheable && decoded.isNotEmpty()) {
            synchronized(cacheLock) { completedCandleCache[cacheKey] = decoded }
        }
        return decoded
    }

    /**
     * Download one `.bi5` object. A 404 is a closed market period. A timeout
     * or WAF on the archive host marks the archive dead for this process:
     * that host currently never writes a response, and retrying it per hour
     * is what froze chart loads.
     */
    private fun downloadBi5(path: String): ByteArray? {
        if (archiveDead.get()) return null
        var transportFailure = false
        for (host in DATAFEED_HOSTS) {
            val request = Request.Builder()
                .url("$host/$path")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()
            try {
                archiveClient.newCall(request).execute().use { response ->
                    val challenged = !response.header("x-amzn-waf-action").isNullOrBlank()
                    val bytes = response.body?.bytes()
                    when (classifyDukascopyResponse(response.code, bytes == null || bytes.isEmpty(), challenged)) {
                        DukascopyHttpOutcome.ABSENT -> return ByteArray(0)
                        DukascopyHttpOutcome.RETRY -> transportFailure = true
                        DukascopyHttpOutcome.DATA -> {
                            if (bytes != null && bytes.size <= MAX_BI5_BYTES) return bytes
                            transportFailure = true
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                transportFailure = true
            }
        }
        if (transportFailure) archiveDead.set(true)
        return null
    }

    fun getPointValue(symbol: String): Double =
        instrumentCatalog.resolve(symbol)?.bi5PointValue
            ?: if (normalizeRaw(symbol).contains("JPY")) 1_000.0 else 100_000.0

    fun normalizeSymbol(symbol: String): String =
        instrumentCatalog.resolve(symbol)?.providerSymbol ?: normalizeRaw(symbol)

    private fun normalizeRaw(symbol: String): String = symbol.trim().uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(".", "")
        .replace(" ", "")

    private fun sourceFor(timeframe: Timeframe): CandleSource = when (timeframe) {
        Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30 -> CandleSource.MINUTE
        Timeframe.H1, Timeframe.H4 -> CandleSource.HOUR
        Timeframe.D1, Timeframe.W1, Timeframe.MN -> CandleSource.DAY
    }

    private fun bucketStart(date: ZonedDateTime, source: CandleSource): ZonedDateTime = when (source) {
        CandleSource.MINUTE -> date.toLocalDate().atStartOfDay(ZoneOffset.UTC)
        CandleSource.HOUR -> date.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC)
        CandleSource.DAY -> date.withDayOfYear(1).toLocalDate().atStartOfDay(ZoneOffset.UTC)
    }

    private fun nextBucket(date: ZonedDateTime, source: CandleSource): ZonedDateTime = when (source) {
        CandleSource.MINUTE -> date.plusDays(1)
        CandleSource.HOUR -> date.plusMonths(1)
        CandleSource.DAY -> date.plusYears(1)
    }

    private fun previousBucket(date: ZonedDateTime, source: CandleSource): ZonedDateTime = when (source) {
        CandleSource.MINUTE -> date.minusDays(1)
        CandleSource.HOUR -> date.minusMonths(1)
        CandleSource.DAY -> date.minusYears(1)
    }

    private fun floorToHour(timestampMs: Long): Long = Math.floorDiv(timestampMs, ONE_HOUR_MS) * ONE_HOUR_MS

    private fun calculateHoursNeeded(timeframe: Timeframe, limit: Int): Int = when (timeframe) {
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

    companion object {
        const val BASE_DATAFEED_URL = "https://datafeed.dukascopy.com/datafeed"
        const val CANDLE_API_URL = "https://jetta.dukascopy.com/v1"
        // www.dukascopy.com/datafeed only 301s onto the host above, which then
        // hangs. Probing the redirect burns a second full timeout for nothing.
        private val DATAFEED_HOSTS = listOf(BASE_DATAFEED_URL)
        const val ONE_HOUR_MS = 3_600_000L
        private const val ONE_DAY_MS = 86_400_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) FoxTrader/6 Dukascopy Market Data"
        // Raised to match what the chart asks for. The chart requests the
        // history its studies need and this clamped it to a fifth of that
        // without saying so, which is a hard ceiling no setting could lift:
        // Compass needs roughly two thousand bars before it can calibrate at
        // all, so the clamp and the requirement were set against each other.
        // The per-request bucket budget below still bounds the actual work.
        const val MAX_CANDLE_LIMIT = 5_000
        private const val MAX_CANDLE_BUCKET_REQUESTS = 96
        // Matches the public market-data client's per-host dispatcher budget so
        // a batch is actually issued in parallel instead of queueing in OkHttp.
        private const val CANDLE_FETCH_CONCURRENCY = 12
        private const val MARKET_GAP_SEARCH_FACTOR = 2.0
        private const val HTTP_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 250L
        private const val ARCHIVE_TIMEOUT_SEC = 4L
        private const val MAX_CANDLE_RESPONSE_CHARS = 8 * 1024 * 1024
        private const val MAX_BI5_BYTES = 8 * 1024 * 1024
        // `MEMORY` Row budgets, not bucket counts. A Candle and a Tick are both
        // ~64 B once the object header and list slot are counted, so these caps
        // bound the two caches at roughly 8 MB and 10 MB of retained heap —
        // something a mid-range handset holds comfortably alongside the chart's
        // own working set. The old entry-count caps (192 buckets each) placed no
        // bound at all on a dense `.bi5` hour and could retain hundreds of MB.
        private const val MAX_CACHED_CANDLE_ROWS = 120_000
        private const val MAX_CACHED_TICK_ROWS = 150_000
        private const val FETCH_BATCH_HOURS = 8
        private const val LIVE_POLL_BATCH_HOURS = 2
        private const val LIVE_POLL_LIMIT_THRESHOLD = 5
        private const val MIN_MARKET_GAP_LOOKBACK_HOURS = 96
        private const val MAX_EMPTY_HOURS = MIN_MARKET_GAP_LOOKBACK_HOURS
        private const val MAX_BI5_FALLBACK_HOURS = 720

        /** `MEMORY` Ceiling on ticks held live while walking the `.bi5` fallback. */
        private const val MAX_ACCUMULATED_TICKS = 400_000
    }
}
