package com.foxtrader.app.data.remote.api

import com.foxtrader.app.data.remote.dto.CandlesResponse
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-side adapter for the FoxTrader backend's AllRatesToday proxy.
 *
 * The user enters the vendor key in Settings. AppPreferences stores it in
 * encrypted preferences; this adapter reads it only at request time and sends
 * it to the FoxTrader backend in a dedicated HTTPS header. The key is never
 * placed in a URL, BuildConfig, source constant, or log message.
 */
@Singleton
class AllRatesTodayDataSource @Inject constructor(
    private val api: MarketApi,
    private val appPreferences: AppPreferences,
) {
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        before: Long? = null,
    ): List<Candle> {
        val response = api.getAllRatesTodayCandles(
            symbol = normalizePair(symbol),
            timeframe = timeframe.label,
            limit = limit.coerceIn(1, 5_000),
            before = before,
            apiKey = requireApiKey(),
        )
        requireRealAllRatesToday(response)
        return response.candles
            .asSequence()
            .filter { dto ->
                dto.timestamp > 0L && dto.open.isFinite() && dto.high.isFinite() &&
                    dto.low.isFinite() && dto.close.isFinite() &&
                    dto.high >= maxOf(dto.open, dto.close) &&
                    dto.low <= minOf(dto.open, dto.close)
            }
            .map { dto ->
                Candle(
                    timestamp = dto.timestamp,
                    open = dto.open,
                    high = dto.high,
                    low = dto.low,
                    close = dto.close,
                    volume = dto.volume.coerceAtLeast(0.0),
                )
            }
            .sortedBy { it.timestamp }
            .toList()
    }

    suspend fun discoverSymbols(): List<ProviderMarketSymbol> {
        val response = api.getAllRatesTodaySymbols(apiKey = requireApiKey())
        check(response.provider.equals("allratestoday", ignoreCase = true)) {
            "Unexpected provider symbol response: ${response.provider}"
        }
        return response.pairs
            .asSequence()
            .mapNotNull(::toMarketSymbol)
            .distinctBy { it.providerSymbol }
            .toList()
    }

    private fun requireApiKey(): String =
        appPreferences.getApiKey(DataProvider.ALL_RATES_TODAY)
            ?: throw IllegalStateException(
                "AllRatesToday API key is required. Open Settings → Data Provider, enter the key, and test the connection."
            )

    private fun toMarketSymbol(raw: String): ProviderMarketSymbol? {
        val pair = runCatching { normalizePair(raw) }.getOrNull() ?: return null
        val base = pair.substring(0, 3)
        val quote = pair.substring(3, 6)
        if (base == quote) return null
        return ProviderMarketSymbol(
            provider = DataProvider.ALL_RATES_TODAY,
            providerSymbol = pair,
            canonicalSymbol = pair,
            displayName = "$base/$quote",
            assetClass = AssetClass.FOREX,
            marketType = MarketType.SPOT,
            baseAsset = base,
            quoteAsset = quote,
            category = "FX",
            isTrading = true,
        )
    }

    private fun requireRealAllRatesToday(response: CandlesResponse) {
        check(response.provider.equals("allratestoday", ignoreCase = true)) {
            "Backend returned ${response.provider ?: "unknown"} instead of AllRatesToday."
        }
        check(!response.source.equals("synthetic", ignoreCase = true)) {
            "AllRatesToday proxy returned synthetic data; refusing to present it as market data."
        }
    }

    private fun normalizePair(symbol: String): String {
        val pair = symbol.trim().uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
        require(pair.length == 6 && pair.all(Char::isLetter)) {
            "AllRatesToday requires a 6-letter ISO FX pair."
        }
        return pair
    }
}
