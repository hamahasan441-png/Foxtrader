package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correlation clustering.
 *
 * The property that matters for risk: positions linked by a *chain* of strong
 * correlations must land in one cluster, and the sign of the peak correlation
 * must survive so a hedge is never reported as compounding risk.
 */
class CorrelationClusterBuilderTest {

    private val builder = CorrelationClusterBuilder()

    private fun pair(a: String, b: String, corr: Double) = CorrelationMatrix.CorrelationPair(
        symbolA = a,
        symbolB = b,
        correlation = corr,
        strength = when {
            corr > 0.7 -> CorrelationMatrix.CorrelationStrength.STRONG_POSITIVE
            corr > 0.4 -> CorrelationMatrix.CorrelationStrength.MODERATE_POSITIVE
            corr < -0.7 -> CorrelationMatrix.CorrelationStrength.STRONG_NEGATIVE
            corr < -0.4 -> CorrelationMatrix.CorrelationStrength.MODERATE_NEGATIVE
            else -> CorrelationMatrix.CorrelationStrength.WEAK
        },
        dataPoints = 100,
    )

    private fun matrix(vararg pairs: CorrelationMatrix.CorrelationPair) =
        CorrelationMatrix.MatrixResult(
            symbols = pairs.flatMap { listOf(it.symbolA, it.symbolB) }.distinct(),
            matrix = arrayOf(doubleArrayOf(1.0)),
            pairs = pairs.toList(),
            period = 100,
        )

    @Test
    fun `strongly correlated pair forms one cluster`() {
        val clusters = builder.build(
            exposureBySymbol = mapOf("EURUSD" to 50.0, "GBPUSD" to 30.0),
            matrix = matrix(pair("EURUSD", "GBPUSD", 0.92)),
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf("EURUSD", "GBPUSD"), clusters.first().symbols)
        assertEquals(80.0, clusters.first().combinedExposurePercent, 1e-9)
    }

    @Test
    fun `weak correlation does not cluster`() {
        val clusters = builder.build(
            exposureBySymbol = mapOf("EURUSD" to 50.0, "USDJPY" to 30.0),
            matrix = matrix(pair("EURUSD", "USDJPY", 0.15)),
        )
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `correlation is transitive across a chain`() {
        // A~B strong, B~C strong, A~C weak: all three still unwind together.
        val clusters = builder.build(
            exposureBySymbol = mapOf("A" to 10.0, "B" to 20.0, "C" to 30.0),
            matrix = matrix(
                pair("A", "B", 0.85),
                pair("B", "C", 0.80),
                pair("A", "C", 0.10),
            ),
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf("A", "B", "C"), clusters.first().symbols)
        assertEquals(60.0, clusters.first().combinedExposurePercent, 1e-9)
    }

    @Test
    fun `negative correlation keeps its sign and reads as a hedge`() {
        val clusters = builder.build(
            exposureBySymbol = mapOf("EURUSD" to 40.0, "USDCHF" to 40.0),
            matrix = matrix(pair("EURUSD", "USDCHF", -0.95)),
        )
        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertEquals(-0.95, cluster.peakCorrelation, 1e-9)
        assertTrue("inverse correlation must read as a hedge", cluster.isHedge)
    }

    @Test
    fun `pairs involving unheld symbols are ignored`() {
        // Two symbols ARE held (so the size>=2 guard passes), but the strong
        // correlation is with GBPUSD, which is not held. USDJPY is held yet
        // uncorrelated. Nothing may cluster.
        val clusters = builder.build(
            exposureBySymbol = mapOf("EURUSD" to 50.0, "USDJPY" to 20.0),
            matrix = matrix(
                pair("EURUSD", "GBPUSD", 0.95),
                pair("EURUSD", "USDJPY", 0.10),
            ),
        )
        assertTrue(
            "a strong correlation to an unheld symbol must not create a cluster",
            clusters.isEmpty(),
        )
    }

    @Test
    fun `separate clusters are reported independently and sorted by exposure`() {
        val clusters = builder.build(
            exposureBySymbol = mapOf("A" to 5.0, "B" to 5.0, "X" to 40.0, "Y" to 40.0),
            matrix = matrix(
                pair("A", "B", 0.90),
                pair("X", "Y", 0.88),
            ),
        )
        assertEquals(2, clusters.size)
        assertEquals(listOf("X", "Y"), clusters.first().symbols)
        assertEquals(80.0, clusters.first().combinedExposurePercent, 1e-9)
        assertEquals(listOf("A", "B"), clusters[1].symbols)
    }

    @Test
    fun `null matrix or single position yields no clusters`() {
        assertTrue(builder.build(mapOf("A" to 10.0, "B" to 10.0), null).isEmpty())
        assertTrue(builder.build(mapOf("A" to 10.0), matrix(pair("A", "B", 0.9))).isEmpty())
        assertTrue(builder.build(emptyMap(), matrix(pair("A", "B", 0.9))).isEmpty())
    }

    @Test
    fun `peak correlation reflects the strongest link in the cluster`() {
        val clusters = builder.build(
            exposureBySymbol = mapOf("A" to 10.0, "B" to 10.0, "C" to 10.0),
            matrix = matrix(
                pair("A", "B", 0.75),
                pair("B", "C", 0.97),
            ),
        )
        assertEquals(0.97, clusters.first().peakCorrelation, 1e-9)
    }
}
