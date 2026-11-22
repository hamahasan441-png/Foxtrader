package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.sdk.indicator.IndicatorSignal
import com.foxtrader.app.domain.sdk.indicator.SignalType
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Asia Sweep Reclaim.
 *
 * The Asia range is the high and low of closed bars from 00:00 to 07:00 UTC.
 * A marker prints only on a closed London or New York bar (07:00–16:00 UTC)
 * that wicks through that range and closes back inside. One signal per side
 * per UTC day. The marker price does not change after that bar has closed.
 */
class AsiaSweepReclaimIndicator : Indicator {
    override val id = "asia_sweep_reclaim"
    override val displayName = "Asia Sweep Reclaim"
    override val description =
        "Non-repainting Asia liquidity sweep and reclaim. London and New York only, closed bars only."
    override val isOverlay = true
    override val version = "1.0.0"

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        if (candles.size < 20) return IndicatorResult(emptyMap())
        val pip = params["pip"]?.takeIf { it > 0.0 } ?: inferPip(candles.first().close)
        val bufferPips = params["bufferPips"] ?: 0.8
        val minBodyAtr = params["minBodyAtr"] ?: 0.35
        val asiaHigh = DoubleArray(candles.size) { Double.NaN }
        val asiaLow = DoubleArray(candles.size) { Double.NaN }
        val signals = ArrayList<IndicatorSignal>()
        val used = HashSet<String>()

        for (i in 16 until candles.size) {
            val bar = candles[i]
            val asia = asiaBefore(candles, i) ?: continue
            asiaHigh[i] = asia.high
            asiaLow[i] = asia.low
            val hour = utcHour(bar.timestamp)
            if (hour < 7 || hour >= 16) continue
            val atr = atr(candles, i)
            val hit = classify(bar, asia, atr, pip, bufferPips, minBodyAtr) ?: continue
            val key = "${utcDay(bar.timestamp)}:${hit.side}"
            if (!used.add(key)) continue
            val buf = bufferPips * pip
            val entry = bar.close
            val stop = if (hit.side == "short") hit.extreme + buf else hit.extreme - buf
            val risk = abs(entry - stop)
            if (risk < pip * 0.4 || (atr > 0.0 && risk > atr * 5.0)) {
                used.remove(key)
                continue
            }
            signals += IndicatorSignal(
                index = i,
                type = if (hit.side == "long") SignalType.BUY else SignalType.SELL,
                price = entry,
                label = "Asia reclaim",
            )
        }
        return IndicatorResult(
            series = mapOf("asiaHigh" to asiaHigh, "asiaLow" to asiaLow),
            signals = signals,
        )
    }

    private data class Asia(val high: Double, val low: Double)
    private data class Hit(val side: String, val extreme: Double)

    private fun inferPip(price: Double) = when {
        price >= 500.0 -> 0.1
        price >= 20.0 -> 0.01
        else -> 0.0001
    }

    private fun utcHour(timestamp: Long) =
        Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).hour

    private fun utcDay(timestamp: Long) =
        Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun atr(candles: List<Candle>, i: Int, n: Int = 14): Double {
        val from = max(1, i - n)
        var sum = 0.0
        var count = 0
        for (j in from until i) {
            val prev = candles[j - 1].close
            val bar = candles[j]
            sum += max(bar.high - bar.low, max(abs(bar.high - prev), abs(bar.low - prev)))
            count += 1
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun asiaBefore(candles: List<Candle>, i: Int): Asia? {
        val day = utcDay(candles[i].timestamp)
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        var count = 0
        for (j in i - 1 downTo 0) {
            val bar = candles[j]
            if (utcDay(bar.timestamp) != day) break
            if (utcHour(bar.timestamp) >= 7) continue
            high = max(high, bar.high)
            low = min(low, bar.low)
            count += 1
        }
        if (count < 4 || high <= low) return null
        return Asia(high, low)
    }

    private fun classify(
        bar: Candle,
        asia: Asia,
        atr: Double,
        pip: Double,
        bufferPips: Double,
        minBodyAtr: Double,
    ): Hit? {
        if (asia.high - asia.low < pip * 4) return null
        val buf = bufferPips * pip
        val body = abs(bar.close - bar.open)
        if (atr <= 0.0 || body < minBodyAtr * atr) return null
        if (bar.high > asia.high + buf && bar.close < asia.high && bar.close < bar.open) {
            return Hit("short", bar.high)
        }
        if (bar.low < asia.low - buf && bar.close > asia.low && bar.close > bar.open) {
            return Hit("long", bar.low)
        }
        return null
    }
}
