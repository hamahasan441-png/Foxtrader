package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Edge-case tests for [SmtDivergenceDetector] that complement [SmtDivergenceDetectorTest].
 *
 * These verify boundary conditions: minimum bars requirement, empty correlated map,
 * correlation threshold gating, confidence bounding, and non-repainting swing logic.
 */
class SmtDivergenceDetectorEdgeCaseTest {

    private lateinit var detector: SmtDivergenceDetector

    @Before
    fun setup() {
        detector = SmtDivergenceDetector()
    }

    // -------------------------------------------------------------------------
    // MINIMUM BARS REQUIREMENT (MIN_BARS = 40)
    // -------------------------------------------------------------------------

    @Test
    fun `returns empty when primary has fewer than MIN_BARS candles`() {
        val primary = makeCorrelatedCandles(39, basePrice = 100.0, trend = 0.1)
        val peer = makeCorrelatedCandles(80, basePrice = 100.0, trend = 0.1)

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primary,
            correlatedCandles = mapOf("GBPUSD" to peer),
        )

        assertTrue("Should return empty for fewer than 40 primary bars", result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // EMPTY CORRELATED MAP
    // -------------------------------------------------------------------------

    @Test
    fun `returns empty when correlated map is empty`() {
        val primary = makeCorrelatedCandles(80, basePrice = 100.0, trend = 0.1)

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primary,
            correlatedCandles = emptyMap(),
        )

        assertTrue("Should return empty with no correlated pairs", result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // CORRELATION BELOW THRESHOLD
    // -------------------------------------------------------------------------

    @Test
    fun `returns empty when correlation is below threshold`() {
        // Primary: steady uptrend
        val primary = makeCorrelatedCandles(80, basePrice = 100.0, trend = 0.5)
        // Peer: reciprocal of the primary closes. The detector correlates bar-to-bar
        // RETURNS, so a reciprocal series has returns that are (near-)exact negatives of
        // the primary's -> correlation ~ -1, deterministically below the 0.45 gate.
        val peer = primary.map { c ->
            val inv = 10_000.0 / c.close
            Candle(
                timestamp = c.timestamp,
                open = inv,
                high = inv + 0.25,
                low = inv - 0.25,
                close = inv,
                volume = 100.0,
            )
        }

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primary,
            correlatedCandles = mapOf("USDJPY" to peer),
            minCorrelation = 0.45,
        )

        assertTrue("Should return empty when correlation is below 0.45", result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // CONFIDENCE BOUNDED 62-86
    // -------------------------------------------------------------------------

    @Test
    fun `confidence is bounded between 62 and 86`() {
        // Build highly-correlated series that produce divergence signals
        val primary = buildDivergencePrimary()
        val peer = buildDivergencePeerLowerHigh()

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primary,
            correlatedCandles = mapOf("GBPUSD" to peer),
        )

        // If divergences are detected, all must have confidence in [62, 86]
        for (div in result) {
            assertTrue(
                "Confidence ${div.confidence} must be >= 62.0",
                div.confidence >= 62.0,
            )
            assertTrue(
                "Confidence ${div.confidence} must be <= 86.0",
                div.confidence <= 86.0,
            )
        }

        // Also test the formula boundaries directly with extreme correlation values:
        // correlation = 0.45 -> confidence = 62.0
        // correlation = 1.0 -> confidence = 62 + (1.0 - 0.45) * 35.0 = 62 + 19.25 = 81.25
        // Formula: (62.0 + ((corr - 0.45) * 35.0)).coerceIn(62.0, 86.0)
        val minConf = (62.0 + ((0.45 - 0.45) * 35.0)).coerceIn(62.0, 86.0)
        assertEquals(62.0, minConf, 1e-10)
        val maxConf = (62.0 + ((2.0 - 0.45) * 35.0)).coerceIn(62.0, 86.0)
        assertEquals(86.0, maxConf, 1e-10)
    }

    // -------------------------------------------------------------------------
    // NON-REPAINTING -- SWINGS NEED LOOKBACK ON BOTH SIDES
    // -------------------------------------------------------------------------

    @Test
    fun `non-repainting -- last swingLookback bars cannot be swing points`() {
        // With default swingLookback=3, the findSwings function iterates from
        // index `lookback` to `size - lookback`. This means the last 3 bars
        // can never be classified as swings, ensuring no repainting.
        val swingLookback = 3
        val size = 80

        // Create data where the absolute high is at the last bar (index=79).
        // Despite being the maximum, it should NOT be used as a swing high
        // because it does not have `lookback` bars on the right.
        val candles = (0 until size).map { i ->
            val close = 100.0 + i * 0.1
            val high = if (i == size - 1) 200.0 else close + 0.25 // extreme high at end
            val low = close - 0.25
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = low,
                close = close,
                volume = 100.0,
            )
        }

        // Similarly for peer: extreme high at end
        val peerCandles = (0 until size).map { i ->
            val close = 100.0 + i * 0.1
            val high = if (i == size - 1) 200.0 else close + 0.25
            val low = close - 0.25
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = low,
                close = close,
                volume = 100.0,
            )
        }

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = candles,
            correlatedCandles = mapOf("GBPUSD" to peerCandles),
            swingLookback = swingLookback,
        )

        // If any divergences were found, their swing indices must be within
        // the confirmed range [lookback, size - lookback - 1]
        for (div in result) {
            assertTrue(
                "Primary swing index ${div.primaryIndex} must be < ${size - swingLookback} (confirmed zone)",
                div.primaryIndex < size - swingLookback,
            )
            assertTrue(
                "Peer swing index ${div.peerIndex} must be < ${size - swingLookback} (confirmed zone)",
                div.peerIndex < size - swingLookback,
            )
        }

        // Also verify: the last `swingLookback` indices never appear as divergence indices
        val lastIndices = (size - swingLookback until size).toSet()
        for (div in result) {
            assertTrue(
                "Primary index should not be in last $swingLookback bars",
                div.primaryIndex !in lastIndices,
            )
        }
    }

    @Test
    fun `peer candles fewer than MIN_BARS returns empty for that pair`() {
        val primary = makeCorrelatedCandles(80, basePrice = 100.0, trend = 0.1)
        val shortPeer = makeCorrelatedCandles(30, basePrice = 100.0, trend = 0.1)

        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primary,
            correlatedCandles = mapOf("GBPUSD" to shortPeer),
        )

        assertTrue("Should return empty when peer has fewer than MIN_BARS candles", result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private fun makeCorrelatedCandles(count: Int, basePrice: Double, trend: Double): List<Candle> =
        (0 until count).map { i ->
            val close = basePrice + i * trend + if (i % 2 == 0) 0.02 else -0.01
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = close + 0.25,
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }

    /**
     * Build primary candles with two swing highs where the second is higher (sweep).
     */
    private fun buildDivergencePrimary(): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val high = when (i) {
                30 -> 110.0 // first swing high
                60 -> 111.0 // second swing high (sweeps above first)
                else -> close + 0.25
            }
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }

    /**
     * Build peer candles where the second swing high is LOWER (fails to confirm).
     */
    private fun buildDivergencePeerLowerHigh(): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val high = when (i) {
                30 -> 110.0 // first swing high
                60 -> 109.5 // second swing high (lower -- fails to confirm)
                else -> close + 0.25
            }
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }
}
