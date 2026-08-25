package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Causal execution geometry for confirmed SMT divergences.
 *
 * [SmtDivergenceDetector] deliberately reports market context only. This class
 * is the single adapter that turns that context into an executable setup for
 * live, replay and backtest surfaces. It evaluates a bounded prefix ending at
 * [index], accepts only a divergence confirmed on that right edge, and derives
 * risk entirely from bars already closed at confirmation time.
 */
@Singleton
class SmtSignalEngine @Inject constructor(
    private val detector: SmtDivergenceDetector,
) {

    data class Config(
        val detectorConfig: SmtConfig = SmtConfig(),
        val analysisWindow: Int = DEFAULT_ANALYSIS_WINDOW,
        val riskLookback: Int = 14,
        val stopBufferRangeMultiple: Double = 0.25,
        val rewardRisk: Double = 2.0,
    ) {
        init {
            require(analysisWindow >= MIN_ANALYSIS_WINDOW)
            require(riskLookback >= 1)
            require(stopBufferRangeMultiple.isFinite() && stopBufferRangeMultiple >= 0.0)
            require(rewardRisk.isFinite() && rewardRisk > 0.0)
        }
    }

    fun signalAt(
        primarySymbol: String,
        primaryCandles: List<Candle>,
        correlatedCandles: Map<String, List<Candle>>,
        index: Int,
        config: Config = Config(),
    ): StrategySignal? {
        if (index !in primaryCandles.indices || index < MIN_ANALYSIS_WINDOW - 1) return null
        if (correlatedCandles.isEmpty()) return null

        val start = (index - config.analysisWindow + 1).coerceAtLeast(0)
        val visiblePrimary = primaryCandles.subList(start, index + 1)
        val firstTimestamp = visiblePrimary.first().timestamp
        val confirmationTimestamp = visiblePrimary.last().timestamp
        val visiblePeers = correlatedCandles.mapNotNull { (symbol, candles) ->
            val visible = candles
                .asSequence()
                .filter { it.timestamp in firstTimestamp..confirmationTimestamp }
                .toList()
                .takeLast(config.analysisWindow)
            if (visible.size >= MIN_ANALYSIS_WINDOW) symbol to visible else null
        }.toMap()
        if (visiblePeers.isEmpty()) return null

        val divergence = detector.detect(
            primarySymbol = primarySymbol,
            primaryCandles = visiblePrimary,
            correlatedCandles = visiblePeers,
            config = config.detectorConfig,
        )
            .asSequence()
            .filter { it.confirmationIndex == visiblePrimary.lastIndex }
            .maxByOrNull { it.confidence }
            ?: return null

        val confirmation = visiblePrimary.last()
        val pivot = visiblePrimary.getOrNull(divergence.primaryIndex) ?: return null
        val averageRange = averageRange(visiblePrimary, visiblePrimary.lastIndex, config.riskLookback)
            ?: return null
        val buffer = averageRange * config.stopBufferRangeMultiple
        val entry = confirmation.close
        val stop = when (divergence.direction) {
            Direction.BULLISH -> minOf(pivot.low, divergence.primaryPrice) - buffer
            Direction.BEARISH -> maxOf(pivot.high, divergence.primaryPrice) + buffer
        }
        if (!entry.isFinite() || entry <= 0.0 || !stop.isFinite() || stop <= 0.0) return null
        val correctSide = when (divergence.direction) {
            Direction.BULLISH -> stop < entry
            Direction.BEARISH -> stop > entry
        }
        if (!correctSide) return null

        val risk = abs(entry - stop)
        if (!risk.isFinite() || risk <= MIN_PRICE_DISTANCE) return null
        val target = when (divergence.direction) {
            Direction.BULLISH -> entry + risk * config.rewardRisk
            Direction.BEARISH -> entry - risk * config.rewardRisk
        }
        if (!target.isFinite() || target <= 0.0) return null

        return StrategySignal(
            index = index,
            timestamp = confirmation.timestamp,
            direction = divergence.direction,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = divergence.confidence.roundToInt().coerceIn(0, 100),
            setupType = "SMT ${divergence.peerSymbol} · ${divergence.type.name}",
        )
    }

    fun strategyFunction(
        primarySymbol: String,
        correlatedCandles: Map<String, List<Candle>>,
        config: Config = Config(),
    ): StrategyFunction = { candles, index ->
        signalAt(primarySymbol, candles, correlatedCandles, index, config)
    }

    private fun averageRange(candles: List<Candle>, through: Int, lookback: Int): Double? {
        val start = (through - lookback + 1).coerceAtLeast(0)
        val ranges = (start..through).mapNotNull { candleIndex ->
            (candles[candleIndex].high - candles[candleIndex].low)
                .takeIf { it.isFinite() && it > MIN_PRICE_DISTANCE }
        }
        return ranges.takeIf { it.isNotEmpty() }?.average()
    }

    private companion object {
        const val DEFAULT_ANALYSIS_WINDOW = 240
        const val MIN_ANALYSIS_WINDOW = 50
        const val MIN_PRICE_DISTANCE = 1e-12
    }
}
