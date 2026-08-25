package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.SmtConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Property-based prefix non-repaint contract for SMT.
 *
 * [SmtPrefixStabilityTest] already covers correlation/confidence freezing, but
 * its fixture engineers exactly one swing pair per series at indices 30 and 60,
 * perfectly synchronized. With a single candidate peer pair the peer-matching
 * branch in `synchronizedPairs` has nothing to choose between, so that test
 * cannot observe a re-match. This test drives many pseudo-random correlated
 * series so the matcher actually has competing candidates.
 *
 * The contract under test: evaluate the detector on every prefix
 * `candles[0..m]`. Once a divergence is emitted, it must stay emitted — with
 * identical peer index, prices, correlation, confidence and strength — on every
 * longer prefix, until it legitimately ages past `maxSignalAgeBars`. A confirmed
 * marker that mutates or vanishes is a repaint, and it is visible to the user:
 * SMT divergences render as historical rays and feed the fused signal arrows.
 *
 * Regression guard: with nearest-total-distance peer matching this test fails,
 * because a peer pivot confirming *after* the divergence's confirmation bar can
 * score closer and take over the match — reading peer structure from the future
 * of the event and rewriting or destroying the published marker.
 */
class SmtPrefixNonRepaintPropertyTest {

    private val detector = SmtDivergenceDetector()
    private val config = SmtConfig()

    /** Identity of a confirmed event: its primary structure and confirmation bar. */
    private data class EventKey(
        val direction: String,
        val type: String,
        val primaryIndex: Int,
        val confirmationIndex: Int,
    )

    /** Everything the chart and fusion layer read off the event. */
    private data class EventPayload(
        val peerIndex: Int,
        val primaryPrice: Double,
        val peerPrice: Double,
        val correlation: Double,
        val confidence: Double,
        val strength: Double,
    )

    @Test
    fun `confirmed divergences never disappear or mutate as bars are appended`() {
        val violations = mutableListOf<String>()
        var observedEvents = 0

        for (seed in 0 until SERIES_COUNT) {
            val (primary, peer) = correlatedSeries(seed)
            val firstSeen = LinkedHashMap<EventKey, Pair<Int, EventPayload>>()

            for (m in WARMUP_BARS..primary.size) {
                val current = detector.detect(
                    primarySymbol = PRIMARY,
                    primaryCandles = primary.subList(0, m),
                    correlatedCandles = mapOf(PEER to peer.subList(0, m)),
                    config = config,
                ).associate { key(it) to payload(it) }

                current.forEach { (key, value) ->
                    if (firstSeen.putIfAbsent(key, m to value) == null) observedEvents++
                }

                firstSeen.forEach { (key, born) ->
                    val (bornAt, expected) = born
                    // Only assert while the event is still inside the public
                    // retention window; ageing out is the documented contract.
                    if (m - 1 - key.confirmationIndex > config.maxSignalAgeBars) return@forEach
                    val actual = current[key]
                    when {
                        actual == null -> violations += "seed=$seed $key born at prefix=$bornAt " +
                            "vanished at prefix=$m (age ${m - 1 - key.confirmationIndex} bars)"
                        !samePayload(expected, actual) -> violations +=
                            "seed=$seed $key born at prefix=$bornAt mutated at prefix=$m: " +
                                "$expected -> $actual"
                    }
                }
            }
        }

        assertTrue(
            "fixture must actually produce confirmed divergences, otherwise the test is vacuous",
            observedEvents >= MIN_EXPECTED_EVENTS,
        )
        assertTrue(
            "SMT repainted ${violations.size} confirmed divergence(s) across $observedEvents events:\n" +
                violations.take(10).joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * A confirmed divergence must never be re-anchored to a peer swing that
     * confirms after the event itself. This isolates the look-ahead directly:
     * the peer swing backing the event has to be knowable by the confirmation
     * bar, allowing for the peer pivot's own right-side confirmation bars.
     *
     * `peerIndex` and `confirmationIndex` live in different index spaces in
     * general (peer series vs primary series). The fixture below emits both
     * feeds on identical timestamps, so alignment is the identity and the two
     * spaces coincide — which is what makes this comparison meaningful here.
     */
    @Test
    fun `peer swing backing an event is knowable by its confirmation bar`() {
        val offenders = mutableListOf<String>()

        for (seed in 0 until SERIES_COUNT) {
            val (primary, peer) = correlatedSeries(seed)
            detector.detect(
                primarySymbol = PRIMARY,
                primaryCandles = primary,
                correlatedCandles = mapOf(PEER to peer),
                config = config,
            ).forEach { d ->
                val peerKnownAt = d.peerIndex + config.swingLookback
                if (peerKnownAt > d.confirmationIndex) {
                    offenders += "seed=$seed peerIndex=${d.peerIndex} known at $peerKnownAt " +
                        "but event confirmed at ${d.confirmationIndex}"
                }
            }
        }

        assertTrue(
            "peer structure read from the future of the confirmation bar:\n" +
                offenders.take(10).joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun key(d: SmtDivergenceDetector.SmtDivergence) = EventKey(
        direction = d.direction.name,
        type = d.type.name,
        primaryIndex = d.primaryIndex,
        confirmationIndex = d.confirmationIndex,
    )

    private fun payload(d: SmtDivergenceDetector.SmtDivergence) = EventPayload(
        peerIndex = d.peerIndex,
        primaryPrice = d.primaryPrice,
        peerPrice = d.peerPrice,
        correlation = d.correlation,
        confidence = d.confidence,
        strength = d.divergenceStrength,
    )

    private fun samePayload(a: EventPayload, b: EventPayload): Boolean =
        a.peerIndex == b.peerIndex &&
            a.primaryPrice == b.primaryPrice &&
            a.peerPrice == b.peerPrice &&
            abs(a.correlation - b.correlation) <= 1e-12 &&
            abs(a.confidence - b.confidence) <= 1e-12 &&
            abs(a.strength - b.strength) <= 1e-12

    /**
     * Two positively correlated random walks: the peer shares most of the
     * primary's shock but carries independent noise, so their swing structures
     * agree often enough to pair and disagree often enough to diverge.
     * Deterministic for a given seed.
     */
    private fun correlatedSeries(seed: Int): Pair<List<Candle>, List<Candle>> {
        val random = Random(seed)
        val primary = ArrayList<Candle>(BARS)
        val peer = ArrayList<Candle>(BARS)
        var primaryMid = 100.0
        var peerMid = 100.0

        for (index in 0 until BARS) {
            val shock = random.nextDouble(-0.6, 0.6)
            primaryMid += shock + random.nextDouble(-0.25, 0.25)
            peerMid += shock * 0.85 + random.nextDouble(-0.30, 0.30)
            primary += bar(index, primaryMid, random)
            peer += bar(index, peerMid, random)
        }
        return primary to peer
    }

    private fun bar(index: Int, mid: Double, random: Random): Candle {
        val safeMid = mid.coerceAtLeast(10.0)
        val high = safeMid + random.nextDouble(0.05, 0.40)
        val low = safeMid - random.nextDouble(0.05, 0.40)
        return Candle(
            timestamp = BASE_TIMESTAMP + index * BAR_MILLIS,
            open = safeMid,
            high = high,
            low = low,
            close = safeMid,
            volume = 1_000.0,
        )
    }

    private companion object {
        const val PRIMARY = "EURUSD"
        const val PEER = "GBPUSD"
        const val SERIES_COUNT = 24
        const val BARS = 320
        const val WARMUP_BARS = 60
        const val MIN_EXPECTED_EVENTS = 50
        const val BASE_TIMESTAMP = 1_700_000_000_000L
        const val BAR_MILLIS = 60_000L
    }
}
