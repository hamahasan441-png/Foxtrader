package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.random.Random

/** Deterministic synthetic series for the Virgin Wick tests. */
object VirginWickFixtures {

    const val SYMBOL = "NQ"
    const val M1_MILLIS = 60 * 1000L

    /**
     * 2023-11-14 22:00 UTC — aligned to an hour boundary.
     *
     * Alignment matters: resampling buckets from the epoch, so an unaligned
     * start makes a group of N execution bars straddle two context buckets and
     * the hand-built context fixtures below stop describing what they say they
     * do. The hour also sits outside every kill zone, so session tests have to
     * be explicit about the window they expect.
     */
    const val START_TIME = 1_699_999_200_000L

    fun testConfig(
        entryMode: EntryMode = EntryMode.IFVG,
        testMode: WickTestMode = WickTestMode.ANY_TOUCH,
    ) = VirginWickConfig(
        entryMode = entryMode,
        testMode = testMode,
        warmupBarsOverride = 0,
    )

    /** A one-minute index-futures style walk with pronounced wicks. */
    fun m1Walk(size: Int, seed: Int = 1, start: Double = 20_000.0): List<Candle> {
        val random = Random(seed)
        var price = start
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 12.0
            val open = price
            val close = open + drift
            price = close
            val wick = abs(drift) * (0.5 + random.nextDouble()) + 0.5
            Candle(
                timestamp = START_TIME + index * M1_MILLIS,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0 + index,
            )
        }
    }

    fun bar(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = START_TIME + index * M1_MILLIS,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )
}
