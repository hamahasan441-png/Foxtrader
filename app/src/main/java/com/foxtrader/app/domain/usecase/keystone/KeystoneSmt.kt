package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDivergence
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Step 3 — the divergence that separates a trap from a break.
 *
 * When one market takes out its previous high and a market driven by the same
 * thing refuses to, the two are disagreeing about whether that high meant
 * anything. That disagreement is the evidence: it says the move through the
 * level was not participation, it was one market reaching for stops the other
 * did not need to reach for.
 *
 * Three things this stage insists on, each of which is a way the test is
 * usually cheated.
 *
 * **Alignment by timestamp, not by position.** Two feeds with different gaps
 * line up bar-for-bar only by accident. Comparing the peer's 137th bar to the
 * primary's 137th bar compares different moments and manufactures divergences
 * out of the offset.
 *
 * **Correlation measured, not assumed.** A divergence between markets that do
 * not move together is not evidence of anything, and the sign has to match the
 * pair's polarity: an inverse peer that has started tracking the primary
 * positively is no longer the market this test was reasoning about.
 *
 * **Confirmation at the later of the two legs.** Both swings must be confirmed
 * before the divergence exists. Stamping it at the primary's swing would date
 * the event before the moment it could have been seen.
 *
 * The whole series is scanned once per peer and the events are stamped with the
 * bar they became knowable, so a query for any bar is a lookup rather than a
 * rerun — and no query can return an event the market had not yet produced.
 */
class KeystoneSmt {

    /** Every divergence the peer set produced, ordered by confirmation. */
    fun detect(
        primaryCandles: List<Candle>,
        peers: List<KeystonePeerSeries>,
        config: KeystoneConfig,
    ): List<KeystoneDivergence> {
        if (peers.isEmpty() || primaryCandles.size < MIN_BARS) return emptyList()
        val interval = medianInterval(primaryCandles) ?: return emptyList()
        val skewLimit = (interval * config.maxTimestampSkewFraction).toLong()
            .coerceIn(0L, interval / 2)

        return peers
            .flatMap { peer -> detectPair(primaryCandles, peer, skewLimit, config) }
            .sortedBy { it.confirmationIndex }
    }

    /**
     * Divergences in [direction] confirmed within [config].smtWindowBars of
     * [sweepIndex] and knowable by then.
     *
     * The window is one-sided in effect: an event confirming after the entry
     * bar is filtered by the caller, which evaluates each bar through its own
     * prefix. The symmetric window here is about how far from the sweep the
     * divergence may sit, not about reading ahead.
     */
    fun near(
        divergences: List<KeystoneDivergence>,
        sweepIndex: Int,
        direction: Direction,
        config: KeystoneConfig,
    ): KeystoneDivergence? = divergences
        .filter { it.direction == direction }
        .filter { abs(it.confirmationIndex - sweepIndex) <= config.smtWindowBars }
        .maxByOrNull { it.strength }

    private fun detectPair(
        primary: List<Candle>,
        peer: KeystonePeerSeries,
        skewLimit: Long,
        config: KeystoneConfig,
    ): List<KeystoneDivergence> {
        if (peer.candles.size < MIN_BARS) return emptyList()
        val aligned = align(primary, peer.candles, skewLimit)
        if (aligned.size < MIN_BARS) return emptyList()

        val p = aligned.map { it.primary }
        val q = aligned.map { it.peer }
        val lookback = config.smtSwingLookback

        val primaryHighs = swings(p, lookback, high = true)
        val primaryLows = swings(p, lookback, high = false)
        val peerHighs = swings(q, lookback, high = true)
        val peerLows = swings(q, lookback, high = false)

        // Rolling rather than whole-series. Normalising by the average range
        // of the entire series would let bars after a divergence change how
        // strong that divergence is judged to be, which is repainting by
        // arithmetic: the same event would pass the strength filter on one
        // chart and fail it on the same chart an hour later.
        val primaryRange = rollingAverageRange(p)
        val peerRange = rollingAverageRange(q)

        val result = ArrayList<KeystoneDivergence>()

        // Bearish: the primary reaches for buy-side liquidity and the peer does
        // not follow. For an inverse peer, following means making a new low.
        result += scan(
            primarySwings = primaryHighs,
            peerConfirming = if (peer.polarity == KeystonePolarity.POSITIVE) peerHighs else peerLows,
            aligned = aligned,
            primaryExtends = { a, b -> b > a },
            peerFollows = if (peer.polarity == KeystonePolarity.POSITIVE) { a, b -> b > a } else { a, b -> b < a },
            direction = Direction.BEARISH,
            peer = peer,
            primaryRange = primaryRange,
            peerRange = peerRange,
            config = config,
        )

        // Bullish: the primary reaches for sell-side liquidity and the peer
        // does not follow.
        result += scan(
            primarySwings = primaryLows,
            peerConfirming = if (peer.polarity == KeystonePolarity.POSITIVE) peerLows else peerHighs,
            aligned = aligned,
            primaryExtends = { a, b -> b < a },
            peerFollows = if (peer.polarity == KeystonePolarity.POSITIVE) { a, b -> b < a } else { a, b -> b > a },
            direction = Direction.BULLISH,
            peer = peer,
            primaryRange = primaryRange,
            peerRange = peerRange,
            config = config,
        )

        return result
    }

    private fun scan(
        primarySwings: List<Swing>,
        peerConfirming: List<Swing>,
        aligned: List<AlignedBar>,
        primaryExtends: (Double, Double) -> Boolean,
        peerFollows: (Double, Double) -> Boolean,
        direction: Direction,
        peer: KeystonePeerSeries,
        primaryRange: DoubleArray,
        peerRange: DoubleArray,
        config: KeystoneConfig,
    ): List<KeystoneDivergence> {
        if (primarySwings.size < 2 || peerConfirming.size < 2) return emptyList()
        val result = ArrayList<KeystoneDivergence>()

        for (i in 1 until primarySwings.size) {
            val first = primarySwings[i - 1]
            val second = primarySwings[i]
            if (!primaryExtends(first.price, second.price)) continue

            // The peer's own pair of swings closest to the primary's, each
            // within the synchronisation tolerance. A peer swing twenty bars
            // away is describing a different move.
            val peerSecond = peerConfirming.lastOrNull {
                abs(it.index - second.index) <= config.smtSwingLookback * SYNC_TOLERANCE_MULTIPLE
            } ?: continue
            val peerFirst = peerConfirming.lastOrNull {
                it.index < peerSecond.index &&
                    abs(it.index - first.index) <= config.smtSwingLookback * SYNC_TOLERANCE_MULTIPLE
            } ?: continue

            if (peerFollows(peerFirst.price, peerSecond.price)) continue

            val confirmedAt = maxOf(second.confirmedIndex, peerSecond.confirmedIndex)
            if (confirmedAt >= aligned.size) continue

            val correlation = correlationAt(aligned, confirmedAt, config.smtCorrelationPeriod)
                ?: continue
            val signedOk = when (peer.polarity) {
                KeystonePolarity.POSITIVE -> correlation >= config.minPeerCorrelation
                KeystonePolarity.INVERSE -> correlation <= -config.minPeerCorrelation
            }
            if (!signedOk) continue

            // How far the peer fell short, and how far the primary extended,
            // each in its own market's units so the two are comparable.
            val localPeerRange = peerRange[confirmedAt]
            val localPrimaryRange = primaryRange[confirmedAt]
            if (localPeerRange <= 0.0 || localPrimaryRange <= 0.0) continue
            val peerShortfall = abs(peerSecond.price - peerFirst.price) / localPeerRange
            val primaryExtension = abs(second.price - first.price) / localPrimaryRange
            val strength = minOf(peerShortfall, primaryExtension)
            if (strength < config.minDivergenceStrength) continue

            result += KeystoneDivergence(
                peerSymbol = peer.symbol,
                polarity = peer.polarity,
                direction = direction,
                confirmationIndex = aligned[confirmedAt].primaryIndex,
                correlation = correlation,
                strength = strength,
                detail = "${peer.symbol} did not confirm (r=${"%.2f".format(correlation)})",
            )
        }
        return result
    }

    // --- Alignment -----------------------------------------------------------

    private data class AlignedBar(
        val primaryIndex: Int,
        val primary: Candle,
        val peer: Candle,
    )

    private fun align(primary: List<Candle>, peer: List<Candle>, skewLimit: Long): List<AlignedBar> {
        val result = ArrayList<AlignedBar>(minOf(primary.size, peer.size))
        var j = 0
        for (i in primary.indices) {
            val target = primary[i].timestamp
            while (j < peer.size - 1 && peer[j].timestamp < target - skewLimit) j++
            val candidate = peer.getOrNull(j) ?: break
            if (abs(candidate.timestamp - target) > skewLimit) continue
            result += AlignedBar(i, primary[i], candidate)
        }
        return result
    }

    private fun medianInterval(candles: List<Candle>): Long? {
        if (candles.size < 3) return null
        val deltas = ArrayList<Long>(candles.size - 1)
        for (i in 1 until candles.size) deltas += candles[i].timestamp - candles[i - 1].timestamp
        deltas.sort()
        return deltas[deltas.size / 2].takeIf { it > 0L }
    }

    // --- Swings --------------------------------------------------------------

    private data class Swing(val index: Int, val price: Double, val confirmedIndex: Int)

    private fun swings(candles: List<Candle>, lookback: Int, high: Boolean): List<Swing> {
        if (candles.size < lookback * 2 + 1) return emptyList()
        val result = ArrayList<Swing>()
        for (i in lookback until candles.size - lookback) {
            val value = if (high) candles[i].high else candles[i].low
            var ok = true
            for (j in 1..lookback) {
                val left = if (high) candles[i - j].high else candles[i - j].low
                val right = if (high) candles[i + j].high else candles[i + j].low
                if (high) {
                    if (value <= left || value < right) { ok = false; break }
                } else {
                    if (value >= left || value > right) { ok = false; break }
                }
            }
            if (ok) result += Swing(i, value, i + lookback)
        }
        return result
    }

    /** Mean bar range over the [RANGE_WINDOW] bars ending at each index. */
    private fun rollingAverageRange(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size)
        var sum = 0.0
        for (i in candles.indices) {
            sum += candles[i].range
            if (i >= RANGE_WINDOW) sum -= candles[i - RANGE_WINDOW].range
            val count = minOf(i + 1, RANGE_WINDOW)
            out[i] = sum / count
        }
        return out
    }

    // --- Correlation ---------------------------------------------------------

    /**
     * Pearson correlation of log-style returns over the window ending at
     * [endIndex], or null when the window is too short or degenerate.
     *
     * Returns rather than levels: two trending series correlate at 0.99 on
     * levels whatever they are doing, which would let this check pass for any
     * pair at all.
     */
    private fun correlationAt(aligned: List<AlignedBar>, endIndex: Int, period: Int): Double? {
        val start = (endIndex - period + 1).coerceAtLeast(1)
        val count = endIndex - start + 1
        if (count < MIN_CORRELATION_BARS) return null

        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        for (i in start..endIndex) {
            val a = aligned[i].primary.close / aligned[i - 1].primary.close - 1.0
            val b = aligned[i].peer.close / aligned[i - 1].peer.close - 1.0
            if (!a.isFinite() || !b.isFinite()) return null
            sumA += a; sumB += b
            sumAA += a * a; sumBB += b * b; sumAB += a * b
        }
        val n = count.toDouble()
        val covariance = sumAB - sumA * sumB / n
        val varianceA = sumAA - sumA * sumA / n
        val varianceB = sumBB - sumB * sumB / n
        if (varianceA <= 0.0 || varianceB <= 0.0) return null
        val r = covariance / sqrt(varianceA * varianceB)
        return if (r.isFinite()) r.coerceIn(-1.0, 1.0) else null
    }

    private companion object {
        const val MIN_BARS = 60
        const val MIN_CORRELATION_BARS = 30
        const val RANGE_WINDOW = 100

        /**
         * How far a peer swing may sit from the primary's, as a multiple of the
         * swing lookback. Two markets rarely turn on the identical bar, and
         * demanding that they do would reject every real pairing.
         */
        const val SYNC_TOLERANCE_MULTIPLE = 2
    }
}
