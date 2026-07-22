package com.foxtrader.app.data.remote.api

import com.foxtrader.app.data.remote.dto.CandlesResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * FoxTrader backend market-data API (FastAPI).
 * Base URL configured in the network Hilt module.
 */
interface MarketApi {

    @GET("api/v1/market/candles/{symbol}/{timeframe}")
    suspend fun getCandles(
        @Path("symbol") symbol: String,
        @Path("timeframe") timeframe: String,
        @Query("limit") limit: Int = 500,
    ): CandlesResponse
}
