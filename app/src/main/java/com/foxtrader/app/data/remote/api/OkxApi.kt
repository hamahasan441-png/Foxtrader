package com.foxtrader.app.data.remote.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OKX V5 public REST API for historical candlestick data.
 *
 * Endpoint docs: GET /api/v5/market/candles
 * Response `data` is a list of arrays (NEWEST FIRST) with fields:
 * [ts, open, high, low, close, volume, volCcy, volCcyQuote, confirm]
 * Only indices 0..5 (ts, open, high, low, close, volume) are consumed.
 */
interface OkxApi {
    @GET("/api/v5/market/candles")
    suspend fun getCandles(
        @Query("instId") instId: String,
        @Query("bar") bar: String,
        @Query("limit") limit: Int = 100,
        @Query("after") after: String? = null,
    ): OkxCandleResponse
}

@Serializable
data class OkxCandleResponse(
    val code: String = "0",
    val msg: String = "",
    val data: List<List<String>> = emptyList(),
)
