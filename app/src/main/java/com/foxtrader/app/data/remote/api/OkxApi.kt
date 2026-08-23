package com.foxtrader.app.data.remote.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** OKX V5 public REST market-data API. */
interface OkxApi {
    @GET("/api/v5/market/candles")
    suspend fun getCandles(
        @Query("instId") instId: String,
        @Query("bar") bar: String,
        @Query("limit") limit: Int = 100,
        @Query("after") after: String? = null,
    ): OkxCandleResponse

    @GET("/api/v5/public/instruments")
    suspend fun getInstruments(
        @Query("instType") instType: String = "SPOT",
    ): OkxInstrumentResponse
}

@Serializable
data class OkxCandleResponse(
    val code: String = "0",
    val msg: String = "",
    val data: List<List<String>> = emptyList(),
)

@Serializable
data class OkxInstrumentResponse(
    val code: String = "0",
    val msg: String = "",
    val data: List<OkxInstrument> = emptyList(),
)

@Serializable
data class OkxInstrument(
    val instType: String = "",
    val instId: String = "",
    val baseCcy: String = "",
    val quoteCcy: String = "",
    val tickSz: String? = null,
    val state: String = "",
)
