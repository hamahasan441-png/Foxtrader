package com.foxtrader.app.data.remote.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Binance public REST API for spot market data.
 *
 * Both endpoints used here are public (`NONE` security):
 * - GET /api/v3/klines for candle history
 * - GET /api/v3/exchangeInfo for the provider-native spot symbol directory
 */
interface BinanceApi {

    @GET("/api/v3/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null,
    ): List<JsonArray>

    @GET("/api/v3/exchangeInfo")
    suspend fun getExchangeInfo(): BinanceExchangeInfoResponse
}

@Serializable
data class BinanceExchangeInfoResponse(
    val symbols: List<BinanceExchangeSymbol> = emptyList(),
)

@Serializable
data class BinanceExchangeSymbol(
    val symbol: String = "",
    val status: String = "",
    val baseAsset: String = "",
    val quoteAsset: String = "",
    val baseAssetPrecision: Int? = null,
    val quoteAssetPrecision: Int? = null,
    val isSpotTradingAllowed: Boolean = true,
    val filters: List<BinanceSymbolFilter> = emptyList(),
)

@Serializable
data class BinanceSymbolFilter(
    val filterType: String = "",
    val tickSize: String? = null,
)
