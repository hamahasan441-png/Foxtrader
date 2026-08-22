package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Strict historical reproducibility tests for SMT. */
class SmtPrefixStabilityTest {
    private val detector = SmtDivergenceDetector()

    @Test
    fun `future bars cannot re-score or erase an already confirmed divergence`() {
        val primary = primaryHigherHigh()
        val peer = peerLowerHigh()
        val baseline = findTarget(primary, peer)
        assertNotNull("baseline divergence fixture must be valid", baseline)

        // Everything after confirmation bar 63 is future information for this
        // divergence. Mutate it aggressively while preserving timestamps and
        // valid OHLC geometry. The historical event must remain byte-for-byte
        // equivalent in all event-time statistics.
        val mutatedPrimary = mutateFuture(primary, fromIndex = 64, rising = true)
        val mutatedPeer = mutateFuture(peer, fromIndex = 64, rising = false)
        val afterFutureMutation = findTarget(mutatedPrimary, mutatedPeer)
        assertNotNull("future candles must not erase the historical SMT event", afterFutureMutation)

        val expected = baseline!!
        val actual = afterFutureMutation!!
        assertEquals(expected.primaryIndex, actual.primaryIndex)
        assertEquals(expected.peerIndex, actual.peerIndex)
        assertEquals(expected.confirmationIndex, actual.confirmationIndex)
        assertEquals(expected.direction, actual.direction)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.primaryPrice, actual.primaryPrice, 0.0)
        assertEquals(expected.peerPrice, actual.peerPrice, 0.0)
        assertEquals(expected.correlation, actual.correlation, 1e-12)
        assertEquals(expected.confidence, actual.confidence, 1e-12)
        assertEquals(expected.divergenceStrength, actual.divergenceStrength, 1e-12)
    }

    @Test
    fun `appended future bars preserve a still-recent historical divergence`() {
        val primary = primaryHigherHigh()
        val peer = peerLowerHigh()
        val baseline = findTarget(primary, peer)
        assertNotNull(baseline)

        // Append six bars: confirmation 63 -> newest index 85 => age 22, still
        // inside the default maxSignalAgeBars=24 contract.
        val extendedPrimary = appendFuture(primary, rising = true)
        val extendedPeer = appendFuture(peer, rising = false)
        val extended = findTarget(extendedPrimary, extendedPeer)
        assertNotNull("newer swings/bars cannot replace a still-recent event", extended)

        assertEquals(baseline!!.correlation, extended!!.correlation, 1e-12)
        assertEquals(baseline.confidence, extended.confidence, 1e-12)
        assertEquals(baseline.divergenceStrength, extended.divergenceStrength, 1e-12)
    }

    private fun findTarget(
        primary: List<Candle>,
        peer: List<Candle>,
    ): SmtDivergenceDetector.SmtDivergence? = detector.detect(
        primarySymbol = "EURUSD",
        primaryCandles = primary,
        correlatedCandles = mapOf("GBPUSD" to peer),
    ).firstOrNull {
        it.direction == Direction.BEARISH &&
            it.type == SmtDivergenceDetector.SmtType.PRIMARY_SWEEP_PEER_FAIL &&
            it.primaryIndex == 60 &&
            it.confirmationIndex == 63
    }

    private fun mutateFuture(
        source: List<Candle>,
        fromIndex: Int,
        rising: Boolean,
    ): List<Candle> = source.mapIndexed { index, candle ->
        if (index < fromIndex) {
            candle
        } else {
            val step = index - fromIndex + 1
            val close = if (rising) 106.0 + step * 3.7 else 106.0 - step * 2.9
            Candle(
                timestamp = candle.timestamp,
                open = close + if (rising) -0.40 else 0.40,
                high = close + 0.75,
                low = close - 0.75,
                close = close,
                volume = 1_500.0 + step,
            )
        }
    }

    private fun appendFuture(source: List<Candle>, rising: Boolean): List<Candle> {
        val start = source.size
        return source + (start until start + 6).map { index ->
            val step = index - start + 1
            val close = if (rising) 106.0 + step * 2.8 else 106.0 - step * 2.2
            Candle(
                timestamp = index * 60_000L,
                open = close + if (rising) -0.35 else 0.35,
                high = close + 0.65,
                low = close - 0.65,
                close = close,
                volume = 2_000.0 + step,
            )
        }
    }

    private fun primaryHigherHigh(): List<Candle> = baseCandles { index, high, low ->
        val adjustedHigh = when (index) {
            30 -> 110.0
            60 -> 111.0
            else -> high
        }
        adjustedHigh to low
    }

    private fun peerLowerHigh(): List<Candle> = baseCandles { index, high, low ->
        val adjustedHigh = when (index) {
            30 -> 110.0
            60 -> 109.5
            else -> high
        }
        adjustedHigh to low
    }

    private fun baseCandles(
        levelOverride: (Int, Double, Double) -> Pair<Double, Double>,
    ): List<Candle> = (0 until 80).map { index ->
        val close = 100.0 + index * 0.08 + if (index % 2 == 0) 0.02 else -0.01
        val (high, low) = levelOverride(index, close + 0.25, close - 0.25)
        Candle(
            timestamp = index * 60_000L,
            open = close - 0.03,
            high = high,
            low = low,
            close = close,
            volume = 100.0,
        )
    }
}
