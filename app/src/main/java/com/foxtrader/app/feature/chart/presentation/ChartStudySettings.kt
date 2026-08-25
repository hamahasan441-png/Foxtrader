package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Immutable

/**
 * Typed, finite-safe settings for chart studies.
 *
 * These values are part of [IndicatorToggles] so a settings change participates
 * in the same immutable state/equality contract as enable/disable changes. That
 * is important for the incremental chart cache: a frame computed with RSI(14)
 * must never be reused after the trader changes the study to RSI(7).
 */
@Immutable
data class ChartStudySettings(
    val ema: EmaStudySettings = EmaStudySettings(),
    val rsi: RsiStudySettings = RsiStudySettings(),
    val rsiOrderFlow: RsiOrderFlowStudySettings = RsiOrderFlowStudySettings(),
    val macd: MacdStudySettings = MacdStudySettings(),
    val bollinger: BollingerStudySettings = BollingerStudySettings(),
    val superTrend: SuperTrendStudySettings = SuperTrendStudySettings(),
    val stochastic: StochasticStudySettings = StochasticStudySettings(),
    val keltner: KeltnerStudySettings = KeltnerStudySettings(),
    val donchian: DonchianStudySettings = DonchianStudySettings(),
    val ichimoku: IchimokuStudySettings = IchimokuStudySettings(),
    val parabolicSar: ParabolicSarStudySettings = ParabolicSarStudySettings(),
    val mfi: MfiStudySettings = MfiStudySettings(),
) {
    fun sanitized(): ChartStudySettings = copy(
        ema = ema.sanitized(),
        rsi = rsi.sanitized(),
        rsiOrderFlow = rsiOrderFlow.sanitized(),
        macd = macd.sanitized(),
        bollinger = bollinger.sanitized(),
        superTrend = superTrend.sanitized(),
        stochastic = stochastic.sanitized(),
        keltner = keltner.sanitized(),
        donchian = donchian.sanitized(),
        ichimoku = ichimoku.sanitized(),
        parabolicSar = parabolicSar.sanitized(),
        mfi = mfi.sanitized(),
    )
}

@Immutable
data class EmaStudySettings(
    val fastPeriod: Int = 20,
    val slowPeriod: Int = 50,
) {
    fun sanitized(): EmaStudySettings {
        val fast = fastPeriod.coerceIn(1, MAX_PERIOD)
        val slow = slowPeriod.coerceIn(1, MAX_PERIOD)
        return if (fast <= slow) copy(fastPeriod = fast, slowPeriod = slow)
        else copy(fastPeriod = slow, slowPeriod = fast)
    }
}

@Immutable
data class RsiStudySettings(
    val period: Int = 14,
    val overbought: Double = 70.0,
    val oversold: Double = 30.0,
) {
    fun sanitized(): RsiStudySettings {
        val low = finiteOr(oversold, 30.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 70.0).coerceIn(51.0, 99.0)
        return copy(period = period.coerceIn(2, MAX_PERIOD), overbought = high, oversold = low)
    }
}

@Immutable
data class RsiOrderFlowStudySettings(
    val rsiPeriod: Int = 14,
    val flowPeriod: Int = 14,
    val flowSmoothing: Int = 5,
    val pivotLeft: Int = 3,
    val pivotRight: Int = 3,
    val minPivotSeparation: Int = 5,
    val maxPivotSeparation: Int = 80,
    val minRsiDifference: Double = 2.0,
    val minFlowDifference: Double = 3.0,
    val includeHidden: Boolean = false,
    val minStrength: Int = 40,
    val riskLookback: Int = 14,
    val stopBufferRangeMultiple: Double = 0.25,
    val rewardRisk: Double = 2.0,
) {
    fun sanitized(): RsiOrderFlowStudySettings {
        val minSep = minPivotSeparation.coerceIn(1, 250)
        return copy(
            rsiPeriod = rsiPeriod.coerceIn(2, MAX_PERIOD),
            flowPeriod = flowPeriod.coerceIn(2, MAX_PERIOD),
            flowSmoothing = flowSmoothing.coerceIn(1, 100),
            pivotLeft = pivotLeft.coerceIn(1, 25),
            pivotRight = pivotRight.coerceIn(1, 25),
            minPivotSeparation = minSep,
            maxPivotSeparation = maxPivotSeparation.coerceIn(minSep, 500),
            minRsiDifference = finiteOr(minRsiDifference, 2.0).coerceIn(0.0, 50.0),
            minFlowDifference = finiteOr(minFlowDifference, 3.0).coerceIn(0.0, 50.0),
            minStrength = minStrength.coerceIn(0, 100),
            riskLookback = riskLookback.coerceIn(1, 250),
            stopBufferRangeMultiple = finiteOr(stopBufferRangeMultiple, 0.25).coerceIn(0.0, 5.0),
            rewardRisk = finiteOr(rewardRisk, 2.0).coerceIn(0.25, 10.0),
        )
    }
}

@Immutable
data class MacdStudySettings(
    val fastPeriod: Int = 12,
    val slowPeriod: Int = 26,
    val signalPeriod: Int = 9,
) {
    fun sanitized(): MacdStudySettings {
        val fast = fastPeriod.coerceIn(1, MAX_PERIOD)
        val slow = slowPeriod.coerceIn(2, MAX_PERIOD)
        val resolvedSlow = if (slow <= fast) (fast + 1).coerceAtMost(MAX_PERIOD) else slow
        val resolvedFast = if (fast >= resolvedSlow) (resolvedSlow - 1).coerceAtLeast(1) else fast
        return copy(
            fastPeriod = resolvedFast,
            slowPeriod = resolvedSlow,
            signalPeriod = signalPeriod.coerceIn(1, MAX_PERIOD),
        )
    }
}

@Immutable
data class BollingerStudySettings(
    val period: Int = 20,
    val multiplier: Double = 2.0,
) {
    fun sanitized(): BollingerStudySettings = copy(
        period = period.coerceIn(2, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 2.0).coerceIn(0.1, 10.0),
    )
}

@Immutable
data class SuperTrendStudySettings(
    val atrPeriod: Int = 10,
    val multiplier: Double = 3.0,
) {
    fun sanitized(): SuperTrendStudySettings = copy(
        atrPeriod = atrPeriod.coerceIn(1, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 3.0).coerceIn(0.1, 20.0),
    )
}

@Immutable
data class StochasticStudySettings(
    val kPeriod: Int = 14,
    val dPeriod: Int = 3,
    val overbought: Double = 80.0,
    val oversold: Double = 20.0,
) {
    fun sanitized(): StochasticStudySettings {
        val low = finiteOr(oversold, 20.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 80.0).coerceIn(51.0, 99.0)
        return copy(
            kPeriod = kPeriod.coerceIn(1, MAX_PERIOD),
            dPeriod = dPeriod.coerceIn(1, MAX_PERIOD),
            overbought = high,
            oversold = low,
        )
    }
}

@Immutable
data class KeltnerStudySettings(
    val emaPeriod: Int = 20,
    val atrPeriod: Int = 10,
    val multiplier: Double = 2.0,
) {
    fun sanitized(): KeltnerStudySettings = copy(
        emaPeriod = emaPeriod.coerceIn(1, MAX_PERIOD),
        atrPeriod = atrPeriod.coerceIn(1, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 2.0).coerceIn(0.1, 20.0),
    )
}

@Immutable
data class DonchianStudySettings(val period: Int = 20) {
    fun sanitized(): DonchianStudySettings = copy(period = period.coerceIn(1, MAX_PERIOD))
}

@Immutable
data class IchimokuStudySettings(
    val tenkanPeriod: Int = 9,
    val kijunPeriod: Int = 26,
    val senkouBPeriod: Int = 52,
    val displacement: Int = 26,
) {
    fun sanitized(): IchimokuStudySettings = copy(
        tenkanPeriod = tenkanPeriod.coerceIn(1, MAX_PERIOD),
        kijunPeriod = kijunPeriod.coerceIn(1, MAX_PERIOD),
        senkouBPeriod = senkouBPeriod.coerceIn(1, MAX_PERIOD),
        displacement = displacement.coerceIn(0, MAX_PERIOD),
    )
}

@Immutable
data class ParabolicSarStudySettings(
    val accelerationStart: Double = 0.02,
    val accelerationStep: Double = 0.02,
    val accelerationMax: Double = 0.20,
) {
    fun sanitized(): ParabolicSarStudySettings {
        val start = finiteOr(accelerationStart, 0.02).coerceIn(0.001, 1.0)
        val step = finiteOr(accelerationStep, 0.02).coerceIn(0.001, 1.0)
        val max = finiteOr(accelerationMax, 0.20).coerceIn(start, 2.0)
        return copy(accelerationStart = start, accelerationStep = step, accelerationMax = max)
    }
}

@Immutable
data class MfiStudySettings(
    val period: Int = 14,
    val overbought: Double = 80.0,
    val oversold: Double = 20.0,
) {
    fun sanitized(): MfiStudySettings {
        val low = finiteOr(oversold, 20.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 80.0).coerceIn(51.0, 99.0)
        return copy(period = period.coerceIn(1, MAX_PERIOD), overbought = high, oversold = low)
    }
}

private const val MAX_PERIOD = 500
private fun finiteOr(value: Double, fallback: Double): Double = if (value.isFinite()) value else fallback
