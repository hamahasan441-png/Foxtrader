package com.foxtrader.app.data.remote.websocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol tests keep WebSocket authentication and provider payload parsing
 * deterministic without opening a network socket or requiring Android.
 */
class PolygonWebSocketProtocolTest {

    @Test
    fun `auth message contains the key only in the required params field`() {
        val root = Json.parseToJsonElement(
            PolygonWebSocketProtocol.authMessage("polygon-key"),
        ).jsonObject

        assertEquals("auth", root["action"]?.jsonPrimitive?.content)
        assertEquals("polygon-key", root["params"]?.jsonPrimitive?.content)
    }

    @Test
    fun `subscription message joins topics in one provider command`() {
        val root = Json.parseToJsonElement(
            PolygonWebSocketProtocol.subscriptionMessage(
                action = "subscribe",
                topics = listOf("AM.AAPL", "AM.MSFT"),
            ),
        ).jsonObject

        assertEquals("subscribe", root["action"]?.jsonPrimitive?.content)
        assertEquals("AM.AAPL,AM.MSFT", root["params"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse recognizes authentication and stock aggregate messages`() {
        val messages = PolygonWebSocketProtocol.parse(
            """
            [
              {"ev":"status","status":"auth_success","message":"authenticated"},
              {"ev":"AM","sym":"AAPL","o":100,"h":105,"l":99,"c":104,"v":1200,"s":60000,"e":119999}
            ]
            """.trimIndent(),
        )

        assertTrue(messages[0] is PolygonWebSocketProtocol.Message.Authenticated)
        val aggregate = messages[1] as PolygonWebSocketProtocol.Message.Aggregate
        assertEquals("AAPL", aggregate.ticker)
        assertEquals(104.0, aggregate.candle.close, 0.0)
        assertEquals(1200.0, aggregate.candle.volume, 0.0)
    }

    @Test
    fun `parse normalizes forex and crypto pair payloads`() {
        val messages = PolygonWebSocketProtocol.parse(
            """
            [
              {"ev":"CA","pair":"EUR/USD","o":1.1,"h":1.2,"l":1.0,"c":1.15,"s":60000},
              {"ev":"XA","pair":"DOGE-USDT","o":0.1,"h":0.2,"l":0.09,"c":0.15,"s":120000}
            ]
            """.trimIndent(),
        )

        assertEquals("C:EURUSD", (messages[0] as PolygonWebSocketProtocol.Message.Aggregate).ticker)
        assertEquals("X:DOGEUSD", (messages[1] as PolygonWebSocketProtocol.Message.Aggregate).ticker)
    }

    @Test
    fun `parse drops malformed and unrelated events`() {
        val messages = PolygonWebSocketProtocol.parse(
            """
            [
              {"ev":"status","status":"connected"},
              {"ev":"AM","sym":"AAPL","o":100,"h":105,"c":104,"s":60000},
              {"ev":"T","sym":"AAPL","p":104,"t":60000}
            ]
            """.trimIndent(),
        )

        assertTrue(messages.isEmpty())
    }
}
