package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import kotlin.math.abs
import kotlin.random.Random

/**
 * Series built to contain the Keystone sequence, and series built to contain
 * nothing.
 *
 * The constructed series is not evidence that the model works. It is evidence
 * that the engine can recognise the model when it is unambiguously present —
 * which is a different and much smaller claim, and the only one a synthetic
 * fixture can support. Earlier engines in this app were validated against
 * generated data that happened not to contain the pattern being looked for, and
 * the resulting silence was read as a bug in the market rather than in the
 * fixture. Everything here is therefore constructed bar by bar with the
 * geometry written out, so that a failure points at the engine.
 */
object KeystoneFixtures {

    const val SYMBOL = "EURUSD"
    const val PEER = "GBPUSD"
    const val M15_MILLIS = 15 * 60 * 1000L

    /** 2024-01-01T00:00:00Z — a Monday, so the session filters see a full week. */
    const val START_TIME = 1_704_067_200_000L

    fun engine() = KeystoneEngine(AnalyzeMarketStructureUseCase())

    /** The full sequence, repeated [cycles] times inside a rising market. */
    fun sequence(cycles: Int = 24): Built = build(cycles)

    fun peerOf(built: Built, polarity: KeystonePolarity = KeystonePolarity.POSITIVE) =
        KeystonePeerSeries(PEER, built.peer, polarity)

    /** A random walk: the sequence is absent, so nothing should be found. */
    fun walk(size: Int, seed: Int = 3, start: Double = 1.1000): List<Candle> {
        val random = Random(seed)
        var price = start
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 0.0016
            val open = price
            val close = open + drift
            price = close
            val wick = abs(drift) * 0.8 + 0.00005
            Candle(
                timestamp = START_TIME + index * M15_MILLIS,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }
    }

    /** The primary series, its peer, and the bars each sweep was placed on. */
    data class Built(
        val primary: List<Candle>,
        val peer: List<Candle>,
        val sweepBars: List<Int>,
    )

    private class Writer {
        val bars = ArrayList<Candle>()
        fun add(open: Double, high: Double, low: Double, close: Double) {
            bars += Candle(
                timestamp = START_TIME + bars.size * M15_MILLIS,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 1_000.0,
            )
        }

        fun up(from: Double, step: Double): Double {
            val close = from + step
            add(from, close + WICK, from - WICK, close)
            return close
        }

        fun down(from: Double, step: Double): Double {
            val close = from - step
            add(from, from + WICK, close - WICK, close)
            return close
        }
    }

    /**
     * One cycle is a rising leg, a pullback that leaves a swing low, a second
     * rising leg, a return to that low, then the sequence itself: the sweep,
     * the displacement, the gap it leaves, and the retracement back into it.
     *
     * The primary and the peer are identical apart from two places, and both
     * differences exist to make the divergence real rather than incidental: the
     * peer's second pullback is shallower, and the peer does not follow the
     * primary through the swept low.
     */
    private fun build(cycles: Int): Built {
        val primary = Writer()
        val peer = Writer()
        val sweeps = ArrayList<Int>()

        var price = 1.1000
        var peerPrice = 1.1000

        repeat(cycles) { cycle ->
            // A correction long enough to register on the higher timeframe.
            // Without one the resampled H4 series is monotone, and a monotone
            // series has no swing points at all — so the bias filter refuses
            // everything for want of structure rather than for want of a trend.
            // That is a property of the fixture, not of the engine, and it is
            // exactly the kind of thing that has previously been mistaken for
            // an engine that does not work.
            if (cycle > 0 && cycle % CORRECTION_EVERY == 0) {
                repeat(CORRECTION_BARS) {
                    price = primary.down(price, 0.0006)
                    peerPrice = peer.down(peerPrice, 0.0006)
                }
            }

            // Leg 1 — twelve bars up.
            repeat(12) {
                price = primary.up(price, 0.0006)
                peerPrice = peer.up(peerPrice, 0.0006)
            }
            // Leg 2 — five bars down, leaving the swing low the sweep will take.
            repeat(5) {
                price = primary.down(price, 0.0005)
                peerPrice = peer.down(peerPrice, 0.0005)
            }
            val low = price - WICK
            val peerLow = peerPrice - WICK

            // Leg 3 — seven bars up, building the high the displacement must break.
            repeat(7) {
                price = primary.up(price, 0.0006)
                peerPrice = peer.up(peerPrice, 0.0006)
            }
            // Leg 4 — back down to the shelf. The peer stops a little short of
            // it, which is what makes its refusal to follow visible.
            repeat(7) {
                price = primary.down(price, 0.0006)
                peerPrice = peer.down(peerPrice, 0.0005)
            }

            // The sweep: through the low, and closed back above it.
            sweeps += primary.bars.size
            primary.add(
                open = price,
                high = low + 0.0007,
                low = low - 0.0010,
                close = low + 0.0006,
            )
            // The peer reaches for the same shelf and does not reach it.
            peer.add(
                open = peerLow + 0.0004,
                high = peerLow + 0.0007,
                low = peerLow + 0.0002,
                close = peerLow + 0.0006,
            )
            price = low + 0.0006
            peerPrice = peerLow + 0.0006

            // The displacement: one closed candle that clears the leg-3 high.
            val displacementClose = low + 0.0066
            primary.add(price, displacementClose + 0.0002, price - 0.0002, displacementClose)
            val peerDisplacementClose = peerLow + 0.0066
            peer.add(peerPrice, peerDisplacementClose + 0.0002, peerPrice - 0.0002, peerDisplacementClose)
            price = displacementClose
            peerPrice = peerDisplacementClose

            // The bar after it, whose low sits clear of the pre-sweep high and
            // so completes the fair value gap.
            primary.add(price, low + 0.0070, low + 0.0055, low + 0.0068)
            peer.add(peerPrice, peerLow + 0.0070, peerLow + 0.0055, peerLow + 0.0068)
            price = low + 0.0068
            peerPrice = peerLow + 0.0068

            // The retracement back into the gap.
            primary.add(price, low + 0.0074, low + 0.0050, low + 0.0072)
            peer.add(peerPrice, peerLow + 0.0074, peerLow + 0.0050, peerLow + 0.0072)
            price = low + 0.0072
            peerPrice = peerLow + 0.0072

            // Continuation.
            repeat(2) {
                price = primary.up(price, 0.0006)
                peerPrice = peer.up(peerPrice, 0.0006)
            }
        }

        return Built(primary.bars, peer.bars, sweeps)
    }

    private const val WICK = 0.0001

    /** Cycles between corrections, and how long a correction lasts. */
    private const val CORRECTION_EVERY = 3
    private const val CORRECTION_BARS = 48
}
