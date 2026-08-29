package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePool
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSweep
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Step 2 — location. Nothing is traded that did not happen at known liquidity.
 *
 * Three pools, each a place where stop orders are known to rest: the previous
 * day's high and low, the Asian session's high and low, and confirmed major
 * swings. A move that reverses in open space is a move with no explanation; a
 * move that reverses immediately after taking one of these has one.
 *
 * Every pool records the bar it became knowable, and it is only offered from
 * that bar onward. The previous day's high is not knowable until the day has
 * ended; the Asian high is not knowable until the session has closed. Marking
 * either one earlier would let a level formed by future bars validate a past
 * sweep, which is the exact repaint this engine is built to avoid.
 */
class KeystoneLiquidity {

    /**
     * Every pool in the series, each stamped with the bar it became knowable.
     *
     * Computed once for the whole series rather than per bar. That is safe
     * precisely because of the stamp: [activeAt] never returns a pool whose
     * forming period had not finished by the bar being evaluated.
     */
    fun pools(candles: List<Candle>, config: KeystoneConfig): List<KeystonePool> {
        val result = ArrayList<KeystonePool>()
        if (KeystoneLiquiditySource.PREVIOUS_DAY in config.liquiditySources) {
            result += dailyPools(candles)
        }
        if (KeystoneLiquiditySource.ASIAN_RANGE in config.liquiditySources) {
            result += asianPools(candles)
        }
        if (KeystoneLiquiditySource.MAJOR_SWING in config.liquiditySources) {
            result += swingPools(candles, config)
        }
        return result.sortedBy { it.formedIndex }
    }

    /** Pools knowable at [index], still young enough, and not already taken. */
    fun activeAt(
        pools: List<KeystonePool>,
        index: Int,
        consumed: List<Pair<Boolean, Double>>,
        config: KeystoneConfig,
    ): List<KeystonePool> = pools.filter { pool ->
        pool.formedIndex < index &&
            index - pool.formedIndex <= config.maxPoolAgeBars &&
            consumed.none { (side, price) ->
                side == pool.aboveMarket && samePool(price, pool.price, config)
            }
    }

    /**
     * The sweep confirmed by the closed bar at [index], if any.
     *
     * Both halves are required on the same closed bar: the wick has to trade
     * through the pool, and the close has to come back on the near side of it.
     * A bar that takes the level and closes beyond it has not swept anything —
     * it has broken through, which is the opposite trade.
     */
    fun sweepAt(
        candles: List<Candle>,
        index: Int,
        pools: List<KeystonePool>,
        atr: Double,
        config: KeystoneConfig,
    ): KeystoneSweep? {
        if (index !in candles.indices || atr <= 0.0) return null
        val bar = candles[index]
        val minimum = config.minSweepPenetrationAtr * atr

        var best: KeystoneSweep? = null
        for (pool in pools) {
            if (pool.aboveMarket) {
                val penetration = bar.high - pool.price
                if (penetration < minimum) continue
                if (bar.close >= pool.price) continue
                val sweep = KeystoneSweep(
                    pool = pool,
                    index = index,
                    timestamp = bar.timestamp,
                    extreme = bar.high,
                    direction = Direction.BEARISH,
                    penetrationAtr = penetration / atr,
                )
                if (best == null || sweep.penetrationAtr > best.penetrationAtr) best = sweep
            } else {
                val penetration = pool.price - bar.low
                if (penetration < minimum) continue
                if (bar.close <= pool.price) continue
                val sweep = KeystoneSweep(
                    pool = pool,
                    index = index,
                    timestamp = bar.timestamp,
                    extreme = bar.low,
                    direction = Direction.BULLISH,
                    penetrationAtr = penetration / atr,
                )
                if (best == null || sweep.penetrationAtr > best.penetrationAtr) best = sweep
            }
        }
        return best
    }

    /**
     * The nearest pool on the opposite side of [from], for use as a target.
     *
     * Only pools already knowable at [index] are eligible, so the exit is drawn
     * from liquidity the market could actually see when the trade was taken.
     */
    fun opposingPool(
        pools: List<KeystonePool>,
        index: Int,
        from: Double,
        direction: Direction,
        config: KeystoneConfig,
    ): KeystonePool? = pools
        .filter { it.formedIndex < index && index - it.formedIndex <= config.maxPoolAgeBars }
        .filter { if (direction == Direction.BULLISH) it.price > from else it.price < from }
        .minByOrNull { abs(it.price - from) }

    private fun samePool(a: Double, b: Double, config: KeystoneConfig): Boolean =
        abs(a - b) <= abs(b) * config.poolClusterFraction

    /**
     * Previous-day high and low, knowable from the first bar of the next day.
     */
    private fun dailyPools(candles: List<Candle>): List<KeystonePool> {
        val result = ArrayList<KeystonePool>()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        var dayKey = -1
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY

        for (i in candles.indices) {
            calendar.timeInMillis = candles[i].timestamp
            val key = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
            if (dayKey == -1) {
                dayKey = key
            } else if (key != dayKey) {
                if (high > Double.NEGATIVE_INFINITY) {
                    // Knowable at i: the day that produced it has just closed.
                    result += KeystonePool(
                        KeystoneLiquiditySource.PREVIOUS_DAY, high, true, i, "PDH",
                    )
                    result += KeystonePool(
                        KeystoneLiquiditySource.PREVIOUS_DAY, low, false, i, "PDL",
                    )
                }
                dayKey = key
                high = Double.NEGATIVE_INFINITY
                low = Double.POSITIVE_INFINITY
            }
            high = maxOf(high, candles[i].high)
            low = minOf(low, candles[i].low)
        }
        return result
    }

    /** Asian session high and low, knowable from the first bar after it closes. */
    private fun asianPools(candles: List<Candle>): List<KeystonePool> {
        val result = ArrayList<KeystonePool>()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        var open = false
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY

        for (i in candles.indices) {
            calendar.timeInMillis = candles[i].timestamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val inside = KeystoneSession.ASIA.contains(hour)
            if (inside) {
                open = true
                high = maxOf(high, candles[i].high)
                low = minOf(low, candles[i].low)
            } else if (open) {
                result += KeystonePool(KeystoneLiquiditySource.ASIAN_RANGE, high, true, i, "Asian high")
                result += KeystonePool(KeystoneLiquiditySource.ASIAN_RANGE, low, false, i, "Asian low")
                open = false
                high = Double.NEGATIVE_INFINITY
                low = Double.POSITIVE_INFINITY
            }
        }
        return result
    }

    /** Confirmed swing highs and lows, knowable once the right side has formed. */
    private fun swingPools(candles: List<Candle>, config: KeystoneConfig): List<KeystonePool> {
        val left = config.swingLeft
        val right = config.swingRight
        if (candles.size < left + right + 1) return emptyList()

        val result = ArrayList<KeystonePool>()
        for (i in left until candles.size - right) {
            if (isSwingHigh(candles, i, left, right)) {
                result += KeystonePool(
                    KeystoneLiquiditySource.MAJOR_SWING, candles[i].high, true, i + right, "Swing high",
                )
            }
            if (isSwingLow(candles, i, left, right)) {
                result += KeystonePool(
                    KeystoneLiquiditySource.MAJOR_SWING, candles[i].low, false, i + right, "Swing low",
                )
            }
        }
        return result
    }

    private fun isSwingHigh(c: List<Candle>, i: Int, left: Int, right: Int): Boolean {
        val h = c[i].high
        for (j in 1..left) if (h <= c[i - j].high) return false
        for (j in 1..right) if (h < c[i + j].high) return false
        return true
    }

    private fun isSwingLow(c: List<Candle>, i: Int, left: Int, right: Int): Boolean {
        val l = c[i].low
        for (j in 1..left) if (l >= c[i - j].low) return false
        for (j in 1..right) if (l > c[i + j].low) return false
        return true
    }
}
