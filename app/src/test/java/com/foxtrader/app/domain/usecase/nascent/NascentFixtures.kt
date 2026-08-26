package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle

/**
 * Deterministic candle fixtures for the Nascent golden dataset.
 *
 * Every builder produces a fully specified series — no randomness, no clock —
 * so a failing assertion always points at the engine rather than at the data.
 * Timestamps start on an exact UTC day boundary and advance by one M5 bar, so
 * the M5 -> H1 external mapping resolves cleanly.
 */
object NascentFixtures {

    const val M5_MILLIS = 5L * 60_000L
    const val START_TIME = 1_700_006_400_000L // exact UTC day boundary

    fun candle(
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 1_000.0,
    ): Candle = Candle(timestamp, open, high, low, close, volume)

    /**
     * Builds a series from directional legs.
     *
     * Each leg walks [bars] candles from the running price to [to], printing
     * bodies in the direction of travel with small symmetric wicks. Legs are the
     * unit the Nascent structure engine actually reasons about, so describing a
     * fixture as legs keeps the intent of each test readable.
     */
    class SeriesBuilder(startPrice: Double = 100.0) {
        private val candles = mutableListOf<Candle>()
        private var time = START_TIME
        private var price = startPrice

        fun leg(to: Double, bars: Int, wick: Double = 0.05): SeriesBuilder {
            require(bars >= 1)
            val step = (to - price) / bars
            repeat(bars) {
                val open = price
                val close = open + step
                val high = maxOf(open, close) + wick
                val low = minOf(open, close) - wick
                candles += candle(time, open, high, low, close)
                time += M5_MILLIS
                price = close
            }
            return this
        }

        /** A single candle that spikes beyond [extreme] and closes at [close]. */
        fun spike(extreme: Double, close: Double, wick: Double = 0.05): SeriesBuilder {
            val open = price
            val high = maxOf(open, close, extreme) + wick
            val low = minOf(open, close, extreme) - wick
            candles += candle(time, open, high, low, close)
            time += M5_MILLIS
            price = close
            return this
        }

        /** Flat filler so an external bucket can complete without new structure. */
        fun drift(bars: Int, amplitude: Double = 0.08): SeriesBuilder {
            repeat(bars) { i ->
                val open = price
                val close = if (i % 2 == 0) open + amplitude else open - amplitude
                candles += candle(time, open, maxOf(open, close) + 0.03, minOf(open, close) - 0.03, close)
                time += M5_MILLIS
                price = close
            }
            return this
        }

        /** A fully specified candle, for the exact bar a test cares about. */
        fun bar(open: Double, high: Double, low: Double, close: Double): SeriesBuilder {
            candles += candle(time, open, high, low, close)
            time += M5_MILLIS
            price = close
            return this
        }

        fun build(): List<Candle> = candles.toList()

        fun nextTimestamp(): Long = time

        fun lastPrice(): Double = price
    }

    /**
     * A long, structurally rich series.
     *
     * Leg lengths are sized against the **external** timeframe, not the chart
     * one. With an M5 internal series the external mapping is H1, so twelve M5
     * bars collapse into a single H1 bar; a leg has to span roughly five H1 bars
     * before it can carry a confirmed external pivot at all. Legs of 60-84 M5
     * bars give the external pass genuine alternation to work with, which is
     * what a real chart would supply and what the liquidity cycle needs.
     */
    fun richSeries(): List<Candle> = SeriesBuilder(100.0)
        .leg(to = 104.0, bars = 72)
        .leg(to = 101.5, bars = 60)
        .leg(to = 107.0, bars = 84)
        .leg(to = 103.0, bars = 60)
        .leg(to = 110.0, bars = 84)
        .leg(to = 105.5, bars = 72)
        .leg(to = 112.0, bars = 72)
        .leg(to = 106.0, bars = 84)
        .leg(to = 109.5, bars = 60)
        .leg(to = 102.0, bars = 84)
        .leg(to = 106.5, bars = 60)
        .leg(to = 99.0, bars = 84)
        .leg(to = 103.5, bars = 60)
        .leg(to = 96.0, bars = 84)
        .drift(bars = 36)
        .build()

    /**
     * `nascent_msu1_bear` — the golden MSU Type 1 bearish continuation.
     *
     * Internal pivots are laid out to hit the Type 1 geometry exactly:
     * ```
     *   H0 = 110   protected high, must survive
     *   L0 = 104
     *   H1 = 107   previous internal high
     *   L1 = 101   new lower low  (L1 < L0)
     *   H2 = 108   pullback trades THROUGH H1 but stays under H0
     *   -> close back below L1 resumes delivery
     *   -> two bullish bars, then a bearish engulfing candle confirms entry
     * ```
     * The leading legs exist to give the H1 external pass enough completed bars
     * to confirm liquidity cycles, without which nothing downstream may fire.
     */
    fun msu1BearishSeries(): List<Candle> = SeriesBuilder(100.0)
        // Warm-up. These legs exist to establish external liquidity cycles
        // *around the price band the setup later forms in*. Without that the
        // engine correctly refuses the setup: its key level would not yet have
        // been knowable on the confirmation bar, which is the non-repaint rule
        // working as intended rather than a detector failing.
        .leg(to = 108.0, bars = 72)
        .leg(to = 101.0, bars = 72)
        .leg(to = 107.0, bars = 72)
        .leg(to = 100.0, bars = 72)
        // The Type 1 structure itself.
        .leg(to = 110.0, bars = 84)
        .leg(to = 104.0, bars = 72)
        .leg(to = 107.0, bars = 48)
        .leg(to = 101.0, bars = 60)
        .leg(to = 108.0, bars = 48)
        // Delivery resumes and closes back below the prior low.
        .leg(to = 100.0, bars = 24)
        // A short counter-move, so the confirming candle has something to
        // engulf — a real entry confirmation, not just another trend candle.
        .bar(open = 100.00, high = 100.90, low = 99.95, close = 100.80)
        .bar(open = 100.80, high = 101.50, low = 100.75, close = 101.40)
        // Bearish engulfing: body [100.10, 101.50] swallows [100.80, 101.40].
        .bar(open = 101.50, high = 101.55, low = 100.05, close = 100.10)
        .leg(to = 97.0, bars = 24)
        .build()

    /** `nascent_msu1_bull` — the exact mirror of [msu1BearishSeries]. */
    fun msu1BullishSeries(): List<Candle> = SeriesBuilder(100.0)
        .leg(to = 92.0, bars = 72)
        .leg(to = 99.0, bars = 72)
        .leg(to = 93.0, bars = 72)
        .leg(to = 100.0, bars = 72)
        .leg(to = 90.0, bars = 84)
        .leg(to = 96.0, bars = 72)
        .leg(to = 93.0, bars = 48)
        .leg(to = 99.0, bars = 60)
        .leg(to = 92.0, bars = 48)
        .leg(to = 100.0, bars = 24)
        .bar(open = 100.00, high = 100.05, low = 99.10, close = 99.20)
        .bar(open = 99.20, high = 99.25, low = 98.50, close = 98.60)
        // Bullish engulfing: body [98.50, 99.90] swallows [98.60, 99.20].
        .bar(open = 98.50, high = 99.95, low = 98.45, close = 99.90)
        .leg(to = 103.0, bars = 24)
        .build()

    /** Appends neutral future bars, for non-repaint assertions. */
    fun withFuture(base: List<Candle>, bars: Int): List<Candle> {
        val out = base.toMutableList()
        var time = base.last().timestamp + M5_MILLIS
        var price = base.last().close
        repeat(bars) { i ->
            val open = price
            val close = if (i % 2 == 0) open + 0.12 else open - 0.09
            out += candle(time, open, maxOf(open, close) + 0.05, minOf(open, close) - 0.05, close)
            time += M5_MILLIS
            price = close
        }
        return out
    }
}
