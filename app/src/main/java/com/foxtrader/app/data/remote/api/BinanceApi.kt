package com.foxtrader.app.data.remote.api

import kotlinx.serialization.json.JsonArray
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Binance public REST API for historical kline (candlestick) data.
 *
 * Base URL: https://api.binance.com
 * Endpoint: GET /api/v3/klines
 *
 * No API key required for public market data.
 *
 * Response format: Array of arrays — each inner array is:
 * [openTime, open, high, low, close, volume, closeTime, quoteAssetVol,
 *  numberOfTrades, takerBuyBaseVol, takerBuyQuoteVol, ignore]
 *
 * We parse as List<JsonArray> and convert in [BinanceDataSource].
 */
interface BinanceApi {

    /**
     * Fetch historical klines.
     *
     * @param symbol Binance trading pair, e.g. "BTCUSDT" (uppercase).
     * @param interval Kline interval: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M.
     * @param limit Number of candles to return (max 1000, default 500).
     * @param startTime Optional start time (epoch ms).
     * @param endTime Optional end time (epoch ms).
     */
    @GET("/api/v3/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null,
    ): List<JsonArray>
}
