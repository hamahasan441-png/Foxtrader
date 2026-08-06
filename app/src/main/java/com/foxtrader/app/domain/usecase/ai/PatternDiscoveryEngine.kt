package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// ============================================================================
// Data classes
// ============================================================================

enum class DiscoveredPatternType {
    TIME_BASED,
    VOLATILITY_CLUSTER,
    PRICE_LEVEL_REACTION,
    SESSION_TENDENCY,
    RANGE_EXPANSION
}

data class DiscoveredPattern(
    val name: String,
    val type: DiscoveredPatternType,
    val occurrences: Int,
    val reliability: Double,
    val description: String,
    val tradableDirection: Direction?,
    val avgMoveAfter: Double
)

data class SessionProfile(
    val session: String,
    val avgRange: Double,
    val avgVolume: Double,
    val directionalBias: Bias,
    val volatilityRank: Int
)

enum class VolatilityLevel { LOW, NORMAL, HIGH, EXTREME }

data class VolatilityRegime(
    val current: VolatilityLevel,
    val avgAtr: Double,
    val atrPercentile: Double,
    val expandingOrContracting: String
)

data class RecurringBehavior(
    val description: String,
    val frequency: Int,
    val lastOccurrence: Int,
    val predictiveValue: Double
)

data class PatternDiscoveryReport(
    val symbol: String,
    val timeframe: Timeframe,
    val discoveredPatterns: List<DiscoveredPattern>,
    val sessionProfiles: List<SessionProfile>,
    val volatilityRegime: VolatilityRegime,
    val recurringBehaviors: List<RecurringBehavior>,
    val summary: String
)

// ============================================================================
// Engine
// ============================================================================

/**
 * Statistical Pattern Discovery Engine.
 *
 * Finds recurring price behaviors beyond candlestick patterns: time-based
 * patterns, volatility clusters, price-level reactions, and session-specific
 * tendencies. All analysis is rule-based/algorithmic with no external
 * dependencies.
 */
@Singleton
class PatternDiscoveryEngine @Inject constructor() {

    companion object {
        private const val MIN_BARS = 50
        private const val ATR_PERIOD = 14
        private const val LARGE_MOVE_MULTIPLIER = 1.5
        private const val TIME_PATTERN_THRESHOLD = 0.50
        private const val TIME_PATTERN_MIN_OCCURRENCES = 3
        private const val VOLATILITY_CLUSTER_MIN_BARS = 3
        private const val SQUEEZE_MIN_BARS = 5
        private const val SQUEEZE_ATR_RATIO = 0.7
        private const val EXPANSION_ATR_RATIO = 1.5
        private const val PRICE_LEVEL_MIN_REVERSALS = 3
        private const val PRICE_LEVEL_BUCKET_FACTOR = 0.5
        private const val REVERSAL_MOVE_THRESHOLD = 0.5
    }

    /**
     * Discover statistical patterns in the given candle data.
     */
    fun discover(candles: List<Candle>, symbol: String, timeframe: Timeframe): PatternDiscoveryReport {
        if (candles.size < MIN_BARS) {
            return emptyReport(symbol, timeframe)
        }

        val atr = TechnicalIndicators.calculateATR(candles, ATR_PERIOD)

        val timePatterns = discoverTimeBasedPatterns(candles, atr)
        val volatilityPatterns = discoverVolatilityClusters(candles, atr)
        val priceLevelPatterns = discoverPriceLevelReactions(candles, atr)
        val sessionProfiles = buildSessionProfiles(candles)
        val volatilityRegime = computeVolatilityRegime(atr)
        val recurringBehaviors = findRecurringBehaviors(candles, atr)

        val allPatterns = timePatterns + volatilityPatterns + priceLevelPatterns

        val summary = buildSummary(allPatterns, volatilityRegime, candles.size)

        return PatternDiscoveryReport(
            symbol = symbol,
            timeframe = timeframe,
            discoveredPatterns = allPatterns,
            sessionProfiles = sessionProfiles,
            volatilityRegime = volatilityRegime,
            recurringBehaviors = recurringBehaviors,
            summary = summary
        )
    }

    // ========================================================================
    // Time-based pattern discovery
    // ========================================================================

    private fun discoverTimeBasedPatterns(
        candles: List<Candle>,
        atr: DoubleArray
    ): List<DiscoveredPattern> {
        val patterns = mutableListOf<DiscoveredPattern>()

        // Group candles by hour-of-day
        data class HourStats(
            var totalBars: Int = 0,
            var largeMoves: Int = 0,
            var bullishLargeMoves: Int = 0,
            var bearishLargeMoves: Int = 0,
            var totalMoveAfter: Double = 0.0
        )

        val hourStats = Array(24) { HourStats() }

        for (i in candles.indices) {
            val hour = ((candles[i].timestamp % 86_400_000L) / 3_600_000L).toInt()
            val atrVal = atr[i]
            if (atrVal <= 0.0) continue

            hourStats[hour].totalBars++
            val barRange = candles[i].high - candles[i].low

            if (barRange > LARGE_MOVE_MULTIPLIER * atrVal) {
                hourStats[hour].largeMoves++
                if (candles[i].isBullish) {
                    hourStats[hour].bullishLargeMoves++
                } else {
                    hourStats[hour].bearishLargeMoves++
                }
                // Compute move after in ATR units
                if (i + 1 < candles.size && atrVal > 0.0) {
                    val moveAfter = abs(candles[i + 1].close - candles[i].close) / atrVal
                    hourStats[hour].totalMoveAfter += moveAfter
                }
            }
        }

        for (hour in 0..23) {
            val stats = hourStats[hour]
            if (stats.totalBars == 0) continue
            val proportion = stats.largeMoves.toDouble() / stats.totalBars
            if (proportion > TIME_PATTERN_THRESHOLD && stats.largeMoves >= TIME_PATTERN_MIN_OCCURRENCES) {
                val isBullishDominant = stats.bullishLargeMoves >= stats.bearishLargeMoves
                val direction = if (isBullishDominant) Direction.BULLISH else Direction.BEARISH
                val dominantCount = if (isBullishDominant) stats.bullishLargeMoves else stats.bearishLargeMoves
                val reliability = dominantCount.toDouble() / stats.largeMoves
                val avgMove = if (stats.largeMoves > 0) stats.totalMoveAfter / stats.largeMoves else 0.0

                patterns.add(
                    DiscoveredPattern(
                        name = "Large moves at hour $hour UTC",
                        type = DiscoveredPatternType.TIME_BASED,
                        occurrences = stats.largeMoves,
                        reliability = reliability.coerceIn(0.0, 1.0),
                        description = "Hour $hour UTC consistently produces large moves " +
                            "(${(proportion * 100).toInt()}% of bars). " +
                            "Predominant direction: ${direction.name.lowercase()}.",
                        tradableDirection = direction,
                        avgMoveAfter = avgMove
                    )
                )
            }
        }
        return patterns
    }

    // ========================================================================
    // Volatility clustering
    // ========================================================================

    private fun discoverVolatilityClusters(
        candles: List<Candle>,
        atr: DoubleArray
    ): List<DiscoveredPattern> {
        val patterns = mutableListOf<DiscoveredPattern>()

        // Compute 20-bar rolling average of ATR
        val avgAtr20 = computeRollingAverage(atr, 20)

        // Detect sequences of 3+ consecutive bars where ATR > 1.5 * 20-bar average
        var clusterCount = 0
        var currentClusterLen = 0
        var totalClusterBars = 0

        for (i in candles.indices) {
            if (avgAtr20[i] > 0.0 && atr[i] > EXPANSION_ATR_RATIO * avgAtr20[i]) {
                currentClusterLen++
            } else {
                if (currentClusterLen >= VOLATILITY_CLUSTER_MIN_BARS) {
                    clusterCount++
                    totalClusterBars += currentClusterLen
                }
                currentClusterLen = 0
            }
        }
        // Handle trailing cluster
        if (currentClusterLen >= VOLATILITY_CLUSTER_MIN_BARS) {
            clusterCount++
            totalClusterBars += currentClusterLen
        }

        if (clusterCount > 0) {
            val avgClusterLen = if (clusterCount > 0) totalClusterBars.toDouble() / clusterCount else 0.0
            patterns.add(
                DiscoveredPattern(
                    name = "Volatility clusters detected",
                    type = DiscoveredPatternType.VOLATILITY_CLUSTER,
                    occurrences = clusterCount,
                    reliability = (clusterCount.toDouble() / (candles.size / 20.0)).coerceIn(0.0, 1.0),
                    description = "$clusterCount volatility clusters found (avg length: " +
                        "${avgClusterLen.toInt()} bars). ATR exceeded 1.5x the 20-bar average.",
                    tradableDirection = null,
                    avgMoveAfter = avgClusterLen
                )
            )
        }

        // Detect squeeze -> expansion patterns
        var squeezeExpansionCount = 0
        var lastExpansionIndex = -1
        var squeezeLen = 0

        for (i in candles.indices) {
            if (avgAtr20[i] > 0.0 && atr[i] < SQUEEZE_ATR_RATIO * avgAtr20[i]) {
                squeezeLen++
            } else {
                if (squeezeLen >= SQUEEZE_MIN_BARS &&
                    avgAtr20[i] > 0.0 &&
                    atr[i] > EXPANSION_ATR_RATIO * avgAtr20[i]
                ) {
                    squeezeExpansionCount++
                    lastExpansionIndex = i
                }
                squeezeLen = 0
            }
        }

        if (squeezeExpansionCount > 0) {
            patterns.add(
                DiscoveredPattern(
                    name = "Squeeze-to-expansion pattern",
                    type = DiscoveredPatternType.VOLATILITY_CLUSTER,
                    occurrences = squeezeExpansionCount,
                    reliability = (squeezeExpansionCount.toDouble() / (candles.size / 50.0)).coerceIn(0.0, 1.0),
                    description = "$squeezeExpansionCount instances where volatility " +
                        "contraction (5+ bars of low ATR) was followed by expansion.",
                    tradableDirection = null,
                    avgMoveAfter = 0.0
                )
            )
        }

        return patterns
    }

    // ========================================================================
    // Price-level reaction
    // ========================================================================

    private fun discoverPriceLevelReactions(
        candles: List<Candle>,
        atr: DoubleArray
    ): List<DiscoveredPattern> {
        val patterns = mutableListOf<DiscoveredPattern>()

        // Determine bucket size based on average ATR
        val validAtr = atr.filter { it > 0.0 }
        if (validAtr.isEmpty()) return patterns
        val avgAtrVal = validAtr.average()
        val bucketSize = avgAtrVal * PRICE_LEVEL_BUCKET_FACTOR
        if (bucketSize <= 0.0) return patterns

        // Track touches and reversals at each price level
        data class LevelStats(
            var touches: Int = 0,
            var reversals: Int = 0,
            var totalMoveAfter: Double = 0.0
        )

        val levelMap = mutableMapOf<Long, LevelStats>()

        for (i in candles.indices) {
            val level = (candles[i].close / bucketSize).toLong()
            val stats = levelMap.getOrPut(level) { LevelStats() }
            stats.touches++

            // Check if reversal occurred: bar N touches level AND bar N+1 or N+2
            // moved away by at least 0.5 ATR
            val currentAtr = if (atr[i] > 0.0) atr[i] else avgAtrVal
            val reversalThreshold = REVERSAL_MOVE_THRESHOLD * currentAtr

            val reversed = when {
                i + 1 < candles.size -> {
                    val moveNext = abs(candles[i + 1].close - candles[i].close)
                    if (moveNext >= reversalThreshold) {
                        true
                    } else if (i + 2 < candles.size) {
                        abs(candles[i + 2].close - candles[i].close) >= reversalThreshold
                    } else {
                        false
                    }
                }
                else -> false
            }

            if (reversed) {
                stats.reversals++
                // Track average move after reversal
                if (i + 1 < candles.size && currentAtr > 0.0) {
                    val moveAfter = abs(candles[i + 1].close - candles[i].close) / currentAtr
                    stats.totalMoveAfter += moveAfter
                }
            }
        }

        // Report levels with >= 3 reversals
        for ((level, stats) in levelMap) {
            if (stats.reversals >= PRICE_LEVEL_MIN_REVERSALS) {
                val priceLevel = level * bucketSize
                val reliability = stats.reversals.toDouble() / stats.touches
                val avgMove = if (stats.reversals > 0) stats.totalMoveAfter / stats.reversals else 0.0

                patterns.add(
                    DiscoveredPattern(
                        name = "Price reaction at ${formatPrice(priceLevel)}",
                        type = DiscoveredPatternType.PRICE_LEVEL_REACTION,
                        occurrences = stats.reversals,
                        reliability = reliability.coerceIn(0.0, 1.0),
                        description = "Price reversed ${stats.reversals} times near " +
                            "${formatPrice(priceLevel)} (touched ${stats.touches} times). " +
                            "Reliability: ${(reliability * 100).toInt()}%.",
                        tradableDirection = null,
                        avgMoveAfter = avgMove
                    )
                )
            }
        }

        return patterns
    }

    // ========================================================================
    // Session profiling
    // ========================================================================

    private fun buildSessionProfiles(candles: List<Candle>): List<SessionProfile> {
        data class SessionStats(
            var totalRange: Double = 0.0,
            var totalVolume: Double = 0.0,
            var bullishBars: Int = 0,
            var bearishBars: Int = 0,
            var count: Int = 0
        )

        val sessions = mapOf(
            "TOKYO" to (0..7),
            "LONDON" to (7..16),
            "NEW_YORK" to (13..22),
            "OVERLAP" to (13..16)
        )

        val statsMap = sessions.keys.associateWith { SessionStats() }.toMutableMap()

        for (candle in candles) {
            val hour = ((candle.timestamp % 86_400_000L) / 3_600_000L).toInt()

            for ((session, range) in sessions) {
                if (hour in range) {
                    val stats = statsMap[session]!!
                    stats.totalRange += candle.range
                    stats.totalVolume += candle.volume
                    stats.count++
                    if (candle.isBullish) stats.bullishBars++ else stats.bearishBars++
                }
            }
        }

        // Build profiles and rank by average range descending
        val profiles = sessions.keys.mapNotNull { session ->
            val stats = statsMap[session]!!
            if (stats.count == 0) return@mapNotNull null
            val avgRange = stats.totalRange / stats.count
            val avgVolume = stats.totalVolume / stats.count
            val bias = when {
                stats.bullishBars > stats.bearishBars * 1.1 -> Bias.BULLISH
                stats.bearishBars > stats.bullishBars * 1.1 -> Bias.BEARISH
                else -> Bias.NEUTRAL
            }
            SessionProfile(
                session = session,
                avgRange = avgRange,
                avgVolume = avgVolume,
                directionalBias = bias,
                volatilityRank = 0 // placeholder, will be assigned below
            )
        }.sortedByDescending { it.avgRange }

        // Assign volatility rank (1 = highest)
        return profiles.mapIndexed { index, profile ->
            profile.copy(volatilityRank = index + 1)
        }
    }

    // ========================================================================
    // Volatility regime
    // ========================================================================

    private fun computeVolatilityRegime(atr: DoubleArray): VolatilityRegime {
        val validAtr = atr.filter { it > 0.0 }
        if (validAtr.isEmpty()) {
            return VolatilityRegime(
                current = VolatilityLevel.NORMAL,
                avgAtr = 0.0,
                atrPercentile = 50.0,
                expandingOrContracting = "stable"
            )
        }

        val currentAtr = validAtr.last()
        val avgAtr = validAtr.average()
        val sorted = validAtr.sorted()
        val percentile = (sorted.indexOfFirst { it >= currentAtr }.toDouble() / sorted.size) * 100.0

        val level = when {
            percentile < 25.0 -> VolatilityLevel.LOW
            percentile <= 75.0 -> VolatilityLevel.NORMAL
            percentile <= 90.0 -> VolatilityLevel.HIGH
            else -> VolatilityLevel.EXTREME
        }

        // Determine expanding or contracting: compare last 5 vs previous 5
        val trend = if (validAtr.size >= 10) {
            val last5 = validAtr.takeLast(5).average()
            val prev5 = validAtr.dropLast(5).takeLast(5).average()
            when {
                last5 > prev5 * 1.1 -> "expanding"
                last5 < prev5 * 0.9 -> "contracting"
                else -> "stable"
            }
        } else {
            "stable"
        }

        return VolatilityRegime(
            current = level,
            avgAtr = avgAtr,
            atrPercentile = percentile.coerceIn(0.0, 100.0),
            expandingOrContracting = trend
        )
    }

    // ========================================================================
    // Recurring behaviors
    // ========================================================================

    private fun findRecurringBehaviors(
        candles: List<Candle>,
        atr: DoubleArray
    ): List<RecurringBehavior> {
        val behaviors = mutableListOf<RecurringBehavior>()

        // Volatility expansion follows contraction
        val avgAtr20 = computeRollingAverage(atr, 20)
        var squeezeCount = 0
        var expansionAfterSqueeze = 0
        var lastExpansionIdx = 0
        var squeezeLen = 0

        for (i in candles.indices) {
            if (avgAtr20[i] > 0.0 && atr[i] < SQUEEZE_ATR_RATIO * avgAtr20[i]) {
                squeezeLen++
            } else {
                if (squeezeLen >= SQUEEZE_MIN_BARS) {
                    squeezeCount++
                    if (avgAtr20[i] > 0.0 && atr[i] > EXPANSION_ATR_RATIO * avgAtr20[i]) {
                        expansionAfterSqueeze++
                        lastExpansionIdx = i
                    }
                }
                squeezeLen = 0
            }
        }

        if (squeezeCount > 0) {
            val predictiveValue = expansionAfterSqueeze.toDouble() / squeezeCount
            behaviors.add(
                RecurringBehavior(
                    description = "Volatility expansion follows contraction " +
                        "${(predictiveValue * 100).toInt()}% of the time",
                    frequency = expansionAfterSqueeze,
                    lastOccurrence = lastExpansionIdx,
                    predictiveValue = predictiveValue.coerceIn(0.0, 1.0)
                )
            )
        }

        return behaviors
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun emptyReport(symbol: String, timeframe: Timeframe): PatternDiscoveryReport {
        return PatternDiscoveryReport(
            symbol = symbol,
            timeframe = timeframe,
            discoveredPatterns = emptyList(),
            sessionProfiles = emptyList(),
            volatilityRegime = VolatilityRegime(
                current = VolatilityLevel.NORMAL,
                avgAtr = 0.0,
                atrPercentile = 50.0,
                expandingOrContracting = "stable"
            ),
            recurringBehaviors = emptyList(),
            summary = "Insufficient data for pattern discovery (minimum $MIN_BARS bars required)."
        )
    }

    private fun computeRollingAverage(data: DoubleArray, window: Int): DoubleArray {
        val result = DoubleArray(data.size)
        var sum = 0.0
        for (i in data.indices) {
            sum += data[i]
            if (i >= window) sum -= data[i - window]
            val count = if (i >= window) window else i + 1
            result[i] = if (count > 0) sum / count else 0.0
        }
        return result
    }

    private fun buildSummary(
        patterns: List<DiscoveredPattern>,
        regime: VolatilityRegime,
        barCount: Int
    ): String {
        if (patterns.isEmpty()) {
            return "No significant patterns discovered in $barCount bars. " +
                "Current volatility: ${regime.current.name} (${regime.expandingOrContracting})."
        }
        val typeCounts = patterns.groupBy { it.type }.mapValues { it.value.size }
        val parts = typeCounts.entries.joinToString(", ") { (type, count) ->
            "$count ${type.name.lowercase().replace('_', ' ')}"
        }
        return "Discovered ${patterns.size} patterns in $barCount bars: $parts. " +
            "Current volatility: ${regime.current.name} (${regime.expandingOrContracting})."
    }

    private fun formatPrice(price: Double): String {
        return if (price < 10.0) {
            String.format("%.5f", price)
        } else {
            String.format("%.2f", price)
        }
    }
}
