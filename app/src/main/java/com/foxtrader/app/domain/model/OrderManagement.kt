package com.foxtrader.app.domain.model

/**
 * Advanced Order Management models.
 * Supports limit, stop, OCO, trailing stop, and bracket orders.
 */

/** Order type. */
enum class OrderType {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT,
    TRAILING_STOP,
}

/** Order status lifecycle. */
enum class OrderStatus {
    PENDING,
    FILLED,
    PARTIALLY_FILLED,
    CANCELLED,
    EXPIRED,
    REJECTED,
}

/** Time-in-force for orders. */
enum class TimeInForce {
    GTC,  // Good Till Cancelled
    DAY,  // Day only
    IOC,  // Immediate or Cancel
    FOK,  // Fill or Kill
}

/**
 * A single order placed on the market.
 */
data class TradeOrder(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val type: OrderType,
    val volume: Double,
    val price: Double?,            // null for market orders
    val stopPrice: Double?,        // for stop/stop-limit orders
    val trailingDistance: Double?,  // for trailing stops (in price units)
    val timeInForce: TimeInForce = TimeInForce.GTC,
    val status: OrderStatus = OrderStatus.PENDING,
    val filledPrice: Double? = null,
    val filledAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val linkedOrderId: String? = null,  // For OCO pairs
    val notes: String = "",
)

/**
 * OCO (One-Cancels-Other) order pair.
 * When one order fills, the other is automatically cancelled.
 */
data class OcoOrder(
    val id: String,
    val takeProfitOrder: TradeOrder,
    val stopLossOrder: TradeOrder,
    val status: OrderStatus = OrderStatus.PENDING,
)

/**
 * Bracket order: entry + take profit + stop loss.
 */
data class BracketOrder(
    val id: String,
    val entryOrder: TradeOrder,
    val takeProfitOrder: TradeOrder,
    val stopLossOrder: TradeOrder,
    val status: OrderStatus = OrderStatus.PENDING,
)
