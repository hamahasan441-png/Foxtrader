package com.foxtrader.app.data.repository

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deterministic synthetic candle generator.
 * Seeds the cache when the backend is unreachable so the chart is always
 * functional offline. Produces realistic-looking OHLC with drift + volatility.
 */
object SampleData {

    fun generate(symbol: String, timeframe: Timeframe, count: Int): List<Candle> {
        val rng = Random(symbol.hashCode().toLong())
        val candles = ArrayList<Candle>(count)
        var price = seedPrice(symbol)
        val stepMs = timeframe.minutes * 60_000L
        val baseTime = System.currentTimeMillis() - count * stepMs

        for (i in 0 until count) {
            val volatility = price * 0.0008
            val change = (rng.nextDouble() - 0.48) * volatility * 2
            val open = price
            val close = price + change
            val wick = rng.nextDouble() * volatility
            val high = maxOf(open, close) + wick
            val low = minOf(open, close) - wick
            val volume = rng.nextDouble() * 800 + 200
            candles += Candle(baseTime + i * stepMs, open, high, low, close, abs(volume))
            price = close
        }
        return candles
    }

    private fun seedPrice(symbol: String): Double = when {
        symbol.startsWith("BTC") -> 62_000.0
        symbol.startsWith("ETH") -> 3_400.0
        symbol.startsWith("XAU") -> 2_350.0
        symbol.contains("JPY") -> 155.0
        else -> 1.10000
    }
}
