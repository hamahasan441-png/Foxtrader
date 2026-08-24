package com.foxtrader.app.data.remote.api

import com.foxtrader.app.data.remote.dto.AllRatesTodayVendorSymbolsResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** Direct AllRatesToday REST API used by physical devices. */
interface AllRatesTodayApi {

    @GET("api/v1/rates")
    suspend fun getRates(
        @Header("Authorization") authorization: String,
        @Query("source") source: String,
        @Query("target") target: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("group") group: String? = null,
    ): JsonElement

    @GET("api/v1/symbols")
    suspend fun getSymbols(): AllRatesTodayVendorSymbolsResponse
}
