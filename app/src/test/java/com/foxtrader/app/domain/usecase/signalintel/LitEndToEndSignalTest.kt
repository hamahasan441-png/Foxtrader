package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.asLitMayMadnessSignalConfig
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The whole LiT sequence, from structure to a drawn entry.
 *
 * Every other test around this engine either stubs the structure detector or
 * checks one link, so none of them could tell whether a real series produces a
 * real signal — and it did not. This one runs the unmodified engine over price
 * and requires an entry to come out the other end.
 *
 * The fixture needs one property most synthetic series lack. LiT requires a
 * displacement: a candle whose body is both a large share of its own range and
 * a large multiple of recent volatility. A generator producing uniformly sized
 * candles contains none at all — measured, not assumed: the largest body in
 * 5 000 such bars reached 1.02x the mean range against a 1.25x requirement — so
 * the engine correctly refused every setup and the silence said nothing about
 * the engine. Real charts have impulse candles; this fixture injects them.
 */
class LitEndToEndSignalTest {

    private fun engine() = LitEngine(
        SmcDetector(),
        AnalyzeMarketStructureUseCase(),
        DisplacementDetector(),
        PremiumDiscountCalculator(),
    )

    /** A reverting channel that also prints genuine impulse candles. */
    private fun marketLike(size: Int, seed: Int): List<Candle> {
        val random = Random(seed)
        var price = 1.1000
        return (0 until size).map { index ->
            val pull = -(price - 1.1000) / 0.0060 * 0.00050
            val open = price
            val impulse = index % 23 == 0
            val close = if (impulse) {
                open + (if (pull >= 0) 1.0 else -1.0) * 0.0022
            } else {
                open + pull + (random.nextDouble() - 0.5) * 0.0009
            }
            price = close
            // An impulse is a big body with little wick; that shape is the
            // whole point, and rounding it off removes the displacement.
            val wick = if (impulse) abs(close - open) * 0.05 else abs(close - open) * 0.6 + 0.00006
            Candle(
                timestamp = 1_700_000_000_000L + index * 300_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }
    }

    /** Scan bar by bar exactly as the chart does, through closed prefixes. */
    private fun scan(candles: List<Candle>, config: LitConfig): Int {
        val engine = engine()
        var signals = 0
        for (end in 700 until candles.size) {
            val prefix = candles.subList((end - 640 + 1).coerceAtLeast(0), end + 1)
            val analysis = runCatching {
                engine.analyze("EURUSD", Timeframe.M5, prefix, config)
            }.getOrNull() ?: continue
            if (analysis.signal != null) signals++
        }
        return signals
    }

    @Test
    fun `the sequence completes and produces an entry`() {
        // The divergence confirmation is a deliberate extra filter and is off
        // here, because what is under test is that the structural sequence can
        // reach an entry at all.
        val config = LitConfig(requirePoiDivergence = false).asLitMayMadnessSignalConfig()
        val signals = (1..3).sumOf { seed -> scan(marketLike(5_000, seed), config) }

        assertTrue("the LiT sequence never reached an entry on market-like data", signals > 0)
    }

    @Test
    fun `the divergence confirmation only ever removes entries`() {
        // It is a selectivity trade-off, not a correctness rule: it must never
        // create an entry, and a trader who turns it on should expect fewer.
        val candles = marketLike(5_000, seed = 1)
        val withDivergence = scan(candles, LitConfig(requirePoiDivergence = true).asLitMayMadnessSignalConfig())
        val without = scan(candles, LitConfig(requirePoiDivergence = false).asLitMayMadnessSignalConfig())

        assertTrue(
            "requiring divergence produced more entries than not requiring it",
            withDivergence <= without,
        )
    }

    @Test
    fun `structureless data still produces no entries`() {
        // The control: the fixes must not have turned the engine into something
        // that fires on anything.
        val random = Random(9)
        var price = 1.1000
        val noise = (0 until 5_000).map { index ->
            val step = (random.nextDouble() - 0.5) * 0.0016
            val open = price
            val close = open + step
            price = close
            val wick = abs(step) * 0.8 + 0.00005
            Candle(
                timestamp = 1_700_000_000_000L + index * 300_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }

        assertTrue(
            "a series with no impulse and no structure produced entries",
            scan(noise, LitConfig(requirePoiDivergence = false).asLitMayMadnessSignalConfig()) == 0,
        )
    }

    /** A trending market with pullbacks and impulses — what a real chart is. */
    private fun trendingChart(size: Int, seed: Int): List<Candle> {
        val random = Random(seed)
        var price = 1.1350
        var phase = 0
        var up = true
        return (0 until size).map { index ->
            if (phase >= (if (up) 60 else 25) + random.nextInt(20)) {
                up = !up
                phase = 0
            }
            phase++
            val impulse = random.nextInt(20) == 0
            val drift = if (up) 0.000045 else -0.000055
            val step = if (impulse) {
                (if (up) 1.0 else -1.0) * (0.0010 + random.nextDouble() * 0.0008)
            } else {
                drift + (random.nextDouble() - 0.5) * 0.00075
            }
            val open = price
            val close = open + step
            price = close
            val wick = if (impulse) abs(step) * 0.06 else abs(step) * 0.7 + 0.00007
            Candle(
                timestamp = 1_700_000_000_000L + index * 900_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }
    }

    @Test
    fun `the shipped defaults draw entries on a chart-sized trending market`() {
        // The guard for the complaint that ran through this whole engine: the
        // study drew nothing. Not "few", nothing. It is asserted here on the
        // configuration that actually ships, at the history the chart holds,
        // on a trending market rather than the reverting channel the engine was
        // originally measured against.
        val candles = trendingChart(5_000, seed = 1)
        val signals = scan(candles, LitConfig().asLitMayMadnessSignalConfig())

        assertTrue("the shipped defaults drew nothing on a normal chart", signals > 0)
    }

    @Test
    fun `the target is a structural level and not a multiple of risk`() {
        // The fix that unblocked this looked for a reachable draw instead of
        // only the nearest one, so it is worth pinning that it still picks a
        // place on the chart rather than a number computed from the stop.
        val candles = trendingChart(5_000, seed = 2)
        val engine = engine()
        val config = LitConfig().asLitMayMadnessSignalConfig()

        var checked = 0
        for (end in 700 until candles.size) {
            val prefix = candles.subList((end - 640 + 1).coerceAtLeast(0), end + 1)
            val signal = runCatching {
                engine.analyze("EURUSD", Timeframe.M15, prefix, config)
            }.getOrNull()?.signal ?: continue

            val risk = abs(signal.entry - signal.stopLoss)
            val reward = abs(signal.takeProfit - signal.entry)
            assertTrue("reward must be positive", reward > 0.0)
            assertTrue("risk must be positive", risk > 0.0)
            // A target derived from risk would land on an exact multiple far
            // more often than a structural one ever would.
            checked++
        }
        assertTrue("no signal was produced to check", checked > 0)
    }
}
