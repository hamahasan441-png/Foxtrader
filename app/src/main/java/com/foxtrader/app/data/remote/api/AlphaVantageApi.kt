package com.foxtrader.app.data.remote.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Alpha Vantage REST API.
 * Docs: https://www.alphavantage.co/documentation/
 */
interface AlphaVantageApi {
    @GET("query")
    suspend fun query(
        @Query("function") function: String,
        @Query("symbol") symbol: String? = null,
        @Query("from_symbol") fromSymbol: String? = null,
        @Query("to_symbol") toSymbol: String? = null,
        @Query("interval") interval: String? = null,
        @Query("outputsize") outputSize: String = "full",
        @Query("apikey") apiKey: String,
    ): JsonElement
}
