package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.random.Random

/** Deterministic synthetic series for the Liquidity Sweep tests. */
object LiquiditySweepFixtures {

    const val SYMBOL = "EURUSD"
    const val M5_MILLIS = 5 * 60 * 1000L
    const val START_TIME = 1_700_000_000_000L

    /** Config for component tests: no warmup exclusion. */
    fun testConfig(
        entryMode: EntryMode = EntryMode.RETEST,
        biasMode: BiasMode = BiasMode.HTF_STRUCTURE,
    ) = LiquiditySweepConfig(
        entryMode = entryMode,
        biasMode = biasMode,
        warmupBarsOverride = 0,
    )

    /** A deterministic five-minute random walk with realistic bar geometry. */
    fun m5Walk(size: Int, seed: Int = 1, start: Double = 1.1000): List<Candle> {
        val random = Random(seed)
        var price = start
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 0.0016
            val open = price
            val close = open + drift
            price = close
            val wick = abs(drift) * 0.8 + 0.00005
            Candle(
                timestamp = START_TIME + index * M5_MILLIS,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0 + index,
            )
        }
    }

    /** Build a bar explicitly, for the hand-made sweep fixtures. */
    fun bar(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = START_TIME + index * M5_MILLIS,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )
}
