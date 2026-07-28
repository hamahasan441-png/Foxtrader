package com.foxtrader.app.data.market.decode

import com.foxtrader.app.data.market.model.TickSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frame parsing must be exact for real ticks and forgiving for everything else:
 * a heartbeat, an ack, or a malformed frame must never crash the feed — it just
 * yields null.
 */
class JsonTickDecoderTest {

    private val decoder = JsonTickDecoder.binanceAggTrade()

    @Test
    fun `decodes a Binance aggTrade with string-typed numbers`() {
        val frame = """{"e":"aggTrade","s":"BTCUSDT","p":"100.5","q":"0.25","T":1704067200000,"m":false}"""
        val tick = decoder.decode(frame)!!
        assertEquals("BTCUSDT", tick.symbol)
        assertEquals(100.5, tick.price, 0.0)
        assertEquals(0.25, tick.quantity, 0.0)
        assertEquals(1704067200000L, tick.timestamp)
        assertEquals(TickSide.BUY, tick.side) // m=false -> buyer is the aggressor
    }

    @Test
    fun `m = true maps to a sell aggressor`() {
        val frame = """{"e":"aggTrade","s":"BTCUSDT","p":"100.5","q":"0.25","T":1704067200000,"m":true}"""
        assertEquals(TickSide.SELL, decoder.decode(frame)!!.side)
    }

    @Test
    fun `decodes numeric-typed fields and tolerates a missing side`() {
        val frame = """{"e":"aggTrade","s":"ETHUSDT","p":2000.0,"q":1.5,"T":1704067200001}"""
        val tick = decoder.decode(frame)!!
        assertEquals(2000.0, tick.price, 0.0)
        assertEquals(1.5, tick.quantity, 0.0)
        assertEquals(TickSide.UNKNOWN, tick.side)
    }

    @Test
    fun `ignores frames of a different event type`() {
        assertNull(decoder.decode("""{"e":"pong","s":"BTCUSDT","p":"1","q":"1","T":1}"""))
    }

    @Test
    fun `ignores malformed JSON instead of throwing`() {
        assertNull(decoder.decode("this is not json"))
        assertNull(decoder.decode(""))
        assertNull(decoder.decode("[1,2,3]")) // array, not an object
    }

    @Test
    fun `requires price, timestamp and a resolvable symbol`() {
        assertNull(decoder.decode("""{"e":"aggTrade","s":"BTCUSDT","q":"1","T":1}"""))        // no price
        assertNull(decoder.decode("""{"e":"aggTrade","s":"BTCUSDT","p":"1","q":"1"}"""))       // no time
        assertNull(decoder.decode("""{"e":"aggTrade","p":"1","q":"1","T":1}"""))               // no symbol, no fallback
    }

    @Test
    fun `falls back to a configured symbol when the frame omits it`() {
        val dec = JsonTickDecoder(
            TickFieldMapping(symbol = "absent", fallbackSymbol = "XAUUSD", side = null, eventTypeKey = null),
        )
        val tick = dec.decode("""{"p":"2030.5","q":"1","T":1704067200000}""")!!
        assertEquals("XAUUSD", tick.symbol)
        assertEquals(TickSide.UNKNOWN, tick.side)
    }

    @Test
    fun `a custom mapping parses a non-Binance shape`() {
        val dec = JsonTickDecoder(
            TickFieldMapping(
                symbol = "instrument",
                price = "price",
                quantity = "volume",
                timestamp = "time",
                side = null,
                eventTypeKey = null,
            ),
        )
        val tick = dec.decode("""{"instrument":"EURUSD","price":1.085,"volume":100000,"time":1704067200000}""")!!
        assertEquals("EURUSD", tick.symbol)
        assertEquals(1.085, tick.price, 0.0)
        assertEquals(100000.0, tick.quantity, 0.0)
    }

    @Test
    fun `side polarity is configurable`() {
        val dec = JsonTickDecoder(
            TickFieldMapping(side = "isBuyerMaker", makerMeansSell = false, eventTypeKey = null),
        )
        // makerMeansSell=false -> isBuyerMaker=true means the buyer aggressed.
        assertEquals(TickSide.BUY, dec.decode("""{"s":"S","p":"1","q":"1","T":1,"isBuyerMaker":true}""")!!.side)
        assertEquals(TickSide.SELL, dec.decode("""{"s":"S","p":"1","q":"1","T":1,"isBuyerMaker":false}""")!!.side)
    }
}
