package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PatternDiscoveryEngine.
 * Tests cover time-based patterns, volatility clusters, price-level reactions,
 * session profiles, volatility regime, and edge cases.
 */
class PatternDiscoveryEngineTest {

    private val engine = PatternDiscoveryEngine()

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Creates a base candle at the given hour (UTC) and bar index.
     * Default: small-range candle (range = 0.0010) with normal volume.
     */
    private fun makeCandle(
        index: Int,
        hour: Int,
        open: Double = 1.1000,
        high: Double = 1.1005,
        low: Double = 0.9995,
        close: Double = 1.1003,
        volume: Double = 1000.0,
        dayOffset: Int = 0
    ): Candle {
        // Place each candle at a specific hour on successive days
        val timestamp = (dayOffset.toLong() * 86_400_000L) + (hour.toLong() * 3_600_000L)
        return Candle(
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    /**
     * Creates candles where bars at targetHour have 3x normal range (large moves),
     * while all other bars have a small range. This should trigger TIME_BASED pattern detection.
     */
    private fun createTimeBasedTestData(size: Int = 200, targetHour: Int = 10): List<Candle> {
        val candles = mutableListOf<Candle>()
        val basePrice = 1.1000
        val normalRange = 0.0010
        val largeRange = 0.0050  // 5x normal to ensure > 1.5 ATR

        for (i in 0 until size) {
            val day = i / 24
            val hour = i % 24
            val price = basePrice + (i * 0.00001) // slight uptrend

            if (hour == targetHour) {
                // Large bullish move at the target hour
                candles.add(
                    Candle(
                        timestamp = day.toLong() * 86_400_000L + hour.toLong() * 3_600_000L,
                        open = price,
                        high = price + largeRange,
                        low = price - largeRange * 0.1,
                        close = price + largeRange * 0.8,
                        volume = 2000.0
                    )
                )
            } else {
                // Normal small-range bar
                candles.add(
                    Candle(
                        timestamp = day.toLong() * 86_400_000L + hour.toLong() * 3_600_000L,
                        open = price,
                        high = price + normalRange * 0.5,
                        low = price - normalRange * 0.5,
                        close = price + normalRange * 0.2,
                        volume = 1000.0
                    )
                )
            }
        }
        return candles
    }

    /**
     * Creates candles where bars 80-95 have 3x normal range to form a volatility cluster.
     */
    private fun createVolatilityClusterData(size: Int = 200): List<Candle> {
        val candles = mutableListOf<Candle>()
        val basePrice = 1.1000
        val normalRange = 0.0010
        val highRange = 0.0060  // 6x normal range for cluster

        for (i in 0 until size) {
            val price = basePrice + (i * 0.00002)
            val hour = i % 24
            val day = i / 24

            val isCluster = i in 80..95
            val range = if (isCluster) highRange else normalRange

            candles.add(
                Candle(
                    timestamp = day.toLong() * 86_400_000L + hour.toLong() * 3_600_000L,
                    open = price,
                    high = price + range * 0.6,
                    low = price - range * 0.4,
                    close = price + range * 0.3,
                    volume = if (isCluster) 3000.0 else 1000.0
                )
            )
        }
        return candles
    }

    /**
     * Creates candles that oscillate around a key price level, touching it 5+ times
     * with reversals to trigger PRICE_LEVEL_REACTION detection.
     */
    private fun createPriceLevelReactionData(size: Int = 200): List<Candle> {
        val candles = mutableListOf<Candle>()
        val supportLevel = 1.1000
        val normalRange = 0.0010

        for (i in 0 until size) {
            val hour = i % 24
            val day = i / 24

            // Oscillate: approach support level, touch it, then bounce away
            val cycleLength = 20
            val posInCycle = i % cycleLength

            val price = when {
                // Move down toward support
                posInCycle < 8 -> supportLevel + normalRange * (8 - posInCycle)
                // Touch support and reverse (bars close near the level)
                posInCycle in 8..10 -> supportLevel + normalRange * 0.1
                // Bounce away from support
                else -> supportLevel + normalRange * (posInCycle - 10) * 0.8
            }

            val range = normalRange * 1.2
            candles.add(
                Candle(
                    timestamp = day.toLong() * 86_400_000L + hour.toLong() * 3_600_000L,
                    open = price - range * 0.1,
                    high = price + range * 0.5,
                    low = price - range * 0.5,
                    close = price + range * 0.2,
                    volume = 1000.0
                )
            )
        }
        return candles
    }

    /**
     * Creates candles spread across different hours for session profiling.
     */
    private fun createSessionData(size: Int = 200): List<Candle> {
        val candles = mutableListOf<Candle>()
        val basePrice = 1.1000

        for (i in 0 until size) {
            val hour = i % 24
            val day = i / 24
            val price = basePrice + (i * 0.00001)

            // London hours (7-16) have larger ranges, NY (13-22) medium, Tokyo (0-7) smaller
            val range = when {
                hour in 13..16 -> 0.0040  // Overlap: highest volatility
                hour in 7..12 -> 0.0030   // London
                hour in 17..22 -> 0.0025  // NY only
                else -> 0.0010            // Tokyo: lowest volatility
            }

            candles.add(
                Candle(
                    timestamp = day.toLong() * 86_400_000L + hour.toLong() * 3_600_000L,
                    open = price,
                    high = price + range * 0.6,
                    low = price - range * 0.4,
                    close = price + range * 0.2,
                    volume = if (hour in 7..16) 2000.0 else 800.0
                )
            )
        }
        return candles
    }

    // ========================================================================
    // Time-based pattern tests
    // ========================================================================

    @Test
    fun `discovers time-based pattern when specific hour has consistently large moves`() {
        val candles = createTimeBasedTestData(size = 200, targetHour = 10)
        val report = engine.discover(candles, "EURUSD", Timeframe.H1)

        val timePatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.TIME_BASED
        }
        assertTrue("Should discover at least one time-based pattern", timePatterns.isNotEmpty())

        val pattern = timePatterns.first()
        assertTrue("Pattern name should mention hour 10", pattern.name.contains("10"))
        assertTrue("Occurrences should be at least 3", pattern.occurrences >= 3)
        assertTrue("Reliability should be between 0 and 1", pattern.reliability in 0.0..1.0)
        assertEquals(Direction.BULLISH, pattern.tradableDirection)
    }

    @Test
    fun `time-based pattern includes description and reliability`() {
        val candles = createTimeBasedTestData(size = 200, targetHour = 14)
        val report = engine.discover(candles, "GBPUSD", Timeframe.M15)

        val timePatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.TIME_BASED
        }
        if (timePatterns.isNotEmpty()) {
            val pattern = timePatterns.first()
            assertTrue("Description should not be empty", pattern.description.isNotBlank())
            assertTrue("Reliability in valid range", pattern.reliability in 0.0..1.0)
        }
    }

    // ========================================================================
    // Volatility cluster tests
    // ========================================================================

    @Test
    fun `detects volatility clusters in data with high-ATR section`() {
        val candles = createVolatilityClusterData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        val volPatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.VOLATILITY_CLUSTER
        }
        assertTrue("Should detect at least one volatility cluster", volPatterns.isNotEmpty())

        val cluster = volPatterns.first()
        assertTrue("Occurrences should be positive", cluster.occurrences > 0)
        assertTrue("Description should mention clusters", cluster.description.contains("cluster", ignoreCase = true))
    }

    @Test
    fun `volatility cluster pattern has valid reliability score`() {
        val candles = createVolatilityClusterData(size = 200)
        val report = engine.discover(candles, "USDJPY", Timeframe.H1)

        val volPatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.VOLATILITY_CLUSTER
        }
        for (pattern in volPatterns) {
            assertTrue("Reliability should be in [0, 1]", pattern.reliability in 0.0..1.0)
        }
    }

    // ========================================================================
    // Price-level reaction tests
    // ========================================================================

    @Test
    fun `identifies price-level reactions from oscillating data`() {
        val candles = createPriceLevelReactionData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        val priceLevelPatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.PRICE_LEVEL_REACTION
        }
        assertTrue(
            "Should detect at least one price-level reaction",
            priceLevelPatterns.isNotEmpty()
        )

        val pattern = priceLevelPatterns.first()
        assertTrue("Should have at least 3 occurrences", pattern.occurrences >= 3)
        assertTrue("Reliability should be valid", pattern.reliability in 0.0..1.0)
        assertTrue("avgMoveAfter should be non-negative", pattern.avgMoveAfter >= 0.0)
    }

    @Test
    fun `price-level reaction includes descriptive information`() {
        val candles = createPriceLevelReactionData(size = 200)
        val report = engine.discover(candles, "GBPUSD", Timeframe.H1)

        val priceLevelPatterns = report.discoveredPatterns.filter {
            it.type == DiscoveredPatternType.PRICE_LEVEL_REACTION
        }
        for (pattern in priceLevelPatterns) {
            assertTrue("Description should not be empty", pattern.description.isNotBlank())
            assertTrue("Name should reference price", pattern.name.contains("Price reaction", ignoreCase = true))
        }
    }

    // ========================================================================
    // Session profile tests
    // ========================================================================

    @Test
    fun `session profiles have valid statistics`() {
        val candles = createSessionData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        assertTrue("Should have session profiles", report.sessionProfiles.isNotEmpty())

        for (profile in report.sessionProfiles) {
            assertTrue("Session name should be known",
                profile.session in listOf("TOKYO", "LONDON", "NEW_YORK", "OVERLAP"))
            assertTrue("Average range should be positive", profile.avgRange > 0.0)
            assertTrue("Average volume should be positive", profile.avgVolume > 0.0)
            assertTrue("Volatility rank should be 1-4", profile.volatilityRank in 1..4)
            assertNotNull("Directional bias should not be null", profile.directionalBias)
        }
    }

    @Test
    fun `session profiles are ranked by volatility`() {
        val candles = createSessionData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.H1)

        if (report.sessionProfiles.size >= 2) {
            val ranks = report.sessionProfiles.map { it.volatilityRank }
            // Ranks should be unique and sequential starting from 1
            assertEquals("Ranks should be unique", ranks.size, ranks.toSet().size)
            assertEquals("First rank should be 1", 1, ranks.min())

            // Higher-ranked sessions should have higher avgRange
            val sorted = report.sessionProfiles.sortedBy { it.volatilityRank }
            for (i in 0 until sorted.size - 1) {
                assertTrue(
                    "Rank ${sorted[i].volatilityRank} should have >= range than rank ${sorted[i + 1].volatilityRank}",
                    sorted[i].avgRange >= sorted[i + 1].avgRange
                )
            }
        }
    }

    // ========================================================================
    // Volatility regime tests
    // ========================================================================

    @Test
    fun `volatility regime classification is correct for normal data`() {
        val candles = createSessionData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        assertNotNull("Volatility regime should not be null", report.volatilityRegime)
        assertTrue("Average ATR should be positive", report.volatilityRegime.avgAtr > 0.0)
        assertTrue(
            "ATR percentile should be in 0-100",
            report.volatilityRegime.atrPercentile in 0.0..100.0
        )
        assertTrue(
            "Expanding/contracting should be valid",
            report.volatilityRegime.expandingOrContracting in listOf("expanding", "contracting", "stable")
        )
    }

    @Test
    fun `volatility regime detects correct level`() {
        // Create data where the last bars have very high volatility
        val candles = mutableListOf<Candle>()
        val basePrice = 1.1000

        // 180 bars of low volatility
        for (i in 0 until 180) {
            val price = basePrice + (i * 0.00001)
            candles.add(
                Candle(
                    timestamp = (i / 24).toLong() * 86_400_000L + (i % 24).toLong() * 3_600_000L,
                    open = price,
                    high = price + 0.0005,
                    low = price - 0.0005,
                    close = price + 0.0002,
                    volume = 1000.0
                )
            )
        }
        // 20 bars of extremely high volatility at the end
        for (i in 180 until 200) {
            val price = basePrice + (i * 0.00001)
            candles.add(
                Candle(
                    timestamp = (i / 24).toLong() * 86_400_000L + (i % 24).toLong() * 3_600_000L,
                    open = price,
                    high = price + 0.0100,
                    low = price - 0.0100,
                    close = price + 0.0050,
                    volume = 5000.0
                )
            )
        }

        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        // With high ATR at the end, regime should be HIGH or EXTREME
        assertTrue(
            "Volatility regime should be HIGH or EXTREME",
            report.volatilityRegime.current == VolatilityLevel.HIGH ||
                report.volatilityRegime.current == VolatilityLevel.EXTREME
        )
        assertEquals("expanding", report.volatilityRegime.expandingOrContracting)
    }

    // ========================================================================
    // Edge case tests
    // ========================================================================

    @Test
    fun `short input produces empty graceful report`() {
        val candles = (0 until 30).map { i ->
            Candle(
                timestamp = i.toLong() * 3_600_000L,
                open = 1.1000,
                high = 1.1010,
                low = 1.0990,
                close = 1.1005,
                volume = 1000.0
            )
        }

        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        assertTrue("Discovered patterns should be empty", report.discoveredPatterns.isEmpty())
        assertTrue("Session profiles should be empty", report.sessionProfiles.isEmpty())
        assertTrue("Recurring behaviors should be empty", report.recurringBehaviors.isEmpty())
        assertTrue("Summary should mention insufficient data",
            report.summary.contains("Insufficient", ignoreCase = true))
    }

    @Test
    fun `empty input produces empty report without crashing`() {
        val report = engine.discover(emptyList(), "EURUSD", Timeframe.H1)

        assertEquals("EURUSD", report.symbol)
        assertEquals(Timeframe.H1, report.timeframe)
        assertTrue("Discovered patterns should be empty", report.discoveredPatterns.isEmpty())
        assertTrue("Session profiles should be empty", report.sessionProfiles.isEmpty())
        assertTrue("Summary should mention insufficient data",
            report.summary.contains("Insufficient", ignoreCase = true))
    }

    @Test
    fun `flat price data does not crash`() {
        // All candles with identical OHLC
        val candles = (0 until 100).map { i ->
            Candle(
                timestamp = (i / 24).toLong() * 86_400_000L + (i % 24).toLong() * 3_600_000L,
                open = 1.1000,
                high = 1.1000,
                low = 1.1000,
                close = 1.1000,
                volume = 1000.0
            )
        }

        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        // Should not crash and should return a valid report
        assertNotNull("Report should not be null", report)
        assertEquals("EURUSD", report.symbol)
    }

    // ========================================================================
    // Summary and report structure tests
    // ========================================================================

    @Test
    fun `report summary includes volatility regime info`() {
        val candles = createSessionData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.H1)

        assertTrue("Summary should not be empty", report.summary.isNotBlank())
        assertTrue(
            "Summary should mention volatility",
            report.summary.contains("volatility", ignoreCase = true) ||
                report.summary.contains("pattern", ignoreCase = true)
        )
    }

    @Test
    fun `recurring behaviors have valid fields`() {
        val candles = createVolatilityClusterData(size = 200)
        val report = engine.discover(candles, "EURUSD", Timeframe.M15)

        for (behavior in report.recurringBehaviors) {
            assertTrue("Description should not be empty", behavior.description.isNotBlank())
            assertTrue("Frequency should be non-negative", behavior.frequency >= 0)
            assertTrue("Predictive value should be in [0, 1]", behavior.predictiveValue in 0.0..1.0)
        }
    }
}
