package com.foxtrader.app.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for the MetaApi REST API.
 *
 * Base URL: https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/
 * Auth: "auth-token" header containing the MetaApi bearer token.
 *
 * This connects MT4/MT5 accounts for trading and account information retrieval.
 */
interface MetaApiService {

    /**
     * Deploy (provision) an MT4 account on MetaApi.
     * Returns the created account metadata including the account ID.
     */
    @POST("/users/current/accounts")
    suspend fun deployAccount(
        @Header("auth-token") authToken: String,
        @Body request: MetaApiDeployRequest,
    ): MetaApiDeployResponse

    /**
     * Retrieve account information (balance, equity, margin, etc.).
     */
    @GET("/users/current/accounts/{accountId}/account-information")
    suspend fun getAccountInformation(
        @Header("auth-token") authToken: String,
        @Path("accountId") accountId: String,
    ): MetaApiAccountInfoResponse

    /**
     * Retrieve open positions on the account.
     */
    @GET("/users/current/accounts/{accountId}/positions")
    suspend fun getPositions(
        @Header("auth-token") authToken: String,
        @Path("accountId") accountId: String,
    ): List<MetaApiPositionResponse>

    /**
     * Execute a trade (market order or pending order).
     */
    @POST("/users/current/accounts/{accountId}/trade")
    suspend fun executeTrade(
        @Header("auth-token") authToken: String,
        @Path("accountId") accountId: String,
        @Body request: MetaApiTradeRequest,
    ): MetaApiTradeResponse
}

// ============================================================================
// REQUEST DTOs
// ============================================================================

@Serializable
data class MetaApiDeployRequest(
    val login: Int,
    val password: String,
    val name: String,
    val server: String,
    val platform: String = "mt4",
    val type: String = "cloud",
    val magic: Int = 0,
)

@Serializable
data class MetaApiTradeRequest(
    val actionType: String,
    val symbol: String,
    val volume: Double,
    @SerialName("stopLoss") val stopLoss: Double? = null,
    @SerialName("takeProfit") val takeProfit: Double? = null,
    val positionId: Long? = null,
    val comment: String = "FoxTrader",
)

// ============================================================================
// RESPONSE DTOs
// ============================================================================

@Serializable
data class MetaApiDeployResponse(
    val id: String,
    val state: String = "",
    val name: String = "",
    val server: String = "",
    val platform: String = "",
)

@Serializable
data class MetaApiAccountInfoResponse(
    val login: Int = 0,
    val balance: Double = 0.0,
    val equity: Double = 0.0,
    val margin: Double = 0.0,
    val freeMargin: Double = 0.0,
    val leverage: Int = 0,
    val currency: String = "USD",
    val name: String = "",
    val server: String = "",
)

@Serializable
data class MetaApiPositionResponse(
    val id: Long = 0,
    val symbol: String = "",
    val type: String = "",
    val volume: Double = 0.0,
    val openPrice: Double = 0.0,
    val time: Long = 0,
    @SerialName("stopLoss") val stopLoss: Double = 0.0,
    @SerialName("takeProfit") val takeProfit: Double = 0.0,
    val profit: Double = 0.0,
    val swap: Double = 0.0,
    val commission: Double = 0.0,
)

@Serializable
data class MetaApiTradeResponse(
    val numericCode: Int = 0,
    val stringCode: String = "",
    val orderId: Long = 0,
    val message: String = "",
)
