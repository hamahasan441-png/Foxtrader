package com.foxtrader.app.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Bybit V5 public REST API for historical kline/candlestick data.
 *
 * Endpoint docs: GET /v5/market/kline
 * Response kline array fields:
 * [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
 */
interface BybitApi {
    @GET("/v5/market/kline")
    suspend fun getKlines(
        @Query("category") category: String = "spot",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
    ): BybitKlineResponse
}

@Serializable
data class BybitKlineResponse(
    val retCode: Int = 0,
    val retMsg: String = "",
    val result: BybitKlineResult? = null,
)

@Serializable
data class BybitKlineResult(
    val category: String? = null,
    val symbol: String? = null,
    @SerialName("list") val candles: List<List<String>> = emptyList(),
)
