package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-class signal/entry layer for FOXTRADER's RSI Orderflow Candle system.
 *
 * The underlying [RsiOrderFlow] study is a candle/volume-derived order-flow
 * proxy, not exchange footprint data. This engine turns only already-confirmed
 * divergences into deterministic trade setups. It never evaluates a divergence
 * on its hindsight pivot bar: [Signal.confirmationIndex] is always the study's
 * causal confirmation bar.
 *
 * The same [signalAt] implementation is exposed as a [StrategyFunction] so live,
 * replay and backtest can share one decision path rather than maintaining three
 * subtly different RSI Orderflow strategies.
 */
@Singleton
class RsiOrderFlowSignalEngine @Inject constructor() {

    data class Config(
        val study: RsiOrderFlow.Config = RsiOrderFlow.Config(),
        /** Minimum setup-quality score produced by the causal study. */
        val minStrength: Int = 40,
        /** Closed bars ending at confirmation used to estimate a risk buffer. */
        val riskLookback: Int = 14,
        /** Fraction of recent average candle range placed beyond the pivot. */
        val stopBufferRangeMultiple: Double = 0.25,
        /** Explicit reward target expressed as multiples of initial risk. */
        val rewardRisk: Double = 2.0,
    ) {
        init {
            require(minStrength in 0..100)
            require(riskLookback >= 1)
            require(stopBufferRangeMultiple.isFinite() && stopBufferRangeMultiple >= 0.0)
            require(rewardRisk.isFinite() && rewardRisk > 0.0)
        }
    }

    data class Signal(
        val symbol: String,
        val timeframe: Timeframe,
        val direction: Direction,
        val divergenceType: RsiOrderFlow.DivergenceType,
        val pivotIndex: Int,
        val confirmationIndex: Int,
        val timestamp: Long,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val confidence: Int,
        val rsiAtPivot: Double,
        val flowAtPivot: Double,
        val positiveVolumeCoverage: Double,
        val reasons: List<String>,
    )

    data class Analysis(
        val signals: List<Signal>,
        val positiveVolumeCoverage: Double,
    )

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: Config = Config(),
    ): Analysis {
        if (candles.isEmpty()) return Analysis(emptyList(), 0.0)
        val studyResult = RsiOrderFlow.calculate(candles, config.study)
        val normalizedSymbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" }

        val signals = studyResult.divergences
            .asSequence()
            .filter { it.confirmedIndex in candles.indices }
            .filter { it.strength >= config.minStrength }
            .mapNotNull { divergence ->
                buildSignal(
                    symbol = normalizedSymbol,
                    timeframe = timeframe,
                    candles = candles,
                    divergence = divergence,
                    volumeCoverage = studyResult.positiveVolumeCoverage,
                    config = config,
                )
            }
            .distinctBy {
                "${it.confirmationIndex}|${it.direction.name}|${it.divergenceType.name}|${it.pivotIndex}"
            }
            .sortedWith(compareBy<Signal> { it.confirmationIndex }.thenBy { it.direction.name })
            .toList()

        return Analysis(signals, studyResult.positiveVolumeCoverage)
    }

    /**
     * Return a setup only when [index] is the bar on which it first became
     * knowable. The caller may pass a longer list; this method hard-bounds the
     * study to candles[0..index] before evaluating it.
     */
    fun signalAt(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
        config: Config = Config(),
    ): StrategySignal? {
        if (index !in candles.indices) return null
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)
        val confirmed = analyze(symbol, timeframe, visible, config).signals
            .filter { it.confirmationIndex == visible.lastIndex }
            .maxByOrNull { it.confidence }
            ?: return null

        return StrategySignal(
            index = index,
            timestamp = visible[index].timestamp,
            direction = confirmed.direction,
            entry = confirmed.entry,
            stopLoss = confirmed.stopLoss,
            takeProfit = confirmed.takeProfit,
            confidence = confirmed.confidence,
            setupType = "RSI Orderflow Candle · ${confirmed.divergenceType.name}",
        )
    }

    fun strategyFunction(
        symbol: String,
        timeframe: Timeframe,
        config: Config = Config(),
    ): StrategyFunction = { candles, index ->
        signalAt(symbol, timeframe, candles, index, config)
    }

    fun minimumBars(config: Config = Config()): Int =
        maxOf(config.study.rsiPeriod, config.study.flowPeriod) +
            config.study.pivotLeft + config.study.pivotRight + config.study.minPivotSeparation + 1

    private fun buildSignal(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        divergence: RsiOrderFlow.Divergence,
        volumeCoverage: Double,
        config: Config,
    ): Signal? {
        val confirmation = candles.getOrNull(divergence.confirmedIndex) ?: return null
        val pivot = candles.getOrNull(divergence.endIndex) ?: return null
        if (!confirmation.close.isFinite() || confirmation.close <= 0.0) return null

        val averageRange = averageClosedRange(
            candles = candles,
            throughIndex = divergence.confirmedIndex,
            lookback = config.riskLookback,
        ) ?: return null
        val buffer = averageRange * config.stopBufferRangeMultiple
        if (!buffer.isFinite() || buffer < 0.0) return null

        val direction = if (divergence.bullish) Direction.BULLISH else Direction.BEARISH
        val entry = confirmation.close
        val stop = when (direction) {
            Direction.BULLISH -> pivot.low - buffer
            Direction.BEARISH -> pivot.high + buffer
        }
        if (!stop.isFinite() || stop <= 0.0) return null

        val risk = abs(entry - stop)
        if (!risk.isFinite() || risk <= MIN_PRICE_DISTANCE) return null
        val correctSide = when (direction) {
            Direction.BULLISH -> stop < entry
            Direction.BEARISH -> stop > entry
        }
        if (!correctSide) return null

        val target = when (direction) {
            Direction.BULLISH -> entry + risk * config.rewardRisk
            Direction.BEARISH -> entry - risk * config.rewardRisk
        }
        if (!target.isFinite() || target <= 0.0) return null

        val rsiDelta = divergence.endRsi - divergence.startRsi
        val flowDelta = divergence.endFlow - divergence.startFlow
        val reasons = listOf(
            "${divergence.type.name} confirmed on bar ${divergence.confirmedIndex}",
            "RSI ${formatSigned(rsiDelta)} (${format1(divergence.endRsi)})",
            "Orderflow proxy ${formatSigned(flowDelta)} (${format1(divergence.endFlow)})",
            "Causal strength ${divergence.strength}/100",
            if (volumeCoverage > 0.0) {
                "Positive-volume coverage ${format1(volumeCoverage * 100.0)}%"
            } else {
                "Provider volume unavailable; unit-weight candle proxy"
            },
        )

        return Signal(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            divergenceType = divergence.type,
            pivotIndex = divergence.endIndex,
            confirmationIndex = divergence.confirmedIndex,
            timestamp = confirmation.timestamp,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = divergence.strength,
            rsiAtPivot = divergence.endRsi,
            flowAtPivot = divergence.endFlow,
            positiveVolumeCoverage = volumeCoverage,
            reasons = reasons,
        )
    }

    /** Uses only closed bars at or before the confirmation timestamp. */
    private fun averageClosedRange(
        candles: List<Candle>,
        throughIndex: Int,
        lookback: Int,
    ): Double? {
        val start = (throughIndex - lookback + 1).coerceAtLeast(0)
        var sum = 0.0
        var count = 0
        for (i in start..throughIndex) {
            val range = candles[i].high - candles[i].low
            if (range.isFinite() && range > MIN_PRICE_DISTANCE) {
                sum += range
                count++
            }
        }
        if (count == 0) return null
        return (sum / count.toDouble()).takeIf { it.isFinite() && it > MIN_PRICE_DISTANCE }
    }

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
    private fun formatSigned(value: Double): String = String.format(java.util.Locale.US, "%+.1f", value)

    private companion object {
        const val MIN_PRICE_DISTANCE = 1e-12
    }
}
