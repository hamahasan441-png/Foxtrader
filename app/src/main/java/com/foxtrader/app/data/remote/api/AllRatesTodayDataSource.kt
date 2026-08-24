package com.foxtrader.app.data.remote.api

import com.foxtrader.app.di.PublicMarketDataClient
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct AllRatesToday adapter for Android devices.
 *
 * The API key is read from encrypted AppPreferences at request time and is sent
 * only in the HTTPS Authorization header to allratestoday.com. This path does
 * not depend on the FoxTrader backend, so physical phones never need the
 * emulator-only 10.0.2.2 address for AllRatesToday market data.
 */
@Singleton
class AllRatesTodayDataSource @Inject constructor(
    @PublicMarketDataClient client: OkHttpClient,
    json: Json,
    private val appPreferences: AppPreferences,
) {
    private val api: AllRatesTodayApi = Retrofit.Builder()
        .baseUrl(ALL_RATES_TODAY_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
        .build()
        .create(AllRatesTodayApi::class.java)

    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        before: Long? = null,
    ): List<Candle> {
        val pair = normalizePair(symbol)
        val base = pair.substring(0, ISO_CODE_LENGTH)
        val quote = pair.substring(ISO_CODE_LENGTH)
        val safeLimit = limit.coerceIn(1, MAX_CANDLES)
        val endMs = ((before ?: System.currentTimeMillis()) - if (before == null) 0L else 1L)
            .coerceAtLeast(0L)
        val requestedSpanMs = timeframe.minutes.toLong() * safeLimit.toLong() * MINUTE_MS
        val marginMs = maxOf(requestedSpanMs / 3L, HISTORY_MARGIN_MS)
        val startMs = (endMs - requestedSpanMs - marginMs).coerceAtLeast(0L)

        val payload = callVendor {
            api.getRates(
                authorization = bearerToken(),
                source = base,
                target = quote,
                from = utcDate(startMs),
                to = utcDate(endMs),
                group = groupFor(timeframe),
            )
        }

        val points = extractRatePoints(payload)
            .asSequence()
            .filter { (timestamp, _) -> timestamp in startMs..endMs }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()

        check(points.isNotEmpty()) {
            "AllRatesToday returned no rate samples for $base/$quote ${timeframe.label}."
        }

        return aggregate(points, timeframe.minutes)
            .takeLast(safeLimit)
    }

    suspend fun discoverSymbols(): List<ProviderMarketSymbol> {
        val response = callVendor { api.getSymbols() }
        val currencies = response.currencies
            .asSequence()
            .map { item -> item.code.trim().uppercase() to item.name.trim() }
            .filter { (code, _) -> code.length == ISO_CODE_LENGTH && code.all(Char::isLetter) }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()

        check(currencies.size >= 2) { "AllRatesToday returned no usable currency directory." }

        return buildList(currencies.size * (currencies.size - 1)) {
            for ((base, baseName) in currencies) {
                for ((quote, quoteName) in currencies) {
                    if (base == quote) continue
                    val pair = "$base$quote"
                    add(
                        ProviderMarketSymbol(
                            provider = DataProvider.ALL_RATES_TODAY,
                            providerSymbol = pair,
                            canonicalSymbol = pair,
                            displayName = "$base/$quote",
                            assetClass = AssetClass.FOREX,
                            marketType = MarketType.SPOT,
                            baseAsset = base,
                            quoteAsset = quote,
                            category = "FX · $baseName / $quoteName",
                            isTrading = true,
                        )
                    )
                }
            }
        }
    }

    private fun bearerToken(): String = "Bearer ${requireApiKey()}"

    private fun requireApiKey(): String =
        appPreferences.getApiKey(DataProvider.ALL_RATES_TODAY)
            ?: throw IllegalStateException(
                "AllRatesToday API key is required. Open Settings → Data Provider, enter the key, and test the connection."
            )

    private suspend fun <T> callVendor(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw when (error.code()) {
            401, 403 -> IllegalStateException(
                "AllRatesToday rejected the API key. Check the key in Settings and try again."
            )
            429 -> IllegalStateException(
                "AllRatesToday rate limit was reached. Wait for the provider limit to reset or check your plan."
            )
            else -> IllegalStateException(
                "AllRatesToday request failed (HTTP ${error.code()})."
            )
        }
    } catch (error: IOException) {
        throw IllegalStateException(
            "Cannot reach AllRatesToday. Check the phone's internet connection and try again.",
            error,
        )
    }

    private fun normalizePair(symbol: String): String {
        val pair = symbol.trim().uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
        require(pair.length == PAIR_LENGTH && pair.all(Char::isLetter)) {
            "AllRatesToday requires a 6-letter ISO FX pair."
        }
        val base = pair.substring(0, ISO_CODE_LENGTH)
        val quote = pair.substring(ISO_CODE_LENGTH)
        require(base != quote) { "AllRatesToday base and quote currencies must differ." }
        return pair
    }

    private fun groupFor(timeframe: Timeframe): String = when {
        timeframe.minutes < 60 -> "minute"
        timeframe.minutes < 1440 -> "hour"
        else -> "day"
    }

    private fun utcDate(timestampMs: Long): String =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun extractRatePoints(payload: JsonElement): List<Pair<Long, Double>> {
        val rows: List<JsonElement> = when (payload) {
            is JsonArray -> payload
            is JsonObject -> when {
                payload["data"] is JsonArray -> payload["data"]!!.jsonArray
                payload["rates"] is JsonArray -> payload["rates"]!!.jsonArray
                payload["rate"] != null -> listOf(payload)
                else -> emptyList()
            }
            else -> emptyList()
        }

        return rows.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val rate = row["rate"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (!rate.isFinite() || rate <= 0.0) return@mapNotNull null
            val timestamp = parseTimestamp(
                row["timestamp"]?.jsonPrimitive?.contentOrNull
                    ?: row["time"]?.jsonPrimitive?.contentOrNull
                    ?: row["date"]?.jsonPrimitive?.contentOrNull
            ) ?: return@mapNotNull null
            timestamp to rate
        }
    }

    private fun parseTimestamp(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { numeric ->
            return if (numeric >= 100_000_000_000L) numeric else numeric * 1000L
        }
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            runCatching {
                java.time.LocalDate.parse(text.take(10))
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
    }

    private fun aggregate(points: List<Pair<Long, Double>>, timeframeMinutes: Int): List<Candle> {
        val bucketMs = timeframeMinutes.toLong().coerceAtLeast(1L) * MINUTE_MS
        return points
            .groupBy { (timestamp, _) -> (timestamp / bucketMs) * bucketMs }
            .toSortedMap()
            .mapNotNull { (bucket, samples) ->
                val ordered = samples.sortedBy { it.first }
                val values = ordered.map { it.second }
                if (values.isEmpty()) return@mapNotNull null
                Candle(
                    timestamp = bucket,
                    open = values.first(),
                    high = values.maxOrNull() ?: return@mapNotNull null,
                    low = values.minOrNull() ?: return@mapNotNull null,
                    close = values.last(),
                    volume = 0.0,
                )
            }
    }

    private companion object {
        const val ALL_RATES_TODAY_BASE_URL = "https://allratestoday.com/"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ISO_CODE_LENGTH = 3
        const val PAIR_LENGTH = 6
        const val MAX_CANDLES = 5_000
        const val MINUTE_MS = 60_000L
        const val HISTORY_MARGIN_MS = 2L * 24L * 60L * MINUTE_MS
    }
}
