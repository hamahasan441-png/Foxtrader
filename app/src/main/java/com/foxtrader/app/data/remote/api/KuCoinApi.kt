package com.foxtrader.app.data.remote.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * KuCoin V1 public REST API for historical candlestick data.
 *
 * Endpoint docs: GET /api/v1/market/candles
 * Response `data` is a list of arrays (NEWEST FIRST) with KuCoin's own field
 * order: [time(sec), open, close, high, low, volume, turnover].
 * Note the unusual open/close/high/low ordering (O, C, H, L), handled in the
 * data source's row mapping.
 */
interface KuCoinApi {
    @GET("/api/v1/market/candles")
    suspend fun getCandles(
        @Query("symbol") symbol: String,
        @Query("type") type: String,
    ): KuCoinCandleResponse
}

@Serializable
data class KuCoinCandleResponse(
    val code: String = "200000",
    val msg: String = "",
    val data: List<List<String>> = emptyList(),
)
