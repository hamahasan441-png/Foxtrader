package com.foxtrader.app.domain.sdk.broker

import com.foxtrader.app.domain.model.Direction

/**
 * SDK interface for broker adapters (live order execution).
 *
 * CONTRACT:
 * - All orders MUST pass through [com.foxtrader.app.domain.usecase.risk.RiskEngine]
 *   before execution — a broker adapter can NEVER bypass risk gating.
 * - Biometric authentication required before placing live orders.
 * - Thread-safe, suspend for network calls.
 */
interface BrokerAdapter {
    val id: String
    val displayName: String
    val supportedAssets: List<String>  // e.g. ["BTCUSDT", "ETHUSDT"]

    /** Check connection / authentication with the broker. */
    suspend fun connect(): Boolean

    /** Place a market order. Returns an order ID or throws. */
    suspend fun placeOrder(order: OrderRequest): OrderResult

    /** Cancel an open order. */
    suspend fun cancelOrder(orderId: String): Boolean

    /** Get open positions. */
    suspend fun getPositions(): List<Position>
}

data class OrderRequest(
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val type: OrderType = OrderType.MARKET,
)

enum class OrderType { MARKET, LIMIT, STOP }

data class OrderResult(
    val orderId: String,
    val symbol: String,
    val filledPrice: Double,
    val volume: Double,
    val timestamp: Long,
)

data class Position(
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val unrealizedPnl: Double,
)
