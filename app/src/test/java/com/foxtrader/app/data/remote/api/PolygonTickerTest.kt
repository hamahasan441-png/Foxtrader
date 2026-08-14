package com.foxtrader.app.data.remote.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the shared Polygon ticker seam used by both REST history and live
 * WebSocket subscriptions. A drift here would make the chart load one asset
 * and stream a different one.
 */
class PolygonTickerTest {

    @Test
    fun `normalizes asset classes to Polygon prefixes`() {
        assertEquals("C:EURUSD", PolygonTicker.normalize("eur/usd"))
        assertEquals("X:BTCUSD", PolygonTicker.normalize("btc/usdt"))
        assertEquals("X:DOGEUSD", PolygonTicker.normalize("DOGE-USDT"))
        assertEquals("I:SPX", PolygonTicker.normalize("US500"))
        assertEquals("AAPL", PolygonTicker.normalize(" aapl "))
    }

    @Test
    fun `subscription symbols use Polygon pair separators`() {
        assertEquals(
            "EUR/USD",
            PolygonTicker.subscriptionSymbol("C:EURUSD", PolygonMarket.FOREX),
        )
        assertEquals(
            "DOGE-USD",
            PolygonTicker.subscriptionSymbol("X:DOGEUSD", PolygonMarket.CRYPTO),
        )
        assertEquals("I:SPX", PolygonTicker.subscriptionSymbol("I:SPX", PolygonMarket.INDICES))
    }

    @Test
    fun `market cluster follows the canonical prefix`() {
        assertEquals(PolygonMarket.FOREX, PolygonTicker.market("C:EURUSD"))
        assertEquals(PolygonMarket.CRYPTO, PolygonTicker.market("X:BTCUSD"))
        assertEquals(PolygonMarket.INDICES, PolygonTicker.market("I:SPX"))
        assertEquals(PolygonMarket.STOCKS, PolygonTicker.market("AAPL"))
    }
}
