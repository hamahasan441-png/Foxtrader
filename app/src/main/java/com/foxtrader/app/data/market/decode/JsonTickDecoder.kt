package com.foxtrader.app.data.market.decode

import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.data.market.model.TickSide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Which JSON keys carry the tick fields. Defaults match Binance's `aggTrade`
 * stream message; reconfigure for other feeds without touching the parser.
 *
 * @param symbol            key for the instrument symbol.
 * @param price             key for the trade price (number or numeric string).
 * @param quantity          key for the trade size (number or numeric string).
 * @param timestamp         key for the trade time in epoch millis.
 * @param side              optional key holding a boolean "is-maker" flag.
 * @param makerMeansSell    when [side] is present, whether `true` denotes a sell
 *                          aggressor. Binance's `m` (is-buyer-the-maker) is
 *                          `true` when the aggressor sold, so this defaults true.
 * @param fallbackSymbol    used when [symbol] is absent (some feeds send the
 *                          symbol only in the subscription, not every frame).
 * @param eventTypeKey      if set, frames whose [eventTypeKey] != [eventTypeValue]
 *                          are ignored (filters heartbeats/acks out of a shared
 *                          stream).
 * @param eventTypeValue    required value of [eventTypeKey] for a frame to parse.
 */
data class TickFieldMapping(
    val symbol: String = "s",
    val price: String = "p",
    val quantity: String = "q",
    val timestamp: String = "T",
    val side: String? = "m",
    val makerMeansSell: Boolean = true,
    val fallbackSymbol: String? = null,
    val eventTypeKey: String? = "e",
    val eventTypeValue: String? = "aggTrade",
)

/**
 * A [TickDecoder] for JSON trade frames, driven by a [TickFieldMapping].
 *
 * Numbers are read from the primitive's raw `content` and parsed with the stdlib
 * `toXOrNull()` helpers, so both `"100.5"` (Binance sends prices as strings) and
 * `100.5` parse identically, and an unexpected element shape never throws. Any
 * frame that is not a JSON object, fails the event-type filter, or is missing a
 * required field (price, timestamp, resolvable symbol) yields `null` rather than
 * throwing — a malformed frame must never crash the feed.
 */
class JsonTickDecoder(
    private val mapping: TickFieldMapping = TickFieldMapping(),
    private val json: Json = LenientJson,
) : TickDecoder {

    override fun decode(frame: String): Tick? {
        // Only the parse + object-cast can throw (malformed JSON, or a frame that
        // is not a JSON object). Everything else uses the null-safe readers below.
        val obj = try {
            json.parseToJsonElement(frame).jsonObject
        } catch (expected: Exception) {
            return null
        }

        val typeKey = mapping.eventTypeKey
        val typeValue = mapping.eventTypeValue
        if (typeKey != null && typeValue != null) {
            if (obj[typeKey].textOrNull() != typeValue) return null
        }

        val price = obj[mapping.price].doubleSafe() ?: return null
        val timestamp = obj[mapping.timestamp].longSafe() ?: return null
        val quantity = obj[mapping.quantity].doubleSafe() ?: 0.0
        val symbol = obj[mapping.symbol].textOrNull()
            ?: mapping.fallbackSymbol
            ?: return null
        if (symbol.isEmpty()) return null

        val sideKey = mapping.side
        val isMaker = if (sideKey != null) obj[sideKey].boolSafe() else null

        return Tick(
            symbol = symbol,
            price = price,
            quantity = quantity,
            timestamp = timestamp,
            side = decodeSide(isMaker),
        )
    }

    private fun decodeSide(isMaker: Boolean?): TickSide = when {
        isMaker == null || mapping.side == null -> TickSide.UNKNOWN
        mapping.makerMeansSell -> if (isMaker) TickSide.SELL else TickSide.BUY
        else -> if (isMaker) TickSide.BUY else TickSide.SELL
    }

    // Read the raw text of any primitive (string OR number) and parse it. Using
    // the stdlib `content` + `toXOrNull()` (rather than kotlinx's typed accessors)
    // makes string-typed and number-typed fields behave identically and guarantees
    // a null instead of a throw on an unexpected shape or a missing key.
    private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.content
    private fun JsonElement?.doubleSafe(): Double? =
        (this as? JsonPrimitive)?.content?.toDoubleOrNull()
    private fun JsonElement?.longSafe(): Long? =
        (this as? JsonPrimitive)?.content?.toLongOrNull()
    private fun JsonElement?.boolSafe(): Boolean? =
        // No stdlib `toBooleanOrNull`; `toBooleanStrictOrNull` parses exactly
        // "true"/"false" (what a JSON boolean primitive's content always is).
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    companion object {
        private val LenientJson = Json { ignoreUnknownKeys = true }

        /** A decoder preconfigured for Binance's `aggTrade` stream. */
        fun binanceAggTrade(): JsonTickDecoder = JsonTickDecoder(TickFieldMapping())
    }
}
