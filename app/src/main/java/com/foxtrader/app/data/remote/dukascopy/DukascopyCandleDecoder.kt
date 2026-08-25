package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.Candle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/** Decodes Dukascopy's delta-compressed free candle API response. */
@Singleton
class DukascopyCandleDecoder @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(payload: String): List<Candle> {
        val root = json.parseToJsonElement(payload).jsonObject
        val times = root.longArray("times")
        if (times.isEmpty()) return emptyList()

        val opens = root.longArray("opens")
        val highs = root.longArray("highs")
        val lows = root.longArray("lows")
        val closes = root.longArray("closes")
        val volumes = root.doubleArray("volumes")
        require(listOf(opens, highs, lows, closes, volumes).all { it.size == times.size }) {
            "Dukascopy candle columns have mismatched lengths."
        }

        var timestamp = root.requiredLong("timestamp")
        val shift = root.requiredLong("shift")
        val multiplier = root.requiredDouble("multiplier")
        require(timestamp > 0L && shift > 0L && multiplier.isFinite() && multiplier > 0.0) {
            "Dukascopy candle metadata is invalid."
        }

        var openUnits = (root.requiredDouble("open") / multiplier).roundToLong()
        var highUnits = (root.requiredDouble("high") / multiplier).roundToLong()
        var lowUnits = (root.requiredDouble("low") / multiplier).roundToLong()
        var closeUnits = (root.requiredDouble("close") / multiplier).roundToLong()
        val candles = ArrayList<Candle>(times.size)

        times.indices.forEach { index ->
            val delta = times[index]
            require(delta >= 0L) { "Dukascopy candle time delta is negative." }
            timestamp = Math.addExact(timestamp, Math.multiplyExact(delta, shift))
            openUnits = Math.addExact(openUnits, opens[index])
            highUnits = Math.addExact(highUnits, highs[index])
            lowUnits = Math.addExact(lowUnits, lows[index])
            closeUnits = Math.addExact(closeUnits, closes[index])
            val candle = Candle(
                timestamp = timestamp,
                open = openUnits * multiplier,
                high = highUnits * multiplier,
                low = lowUnits * multiplier,
                close = closeUnits * multiplier,
                volume = volumes[index],
            )
            require(candle.isValid()) { "Dukascopy returned an invalid OHLCV candle." }
            candles += candle
        }
        return candles
    }

    private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: error("Dukascopy response is missing $name.")

    private fun kotlinx.serialization.json.JsonObject.requiredDouble(name: String): Double =
        get(name)?.jsonPrimitive?.doubleOrNull ?: error("Dukascopy response is missing $name.")

    private fun kotlinx.serialization.json.JsonObject.longArray(name: String): List<Long> =
        get(name)?.jsonArray?.map { it.jsonPrimitive.longOrNull ?: error("Invalid $name value.") }
            ?: error("Dukascopy response is missing $name.")

    private fun kotlinx.serialization.json.JsonObject.doubleArray(name: String): List<Double> =
        get(name)?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: error("Invalid $name value.") }
            ?: error("Dukascopy response is missing $name.")

    private fun Candle.isValid(): Boolean =
        timestamp > 0L && open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
            volume.isFinite() && open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 &&
            volume >= 0.0 && high >= low && open in low..high && close in low..high
}
