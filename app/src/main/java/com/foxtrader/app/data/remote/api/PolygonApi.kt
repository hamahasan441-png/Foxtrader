package com.foxtrader.app.data.remote.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Polygon.io v2 aggregate bars endpoint.
 *
 * The API returns one result object per bar with epoch-millisecond `t` plus
 * OHLCV fields (`o`, `h`, `l`, `c`, `v`). The response is intentionally kept as
 * [JsonElement] here: Polygon adds optional result fields over time and the data
 * source only needs the stable aggregate fields.
 *
 * Docs: https://polygon.io/docs/rest/stocks/aggregates-custom-bars
 */
interface PolygonApi {

    @GET("v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{from}/{to}")
    suspend fun aggregateBars(
        /** Polygon tickers such as `C:EURUSD`, `X:BTCUSD`, or `AAPL`. */
        @Path("ticker", encoded = true) ticker: String,
        @Path("multiplier") multiplier: Int,
        @Path("timespan") timespan: String,
        @Path("from") from: Long,
        @Path("to") to: Long,
        @Query("adjusted") adjusted: Boolean = true,
        @Query("sort") sort: String = "asc",
        @Query("limit") limit: Int = 50_000,
        @Query("apiKey") apiKey: String,
    ): JsonElement
}
