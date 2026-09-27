package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fill prices, trailing stops and bracket sequencing in [OrderManager]. */
class OrderManagerFillTest {

    private val manager = OrderManager()
    private var t = 0L

    private fun candle(open: Double, high: Double, low: Double, close: Double) =
        Candle(++t, open, high, low, close, 100.0)

    @Test
    fun `a limit fills at its price, not at the close`() {
        manager.placeLimitOrder("EURUSD", Direction.BULLISH, 0.1, price = 1.1000)
        val filled = manager.processTick("EURUSD", candle(1.1050, 1.1060, 1.0990, 1.1040))
        assertEquals(1.1000, filled.single().filledPrice!!, 1e-12)
    }

    @Test
    fun `a limit the bar opened through fills at the better open`() {
        manager.placeLimitOrder("EURUSD", Direction.BULLISH, 0.1, price = 1.1000)
        val filled = manager.processTick("EURUSD", candle(1.0980, 1.0990, 1.0970, 1.0985))
        assertEquals(1.0980, filled.single().filledPrice!!, 1e-12)
    }

    @Test
    fun `a stop gapped through fills at the worse open`() {
        manager.placeStopOrder("EURUSD", Direction.BEARISH, 0.1, stopPrice = 1.0950)
        val filled = manager.processTick("EURUSD", candle(1.0900, 1.0910, 1.0890, 1.0905))
        assertEquals(1.0900, filled.single().filledPrice!!, 1e-12)
    }

    @Test
    fun `a market order fills at the open`() {
        manager.placeMarketOrder("EURUSD", Direction.BULLISH, 0.1)
        val filled = manager.processTick("EURUSD", candle(1.1000, 1.1100, 1.0900, 1.1080))
        assertEquals(1.1000, filled.single().filledPrice!!, 1e-12)
    }

    @Test
    fun `a trailing sell stop anchors on its first bar and then trails`() {
        // Regression: the stop was seeded from the close and tested against the
        // same bar's range, which always contains the close — so every
        // trailing stop filled on the first tick it saw.
        manager.placeTrailingStop("EURUSD", Direction.BEARISH, 0.1, trailingDistance = 0.0050)

        assertTrue(manager.processTick("EURUSD", candle(1.1000, 1.1010, 1.0990, 1.1000)).isEmpty())
        assertEquals(1.0950, manager.getPendingOrders().single().stopPrice!!, 1e-12)

        // Rally: never trades down to 1.0950, stop ratchets to 1.1100 - 0.0050.
        assertTrue(manager.processTick("EURUSD", candle(1.1000, 1.1100, 1.1000, 1.1090)).isEmpty())
        assertEquals(1.1050, manager.getPendingOrders().single().stopPrice!!, 1e-12)

        val filled = manager.processTick("EURUSD", candle(1.1080, 1.1085, 1.1040, 1.1045))
        assertEquals(1.1050, filled.single().filledPrice!!, 1e-12)
    }

    @Test
    fun `a trailing buy stop trails above price`() {
        manager.placeTrailingStop("EURUSD", Direction.BULLISH, 0.1, trailingDistance = 0.0050)
        manager.processTick("EURUSD", candle(1.1000, 1.1010, 1.0990, 1.1000))
        assertEquals(1.1050, manager.getPendingOrders().single().stopPrice!!, 1e-12)

        manager.processTick("EURUSD", candle(1.1000, 1.1000, 1.0900, 1.0910))
        assertEquals(1.0950, manager.getPendingOrders().single().stopPrice!!, 1e-12)
    }

    @Test
    fun `bracket exits wait for the entry and then cancel each other`() {
        val bracket = manager.placeBracketOrder(
            "EURUSD", Direction.BULLISH, 0.1,
            entryPrice = 1.0900, takeProfitPrice = 1.1100, stopLossPrice = 1.0800,
        )

        // Touches the target without ever reaching the entry: nothing may fill.
        assertTrue(manager.processTick("EURUSD", candle(1.1000, 1.1120, 1.0950, 1.1100)).isEmpty())

        // Entry fills; the exits are not armed on the same bar.
        val entryBar = manager.processTick("EURUSD", candle(1.0950, 1.1150, 1.0890, 1.0900))
        assertEquals(listOf(bracket.entryOrder.id), entryBar.map { it.id })

        val exitBar = manager.processTick("EURUSD", candle(1.0900, 1.1150, 1.0880, 1.1120))
        assertEquals(listOf(bracket.takeProfitOrder.id), exitBar.map { it.id })
        val sl = manager.getAllOrders().single { it.id == bracket.stopLossOrder.id }
        assertEquals(OrderStatus.CANCELLED, sl.status)
    }

    @Test
    fun `cancelling an unfilled bracket entry cancels its exits`() {
        val bracket = manager.placeBracketOrder(
            "EURUSD", Direction.BULLISH, 0.1,
            entryPrice = 1.0900, takeProfitPrice = 1.1100, stopLossPrice = 1.0800,
        )
        assertTrue(manager.cancelOrder(bracket.entryOrder.id))
        assertTrue(manager.getPendingOrders().isEmpty())
    }
}
