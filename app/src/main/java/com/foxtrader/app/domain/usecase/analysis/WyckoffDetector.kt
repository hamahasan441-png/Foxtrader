package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.abs

/**
 * Wyckoff Phase Detector — identifies accumulation/distribution schematics.
 *
 * Simplified heuristic detection of the Wyckoff market cycle:
 * - Accumulation: range-bound after downtrend, rising volume on up-bars
 * - Markup: breakout with expansion
 * - Distribution: range-bound after uptrend, rising volume on down-bars
 * - Markdown: breakdown with expansion
 *
 * Uses price range compression + volume behavior + prior trend.
 */
class WyckoffDetector @Inject constructor() {

    enum class WyckoffPhase { ACCUMULATION, MARKUP, DISTRIBUTION, MARKDOWN, UNDEFINED }

    data class WyckoffResult(
        val phase: WyckoffPhase,
        val confidence: Double,      // 0-100
        val rangeHigh: Double,
        val rangeLow: Double,
        val description: String,
    )

    fun detect(candles: List<Candle>, lookback: Int = 50): WyckoffResult {
        if (candles.size < lookback) {
            return WyckoffResult(WyckoffPhase.UNDEFINED, 0.0, 0.0, 0.0, "Insufficient data")
        }
        val window = candles.takeLast(lookback)
        val rangeHigh = window.maxOf { it.high }
        val rangeLow = window.minOf { it.low }
        val range = (rangeHigh - rangeLow).coerceAtLeast(1e-9)

        // Prior trend: compare first third vs the window start
        val priorStart = candles.getOrNull(candles.size - lookback - lookback / 2)?.close
            ?: window.first().close
        val windowStart = window.first().close
        val priorTrendUp = windowStart > priorStart

        // Volume behavior: correlation of volume with up vs down bars
        val upVolume = window.filter { it.isBullish }.sumOf { it.volume }
        val downVolume = window.filter { !it.isBullish }.sumOf { it.volume }

        // Range compression: recent range vs full window range
        val recentRange = window.takeLast(lookback / 3).let { it.maxOf { c -> c.high } - it.minOf { c -> c.low } }
        val isCompressed = recentRange < range * 0.5

        // Current position within the range
        val lastClose = window.last().close
        val posInRange = (lastClose - rangeLow) / range

        val (phase, confidence, desc) = when {
            isCompressed && !priorTrendUp && upVolume > downVolume ->
                Triple(WyckoffPhase.ACCUMULATION, 70.0, "Range after downtrend, buying pressure")
            isCompressed && priorTrendUp && downVolume > upVolume ->
                Triple(WyckoffPhase.DISTRIBUTION, 70.0, "Range after uptrend, selling pressure")
            !isCompressed && posInRange > 0.8 && priorTrendUp ->
                Triple(WyckoffPhase.MARKUP, 60.0, "Breakout expansion higher")
            !isCompressed && posInRange < 0.2 && !priorTrendUp ->
                Triple(WyckoffPhase.MARKDOWN, 60.0, "Breakdown expansion lower")
            else -> Triple(WyckoffPhase.UNDEFINED, 30.0, "No clear Wyckoff phase")
        }

        return WyckoffResult(phase, confidence, rangeHigh, rangeLow, desc)
    }
}
