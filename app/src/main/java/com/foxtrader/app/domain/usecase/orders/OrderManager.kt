package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.BracketOrder
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OcoOrder
import com.foxtrader.app.domain.model.OrderStatus
import com.foxtrader.app.domain.model.OrderType
import com.foxtrader.app.domain.model.TimeInForce
import com.foxtrader.app.domain.model.TradeOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Order Manager — handles order lifecycle for all order types.
 *
 * Supports:
 * - Market, Limit, Stop, Stop-Limit, Trailing Stop orders
 * - OCO (One-Cancels-Other) pairs
 * - Bracket orders (entry + TP + SL)
 * - Order fill simulation against price ticks
 * - Trailing stop price adjustment
 *
 * Pure domain logic — broker execution in data layer.
 */
@Singleton
class OrderManager @Inject constructor() {

    private val orders = mutableListOf<TradeOrder>()
    private val ocoOrders = mutableListOf<OcoOrder>()
    private val bracketOrders = mutableListOf<BracketOrder>()

    // ========================================================================
    // ORDER CREATION
    // ========================================================================

    fun placeMarketOrder(symbol: String, direction: Direction, volume: Double): TradeOrder {
        val order = TradeOrder(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            direction = direction,
            type = OrderType.MARKET,
            volume = volume,
            price = null,
            stopPrice = null,
            trailingDistance = null,
            status = OrderStatus.PENDING,
        )
        orders.add(order)
        return order
    }

    fun placeLimitOrder(
        symbol: String, direction: Direction, volume: Double,
        price: Double, timeInForce: TimeInForce = TimeInForce.GTC,
    ): TradeOrder {
        val order = TradeOrder(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            direction = direction,
            type = OrderType.LIMIT,
            volume = volume,
            price = price,
            stopPrice = null,
            trailingDistance = null,
            timeInForce = timeInForce,
        )
        orders.add(order)
        return order
    }

    fun placeStopOrder(
        symbol: String, direction: Direction, volume: Double,
        stopPrice: Double,
    ): TradeOrder {
        val order = TradeOrder(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            direction = direction,
            type = OrderType.STOP,
            volume = volume,
            price = null,
            stopPrice = stopPrice,
            trailingDistance = null,
        )
        orders.add(order)
        return order
    }

    fun placeTrailingStop(
        symbol: String, direction: Direction, volume: Double,
        trailingDistance: Double,
    ): TradeOrder {
        val order = TradeOrder(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            direction = direction,
            type = OrderType.TRAILING_STOP,
            volume = volume,
            price = null,
            stopPrice = null,
            trailingDistance = trailingDistance,
        )
        orders.add(order)
        return order
    }

    fun placeOcoOrder(
        symbol: String, direction: Direction, volume: Double,
        takeProfitPrice: Double, stopLossPrice: Double,
    ): OcoOrder {
        val tpOrder = TradeOrder(
            id = UUID.randomUUID().toString(), symbol = symbol,
            direction = if (direction == Direction.BULLISH) Direction.BEARISH else Direction.BULLISH,
            type = OrderType.LIMIT, volume = volume, price = takeProfitPrice,
            stopPrice = null, trailingDistance = null,
        )
        val slOrder = TradeOrder(
            id = UUID.randomUUID().toString(), symbol = symbol,
            direction = if (direction == Direction.BULLISH) Direction.BEARISH else Direction.BULLISH,
            type = OrderType.STOP, volume = volume, price = null,
            stopPrice = stopLossPrice, trailingDistance = null,
        )
        orders.addAll(listOf(tpOrder, slOrder))
        val oco = OcoOrder(
            id = UUID.randomUUID().toString(),
            takeProfitOrder = tpOrder,
            stopLossOrder = slOrder,
        )
        ocoOrders.add(oco)
        return oco
    }

    fun placeBracketOrder(
        symbol: String, direction: Direction, volume: Double,
        entryPrice: Double, takeProfitPrice: Double, stopLossPrice: Double,
    ): BracketOrder {
        val entry = TradeOrder(
            id = UUID.randomUUID().toString(), symbol = symbol,
            direction = direction, type = OrderType.LIMIT, volume = volume,
            price = entryPrice, stopPrice = null, trailingDistance = null,
        )
        val tp = TradeOrder(
            id = UUID.randomUUID().toString(), symbol = symbol,
            direction = if (direction == Direction.BULLISH) Direction.BEARISH else Direction.BULLISH,
            type = OrderType.LIMIT, volume = volume, price = takeProfitPrice,
            stopPrice = null, trailingDistance = null,
        )
        val sl = TradeOrder(
            id = UUID.randomUUID().toString(), symbol = symbol,
            direction = if (direction == Direction.BULLISH) Direction.BEARISH else Direction.BULLISH,
            type = OrderType.STOP, volume = volume, price = null,
            stopPrice = stopLossPrice, trailingDistance = null,
        )
        orders.addAll(listOf(entry, tp, sl))
        val bracket = BracketOrder(id = UUID.randomUUID().toString(), entry, tp, sl)
        bracketOrders.add(bracket)
        return bracket
    }

    // ========================================================================
    // ORDER MANAGEMENT
    // ========================================================================

    fun cancelOrder(id: String): Boolean {
        val idx = orders.indexOfFirst { it.id == id && it.status == OrderStatus.PENDING }
        if (idx >= 0) {
            orders[idx] = orders[idx].copy(status = OrderStatus.CANCELLED)
            // Cancel linked OCO partner
            cancelLinkedOco(id)
            return true
        }
        return false
    }

    fun getPendingOrders(): List<TradeOrder> = orders.filter { it.status == OrderStatus.PENDING }
    fun getFilledOrders(): List<TradeOrder> = orders.filter { it.status == OrderStatus.FILLED }
    fun getAllOrders(): List<TradeOrder> = orders.toList()

    // ========================================================================
    // TICK SIMULATION (for replay/backtest)
    // ========================================================================

    /**
     * Process a new candle tick against all pending orders for [symbol].
     * Returns list of orders that were filled this tick.
     *
     * @param symbol The instrument this candle belongs to (Candle has no symbol field,
     *               so the caller supplies the market context).
     */
    fun processTick(symbol: String, candle: Candle): List<TradeOrder> {
        val filled = mutableListOf<TradeOrder>()

        for (i in orders.indices) {
            val order = orders[i]
            if (order.status != OrderStatus.PENDING) continue
            if (order.symbol != symbol) continue

            val wasFilled = when (order.type) {
                OrderType.MARKET -> true
                OrderType.LIMIT -> checkLimitFill(order, candle)
                OrderType.STOP -> checkStopFill(order, candle)
                OrderType.STOP_LIMIT -> checkStopFill(order, candle)
                OrderType.TRAILING_STOP -> checkTrailingStopFill(order, candle, i)
            }

            if (wasFilled) {
                orders[i] = order.copy(
                    status = OrderStatus.FILLED,
                    filledPrice = candle.close,
                    filledAt = candle.timestamp,
                )
                filled.add(orders[i])
                cancelLinkedOco(order.id)
            }
        }
        return filled
    }

    private fun checkLimitFill(order: TradeOrder, candle: Candle): Boolean {
        val price = order.price ?: return false
        return if (order.direction == Direction.BULLISH) candle.low <= price
        else candle.high >= price
    }

    private fun checkStopFill(order: TradeOrder, candle: Candle): Boolean {
        val stop = order.stopPrice ?: return false
        return if (order.direction == Direction.BULLISH) candle.high >= stop
        else candle.low <= stop
    }

    private fun checkTrailingStopFill(order: TradeOrder, candle: Candle, idx: Int): Boolean {
        // Trailing stop logic: update stop price as market moves favorably
        val distance = order.trailingDistance ?: return false
        val currentStop = order.stopPrice ?: candle.close

        val newStop = if (order.direction == Direction.BEARISH) {
            // Trailing buy stop — lower stop as price drops
            kotlin.math.min(currentStop, candle.low + distance)
        } else {
            // Trailing sell stop — raise stop as price rises
            kotlin.math.max(currentStop, candle.high - distance)
        }
        orders[idx] = order.copy(stopPrice = newStop)

        return if (order.direction == Direction.BEARISH) candle.high >= newStop
        else candle.low <= newStop
    }

    private fun cancelLinkedOco(filledOrderId: String) {
        for (i in ocoOrders.indices) {
            val oco = ocoOrders[i]
            if (oco.takeProfitOrder.id == filledOrderId) {
                cancelOrder(oco.stopLossOrder.id)
                ocoOrders[i] = oco.copy(status = OrderStatus.FILLED)
            } else if (oco.stopLossOrder.id == filledOrderId) {
                cancelOrder(oco.takeProfitOrder.id)
                ocoOrders[i] = oco.copy(status = OrderStatus.FILLED)
            }
        }
    }

    fun clearAll() {
        orders.clear()
        ocoOrders.clear()
        bracketOrders.clear()
    }
}
