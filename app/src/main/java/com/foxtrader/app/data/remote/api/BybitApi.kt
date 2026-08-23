package com.foxtrader.app.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Bybit V5 public REST market-data API. */
interface BybitApi {
    @GET("/v5/market/kline")
    suspend fun getKlines(
        @Query("category") category: String = "spot",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("end") end: Long? = null,
    ): BybitKlineResponse

    /** Spot instrument directory. Spot does not use cursor pagination. */
    @GET("/v5/market/instruments-info")
    suspend fun getInstrumentsInfo(
        @Query("category") category: String = "spot",
    ): BybitInstrumentResponse
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

@Serializable
data class BybitInstrumentResponse(
    val retCode: Int = 0,
    val retMsg: String = "",
    val result: BybitInstrumentResult? = null,
)

@Serializable
data class BybitInstrumentResult(
    val category: String = "",
    val nextPageCursor: String = "",
    @SerialName("list") val instruments: List<BybitInstrument> = emptyList(),
)

@Serializable
data class BybitInstrument(
    val symbol: String = "",
    val baseCoin: String = "",
    val quoteCoin: String = "",
    val status: String = "",
    val symbolType: String? = null,
    val displayName: String? = null,
    val priceFilter: BybitPriceFilter? = null,
)

@Serializable
data class BybitPriceFilter(
    val tickSize: String? = null,
)
