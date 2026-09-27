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
            // An unfilled bracket entry takes its protective exits with it.
            bracketOrders.firstOrNull { it.entryOrder.id == id }?.let { bracket ->
                cancelOrder(bracket.takeProfitOrder.id)
                cancelOrder(bracket.stopLossOrder.id)
            }
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
        // Bracket exits whose entry filled on THIS candle are not eligible until
        // the next one: OHLC does not say whether the exit level was touched
        // before or after the entry, so booking both on one bar invents a trade.
        val entriesFilledThisTick = mutableSetOf<String>()

        for (i in orders.indices) {
            val order = orders[i]
            if (order.status != OrderStatus.PENDING) continue
            if (order.symbol != symbol) continue
            if (!bracketExitIsArmed(order.id, entriesFilledThisTick)) continue

            val fillPrice = when (order.type) {
                OrderType.MARKET -> marketFillPrice(candle)
                OrderType.LIMIT -> limitFillPrice(order, candle)
                OrderType.STOP -> stopFillPrice(order, candle)
                OrderType.STOP_LIMIT -> stopFillPrice(order, candle)
                OrderType.TRAILING_STOP -> trailingStopFillPrice(order, candle, i)
            } ?: continue

            // Re-read: the trailing-stop path may have ratcheted the stored stop.
            orders[i] = orders[i].copy(
                status = OrderStatus.FILLED,
                filledPrice = fillPrice,
                filledAt = candle.timestamp,
            )
            filled.add(orders[i])
            if (bracketOrders.any { it.entryOrder.id == order.id }) entriesFilledThisTick += order.id
            cancelLinkedOco(order.id)
            cancelLinkedBracketExit(order.id)
        }
        return filled
    }

    /**
     * A bracket's take-profit and stop-loss protect a position that exists only
     * once the entry has filled. Previously they were live from placement, so a
     * bracket whose entry never filled could still "fill" its exits.
     */
    private fun bracketExitIsArmed(orderId: String, entriesFilledThisTick: Set<String>): Boolean {
        val bracket = bracketOrders.firstOrNull {
            it.takeProfitOrder.id == orderId || it.stopLossOrder.id == orderId
        } ?: return true
        val entryId = bracket.entryOrder.id
        if (entryId in entriesFilledThisTick) return false
        return orders.firstOrNull { it.id == entryId }?.status == OrderStatus.FILLED
    }

    private fun marketFillPrice(candle: Candle): Double =
        candle.open.takeIf { it.isFinite() && it > 0.0 } ?: candle.close

    /**
     * A limit fills at its price, or at the open when the bar opened already
     * through it (a better price the book really offered). It never fills at
     * the close: a buy limit at 100 touched intrabar is not a fill at 105.
     */
    private fun limitFillPrice(order: TradeOrder, candle: Candle): Double? {
        val price = order.price ?: return null
        return if (order.direction == Direction.BULLISH) {
            when {
                candle.open <= price -> candle.open
                candle.low <= price -> price
                else -> null
            }
        } else {
            when {
                candle.open >= price -> candle.open
                candle.high >= price -> price
                else -> null
            }
        }
    }

    /** A stop fills at its price, or at the open when the bar gapped through it. */
    private fun stopFillPrice(order: TradeOrder, candle: Candle): Double? {
        val stop = order.stopPrice ?: return null
        return stopFill(order.direction, stop, candle)
    }

    private fun stopFill(direction: Direction, stop: Double, candle: Candle): Double? =
        if (direction == Direction.BULLISH) {
            when {
                candle.open >= stop -> candle.open
                candle.high >= stop -> stop
                else -> null
            }
        } else {
            when {
                candle.open <= stop -> candle.open
                candle.low <= stop -> stop
                else -> null
            }
        }

    /**
     * [TradeOrder.direction] is the order side, as for every other order type:
     * a BEARISH trailing stop is a sell stop that trails below price and
     * protects a long; a BULLISH one is a buy stop trailing above price.
     *
     * The first candle only anchors the stop at the close. Previously the stop
     * was seeded from the close and then tested against that same candle's
     * range, which always contains the close — so every trailing stop filled
     * on the first tick it saw.
     */
    private fun trailingStopFillPrice(order: TradeOrder, candle: Candle, idx: Int): Double? {
        val distance = order.trailingDistance?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val currentStop = order.stopPrice
        if (currentStop == null) {
            val anchor = if (order.direction == Direction.BEARISH) {
                candle.close - distance
            } else {
                candle.close + distance
            }
            orders[idx] = order.copy(stopPrice = anchor)
            return null
        }

        // Test the stop that was in force when the bar opened, then ratchet it
        // with this bar's favourable extreme for the next bar.
        val fill = stopFill(order.direction, currentStop, candle)
        if (fill != null) return fill
        val ratcheted = if (order.direction == Direction.BEARISH) {
            maxOf(currentStop, candle.high - distance)
        } else {
            minOf(currentStop, candle.low + distance)
        }
        orders[idx] = order.copy(stopPrice = ratcheted)
        return null
    }

    /** One bracket exit filling cancels its sibling; cancelling an entry cancels both. */
    private fun cancelLinkedBracketExit(filledOrderId: String) {
        for (bracket in bracketOrders) {
            when (filledOrderId) {
                bracket.takeProfitOrder.id -> cancelOrder(bracket.stopLossOrder.id)
                bracket.stopLossOrder.id -> cancelOrder(bracket.takeProfitOrder.id)
            }
        }
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
