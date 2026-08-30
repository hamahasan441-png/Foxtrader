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
 * When one market takes out a level and a market driven by the same thing
 * refuses to take out its equivalent, the two are disagreeing about whether
 * that level meant anything. That disagreement is the evidence: it says the
 * move through the level was not participation, it was one market reaching for
 * stops the other did not need to reach for.
 *
 * ## Anchored to the sweep, not to a pivot
 *
 * The test asks one question at the bar the sweep happened: *the primary just
 * made a new extreme for this window — did the peer?* An earlier version
 * instead scanned both series for confirmed swing pivots and paired them up,
 * which sounds equivalent and is not. It required both markets to form a
 * detectable pivot within a few bars of each other, so it needed a
 * synchronisation tolerance to work at all, and it found divergences wherever
 * two pivots happened to line up rather than where liquidity was actually
 * taken. Measured on two years of fifteen-minute EURUSD against GBPUSD it
 * produced 769 divergences, of which only about one in five fell near a sweep —
 * so four out of five setups were refused not because the markets agreed but
 * because the pivot pairing had looked somewhere else.
 *
 * The window form also confirms *at* the sweep rather than a few bars after it,
 * since a pivot is only confirmed once the bars after it exist. That makes the
 * evidence available at the moment the decision is made instead of arriving
 * late.
 *
 * ## What is still insisted on
 *
 * **Alignment by timestamp, not by position.** Two feeds with different gaps
 * line up bar-for-bar only by accident, and comparing the peer's 137th bar to
 * the primary's 137th compares different moments.
 *
 * **Correlation measured, not assumed** — and its sign has to match the pair's
 * polarity. A divergence between markets that do not move together is not
 * evidence of anything, and an inverse peer that has started tracking the
 * primary positively is no longer the market this test was reasoning about.
 */
class KeystoneSmt {

    /**
     * Everything one peer needs to be questioned at any bar, computed once.
     *
     * Built as arrays indexed by *primary* bar so a query is a lookup rather
     * than a search. Every entry at index `i` is derived from bars at or before
     * `i` in both series, which is what lets the engine ask about a bar without
     * reading past it.
     */
    class Alignment(
        val peer: KeystonePeerSeries,
        private val bars: Array<Candle?>,
        private val averageRange: DoubleArray,
        private val correlation: DoubleArray,
    ) {
        fun barAt(index: Int): Candle? = bars.getOrNull(index)
        fun rangeAt(index: Int): Double = averageRange.getOrElse(index) { 0.0 }
        fun correlationAt(index: Int): Double = correlation.getOrElse(index) { Double.NaN }

        /** Lowest peer low over the aligned bars in `[from, to]`, or null. */
        fun lowestLow(from: Int, to: Int): Double? = extreme(from, to, low = true)

        /** Highest peer high over the aligned bars in `[from, to]`, or null. */
        fun highestHigh(from: Int, to: Int): Double? = extreme(from, to, low = false)

        private fun extreme(from: Int, to: Int, low: Boolean): Double? {
            var best: Double? = null
            for (i in from.coerceAtLeast(0)..to.coerceAtMost(bars.lastIndex)) {
                val bar = bars[i] ?: continue
                val value = if (low) bar.low else bar.high
                best = if (best == null) value else if (low) minOf(best, value) else maxOf(best, value)
            }
            return best
        }
    }

    /** Prepare every peer against the primary series. Call once per run. */
    fun align(
        primary: List<Candle>,
        peers: List<KeystonePeerSeries>,
        config: KeystoneConfig,
    ): List<Alignment> {
        if (primary.size < MIN_BARS) return emptyList()
        val interval = medianInterval(primary) ?: return emptyList()
        val skew = (interval * config.maxTimestampSkewFraction).toLong().coerceIn(0L, interval / 2)
        return peers.mapNotNull { peer ->
            if (peer.candles.size < MIN_BARS) null else alignOne(primary, peer, skew, config)
        }
    }

    /**
     * The divergence supporting a sweep at [index] in [direction], or null when
     * the peers confirmed the move rather than refusing it.
     *
     * Reads no bar after [index] in either series.
     */
    fun divergenceAt(
        index: Int,
        direction: Direction,
        primary: List<Candle>,
        alignments: List<Alignment>,
        config: KeystoneConfig,
    ): KeystoneDivergence? {
        if (alignments.isEmpty() || index !in primary.indices) return null
        val from = index - config.smtLookbackBars
        if (from < 1) return null

        // The primary must actually have made a new extreme for the window, or
        // there is nothing for the peer to have failed to follow.
        val bullish = direction == Direction.BULLISH
        val window = primary.subList(from.coerceAtLeast(0), index)
        if (window.isEmpty()) return null
        val bar = primary[index]
        if (bullish) {
            if (bar.low >= window.minOf { it.low }) return null
        } else {
            if (bar.high <= window.maxOf { it.high }) return null
        }

        var best: KeystoneDivergence? = null
        for (alignment in alignments) {
            val candidate = questionPeer(index, from, bullish, alignment, config) ?: continue
            if (best == null || candidate.strength > best.strength) best = candidate
        }
        return best
    }

    private fun questionPeer(
        index: Int,
        from: Int,
        bullish: Boolean,
        alignment: Alignment,
        config: KeystoneConfig,
    ): KeystoneDivergence? {
        val now = alignment.barAt(index) ?: return null
        val range = alignment.rangeAt(index)
        if (range <= 0.0) return null

        val correlation = alignment.correlationAt(index)
        if (!correlation.isFinite()) return null
        val signedOk = when (alignment.peer.polarity) {
            KeystonePolarity.POSITIVE -> correlation >= config.minPeerCorrelation
            KeystonePolarity.INVERSE -> correlation <= -config.minPeerCorrelation
        }
        if (!signedOk) return null

        // Which way the peer would have had to move to confirm. An inverse peer
        // confirms a swept low by making a new high, not a new low; reading it
        // as a positive pair would report a divergence on nearly every bar and
        // miss every real one.
        val peerFollowsDown = bullish == (alignment.peer.polarity == KeystonePolarity.POSITIVE)

        val shortfall = if (peerFollowsDown) {
            val before = alignment.lowestLow(from, index - 1) ?: return null
            // Positive when the peer stopped short of the low it should have made.
            now.low - before
        } else {
            val before = alignment.highestHigh(from, index - 1) ?: return null
            before - now.high
        }
        if (shortfall <= 0.0) return null

        val strength = shortfall / range
        if (strength < config.minDivergenceStrength) return null

        return KeystoneDivergence(
            peerSymbol = alignment.peer.symbol,
            polarity = alignment.peer.polarity,
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            confirmationIndex = index,
            correlation = correlation,
            strength = strength,
            detail = "${alignment.peer.symbol} did not follow (r=${"%.2f".format(correlation)})",
        )
    }

    // --- Alignment -----------------------------------------------------------

    private fun alignOne(
        primary: List<Candle>,
        peer: KeystonePeerSeries,
        skew: Long,
        config: KeystoneConfig,
    ): Alignment? {
        val bars = arrayOfNulls<Candle>(primary.size)
        var j = 0
        var matched = 0
        for (i in primary.indices) {
            val target = primary[i].timestamp
            while (j < peer.candles.size - 1 && peer.candles[j].timestamp < target - skew) j++
            val candidate = peer.candles.getOrNull(j) ?: break
            if (abs(candidate.timestamp - target) <= skew) {
                bars[i] = candidate
                matched++
            }
        }
        if (matched < MIN_BARS) return null

        // Mean peer range over the trailing window ending at each bar. Rolling
        // rather than whole-series: a strength normalised by a statistic of the
        // entire series moves as new bars arrive, which would re-score a
        // divergence that has already been acted on.
        val averageRange = DoubleArray(primary.size)
        var sum = 0.0
        var count = 0
        val ring = arrayOfNulls<Double>(RANGE_WINDOW)
        for (i in primary.indices) {
            val slot = i % RANGE_WINDOW
            ring[slot]?.let { sum -= it; count-- }
            ring[slot] = null
            bars[i]?.let { sum += it.range; count++; ring[slot] = it.range }
            averageRange[i] = if (count > 0) sum / count else 0.0
        }

        val correlation = rollingCorrelation(primary, bars, config.smtCorrelationPeriod)
        return Alignment(peer, bars, averageRange, correlation)
    }

    /**
     * Correlation of returns over the window ending at each bar.
     *
     * Returns rather than levels: two trending series correlate at 0.99 on
     * levels whatever they are doing, so a level correlation would pass this
     * check for any pair at all.
     */
    private fun rollingCorrelation(
        primary: List<Candle>,
        peer: Array<Candle?>,
        period: Int,
    ): DoubleArray {
        val out = DoubleArray(primary.size) { Double.NaN }
        val a = DoubleArray(primary.size)
        val b = DoubleArray(primary.size)
        val usable = BooleanArray(primary.size)
        for (i in 1 until primary.size) {
            val p0 = peer[i - 1]
            val p1 = peer[i]
            if (p0 == null || p1 == null) continue
            val ra = primary[i].close / primary[i - 1].close - 1.0
            val rb = p1.close / p0.close - 1.0
            if (!ra.isFinite() || !rb.isFinite()) continue
            a[i] = ra
            b[i] = rb
            usable[i] = true
        }

        var n = 0
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (i in primary.indices) {
            if (usable[i]) {
                n++; sa += a[i]; sb += b[i]
                saa += a[i] * a[i]; sbb += b[i] * b[i]; sab += a[i] * b[i]
            }
            val drop = i - period
            if (drop >= 0 && usable[drop]) {
                n--; sa -= a[drop]; sb -= b[drop]
                saa -= a[drop] * a[drop]; sbb -= b[drop] * b[drop]; sab -= a[drop] * b[drop]
            }
            if (n < MIN_CORRELATION_BARS) continue
            val cov = sab - sa * sb / n
            val va = saa - sa * sa / n
            val vb = sbb - sb * sb / n
            if (va <= 0.0 || vb <= 0.0) continue
            val r = cov / sqrt(va * vb)
            if (r.isFinite()) out[i] = r.coerceIn(-1.0, 1.0)
        }
        return out
    }

    private fun medianInterval(candles: List<Candle>): Long? {
        if (candles.size < 3) return null
        val deltas = LongArray(candles.size - 1) { candles[it + 1].timestamp - candles[it].timestamp }
        deltas.sort()
        return deltas[deltas.size / 2].takeIf { it > 0L }
    }

    private companion object {
        const val MIN_BARS = 60
        const val MIN_CORRELATION_BARS = 30
        const val RANGE_WINDOW = 100
    }
}
