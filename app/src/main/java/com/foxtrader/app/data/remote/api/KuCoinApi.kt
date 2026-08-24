package com.foxtrader.app.data.remote.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** KuCoin public REST spot market-data API. */
interface KuCoinApi {
    @GET("/api/v1/market/candles")
    suspend fun getCandles(
        @Query("symbol") symbol: String,
        @Query("type") type: String,
    ): KuCoinCandleResponse

    /** Current public directory of available KuCoin spot trading pairs. */
    @GET("/api/v2/symbols")
    suspend fun getSymbols(
        @Query("market") market: String? = null,
    ): KuCoinSymbolsResponse
}

@Serializable
data class KuCoinCandleResponse(
    val code: String = "200000",
    val msg: String = "",
    val data: List<List<String>> = emptyList(),
)

@Serializable
data class KuCoinSymbolsResponse(
    val code: String = "200000",
    val msg: String = "",
    val data: List<KuCoinSymbol> = emptyList(),
)

@Serializable
data class KuCoinSymbol(
    val symbol: String = "",
    val name: String = "",
    val baseCurrency: String = "",
    val quoteCurrency: String = "",
    val market: String? = null,
    val priceIncrement: String? = null,
    val enableTrading: Boolean = false,
)
