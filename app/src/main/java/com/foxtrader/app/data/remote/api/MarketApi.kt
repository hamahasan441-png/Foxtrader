package com.foxtrader.app.data.remote.api

import com.foxtrader.app.data.remote.dto.AllRatesTodaySymbolsResponse
import com.foxtrader.app.data.remote.dto.CandlesResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** FoxTrader backend market-data API (FastAPI). */
interface MarketApi {

    @GET("api/v1/market/candles/{symbol}/{timeframe}")
    suspend fun getCandles(
        @Path("symbol") symbol: String,
        @Path("timeframe") timeframe: String,
        @Query("limit") limit: Int = 500,
        @Query("before") before: Long? = null,
    ): CandlesResponse

    @GET("api/v1/market/providers/allratestoday/candles/{symbol}/{timeframe}")
    suspend fun getAllRatesTodayCandles(
        @Path("symbol") symbol: String,
        @Path("timeframe") timeframe: String,
        @Query("limit") limit: Int = 500,
        @Query("before") before: Long? = null,
        @Header("X-AllRatesToday-Key") apiKey: String,
    ): CandlesResponse

    @GET("api/v1/market/providers/allratestoday/symbols")
    suspend fun getAllRatesTodaySymbols(
        @Header("X-AllRatesToday-Key") apiKey: String,
    ): AllRatesTodaySymbolsResponse
}
