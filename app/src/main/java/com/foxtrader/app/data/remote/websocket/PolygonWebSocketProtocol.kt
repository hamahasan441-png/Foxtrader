package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.api.PolygonTicker
import com.foxtrader.app.domain.model.Candle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Pure command and payload codec for Polygon's authenticated WebSocket API. */
internal object PolygonWebSocketProtocol {

    sealed interface Message {
        data object Authenticated : Message
        data class AuthFailed(val reason: String) : Message
        data class Aggregate(val ticker: String, val candle: Candle) : Message
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun authMessage(apiKey: String): String = buildJsonObject {
        put("action", "auth")
        put("params", apiKey)
    }.toString()

    fun subscriptionMessage(action: String, topics: Collection<String>): String {
        require(action == "subscribe" || action == "unsubscribe") {
            "Polygon WebSocket action must be subscribe or unsubscribe."
        }
        require(topics.isNotEmpty()) { "Polygon WebSocket topics must not be empty." }
        return buildJsonObject {
            put("action", action)
            put("params", topics.joinToString(","))
        }.toString()
    }

    fun parse(text: String): List<Message> = runCatching {
        val root = json.parseToJsonElement(text)
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> return@runCatching emptyList()
        }
        elements.mapNotNull(::parseObject)
    }.getOrDefault(emptyList())

    private fun parseObject(element: JsonElement): Message? {
        val item = element as? JsonObject ?: return null
        val event = item["ev"]?.jsonPrimitive?.contentOrNull ?: return null
        if (event == "status") {
            return when (item["status"]?.jsonPrimitive?.contentOrNull) {
                "auth_success" -> Message.Authenticated
                "auth_failed", "auth_failed_invalid_key" -> Message.AuthFailed(
                    item["message"]?.jsonPrimitive?.contentOrNull ?: "Polygon authentication failed",
                )
                else -> null
            }
        }

        if (event !in AGGREGATE_EVENTS) return null
        val rawTicker = item["sym"]?.jsonPrimitive?.contentOrNull
            ?: item["pair"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val timestamp = item["s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return null
        val open = item["o"]?.jsonPrimitive?.doubleOrNull ?: return null
        val high = item["h"]?.jsonPrimitive?.doubleOrNull ?: return null
        val low = item["l"]?.jsonPrimitive?.doubleOrNull ?: return null
        val close = item["c"]?.jsonPrimitive?.doubleOrNull ?: return null
        val volume = item["v"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (!listOf(open, high, low, close, volume).all { it.isFinite() }) return null

        return Message.Aggregate(
            ticker = PolygonTicker.normalize(rawTicker),
            candle = Candle(
                timestamp = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
            ),
        )
    }

    private val AGGREGATE_EVENTS = setOf("AM", "CA", "XA")
}
