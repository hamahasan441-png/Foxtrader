package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import javax.inject.Inject

/**
 * Adapter wrapping [MetaApiService] to map MetaApi REST DTOs into domain models.
 *
 * Handles:
 * - Account deployment (provisioning on MetaApi)
 * - Account info retrieval and mapping to [Mt4AccountInfo]
 * - Position retrieval and mapping to [Mt4Position]
 * - Trade execution and order type mapping
 *
 * This is a data-layer detail consumed by [com.foxtrader.app.data.repository.Mt4RepositoryImpl].
 */
class MetaApiDataSource @Inject constructor(
    private val api: MetaApiService,
) {

    /**
     * Deploy an MT4 account on MetaApi and return the provisioned account ID.
     *
     * @param token The MetaApi auth token.
     * @param credentials MT4 login credentials.
     * @return The MetaApi account ID for subsequent requests.
     * @throws IllegalStateException if the deploy response is missing an ID.
     */
    suspend fun deployAccount(token: String, credentials: Mt4Credentials): String {
        val request = MetaApiDeployRequest(
            login = credentials.login,
            password = credentials.password,
            name = "FoxTrader-${credentials.login}",
            server = credentials.server,
            platform = credentials.platform,
        )
        val response = api.deployAccount(authToken = token, request = request)
        check(response.id.isNotBlank()) { "MetaApi: account deploy returned empty ID" }
        return response.id
    }

    /**
     * Fetch account information and map to [Mt4AccountInfo].
     *
     * @param token The MetaApi auth token.
     * @param accountId The MetaApi account ID.
     * @return Mapped account information.
     */
    suspend fun getAccountInfo(token: String, accountId: String): Mt4AccountInfo {
        val response = api.getAccountInformation(authToken = token, accountId = accountId)
        return response.toDomain()
    }

    /**
     * Fetch open positions and map to [Mt4Position] list.
     *
     * @param token The MetaApi auth token.
     * @param accountId The MetaApi account ID.
     * @return List of open positions mapped to domain models.
     */
    suspend fun getPositions(token: String, accountId: String): List<Mt4Position> {
        val response = api.getPositions(authToken = token, accountId = accountId)
        return response.map { it.toDomain() }
    }

    /**
     * Execute a trade on the MT4 account.
     *
     * @param token The MetaApi auth token.
     * @param accountId The MetaApi account ID.
     * @param symbol Trading instrument.
     * @param type Order type.
     * @param lots Trade volume.
     * @param sl Stop loss (null for none).
     * @param tp Take profit (null for none).
     * @return The order ticket ID.
     * @throws IllegalStateException if the trade execution fails.
     */
    suspend fun executeTrade(
        token: String,
        accountId: String,
        symbol: String,
        type: Mt4OrderType,
        lots: Double,
        sl: Double?,
        tp: Double?,
    ): Long {
        val request = MetaApiTradeRequest(
            actionType = type.toMetaApiAction(),
            symbol = symbol,
            volume = lots,
            stopLoss = sl,
            takeProfit = tp,
        )
        val response = api.executeTrade(authToken = token, accountId = accountId, request = request)
        check(response.numericCode == 0 || response.orderId > 0) {
            "MetaApi trade failed: ${response.stringCode} - ${response.message}"
        }
        return response.orderId
    }

    /**
     * Close an open position by ticket number.
     *
     * @param token The MetaApi auth token.
     * @param accountId The MetaApi account ID.
     * @param ticket The position ticket to close.
     * @throws IllegalStateException if closing fails.
     */
    suspend fun closePosition(token: String, accountId: String, ticket: Long) {
        val request = MetaApiTradeRequest(
            actionType = "POSITION_CLOSE_ID",
            symbol = "",
            volume = 0.0,
            positionId = ticket,
        )
        val response = api.executeTrade(authToken = token, accountId = accountId, request = request)
        check(response.numericCode == 0 || response.stringCode == "TRADE_RETCODE_DONE") {
            "MetaApi close failed: ${response.stringCode} - ${response.message}"
        }
    }

    // ========================================================================
    // MAPPING
    // ========================================================================

    private fun MetaApiAccountInfoResponse.toDomain(): Mt4AccountInfo = Mt4AccountInfo(
        login = login,
        balance = balance,
        equity = equity,
        margin = margin,
        freeMargin = freeMargin,
        leverage = leverage,
        currency = currency,
        name = name,
        server = server,
    )

    private fun MetaApiPositionResponse.toDomain(): Mt4Position = Mt4Position(
        ticket = id,
        symbol = symbol,
        type = parseOrderType(type),
        lots = volume,
        openPrice = openPrice,
        openTime = time,
        sl = stopLoss,
        tp = takeProfit,
        profit = profit,
        swap = swap,
        commission = commission,
    )

    private fun parseOrderType(raw: String): Mt4OrderType = when (raw.uppercase()) {
        "POSITION_TYPE_BUY", "ORDER_TYPE_BUY", "BUY" -> Mt4OrderType.BUY
        "POSITION_TYPE_SELL", "ORDER_TYPE_SELL", "SELL" -> Mt4OrderType.SELL
        "ORDER_TYPE_BUY_LIMIT", "BUY_LIMIT" -> Mt4OrderType.BUY_LIMIT
        "ORDER_TYPE_SELL_LIMIT", "SELL_LIMIT" -> Mt4OrderType.SELL_LIMIT
        "ORDER_TYPE_BUY_STOP", "BUY_STOP" -> Mt4OrderType.BUY_STOP
        "ORDER_TYPE_SELL_STOP", "SELL_STOP" -> Mt4OrderType.SELL_STOP
        else -> Mt4OrderType.BUY
    }

    private fun Mt4OrderType.toMetaApiAction(): String = when (this) {
        Mt4OrderType.BUY -> "ORDER_TYPE_BUY"
        Mt4OrderType.SELL -> "ORDER_TYPE_SELL"
        Mt4OrderType.BUY_LIMIT -> "ORDER_TYPE_BUY_LIMIT"
        Mt4OrderType.SELL_LIMIT -> "ORDER_TYPE_SELL_LIMIT"
        Mt4OrderType.BUY_STOP -> "ORDER_TYPE_BUY_STOP"
        Mt4OrderType.SELL_STOP -> "ORDER_TYPE_SELL_STOP"
    }
}
