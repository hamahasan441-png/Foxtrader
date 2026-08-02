package com.foxtrader.app.data.remote.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Twelve Data REST API — time series endpoint.
 * Docs: https://twelvedata.com/docs#time-series
 *
 * Covers forex, stocks, indices, crypto, ETFs, and funds on a single API key.
 * Free tier: 800 requests/day, 8 per minute, end-of-day + limited intraday.
 */
interface TwelveDataApi {

    @GET("time_series")
    suspend fun timeSeries(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("outputsize") outputSize: Int = 500,
        @Query("apikey") apiKey: String,
        @Query("end_date") endDate: String? = null,
        @Query("format") format: String = "JSON",
    ): JsonElement
}
