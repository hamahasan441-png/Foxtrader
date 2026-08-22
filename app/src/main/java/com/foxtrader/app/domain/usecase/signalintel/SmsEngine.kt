package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmsEventType
import com.foxtrader.app.domain.model.SmsSignal
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * SMS = Smart Money Structure. First-class, non-repainting structure layer that
 * distinguishes BOS, CHOCH and displacement-confirmed MSS and tracks protected
 * swing levels. Signals are stamped on their confirmation bar, not the earlier
 * swing bar where hindsight first makes the pattern visually obvious.
 */
@Singleton
class SmsEngine @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val displacementDetector: DisplacementDetector,
    private val smcDetector: SmcDetector,
) {
    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: SmsConfig = SmsConfig(),
    ): SmsAnalysis {
        val cfg = config.sanitized()
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) return SmsAnalysis.empty(symbol, timeframe, integrity.reason ?: "Invalid market data.")

        val structure = analyzeStructure(candles, cfg.swingBars, cfg.swingBars)
        val latest = structure.breaks.lastOrNull { it.confirmed }
            ?: return SmsAnalysis(symbol, timeframe, structure.bias, null, null, null, "SMS: structure mapped; no confirmed break yet.")

        val confirmationIndex = latest.breakIndex + cfg.swingBars
        if (confirmationIndex !in candles.indices) {
            return SmsAnalysis.empty(symbol, timeframe, "SMS: latest structure event is not confirmed yet.")
        }

        val displacement = displacementDetector.detectLatest(candles, cfg.displacementAtrMultiple)
        val strongMss = latest.type in setOf(StructureBreakType.CHOCH, StructureBreakType.MSS) &&
            displacement != null && displacement.direction == latest.direction &&
            displacement.atrMultiple >= cfg.displacementAtrMultiple &&
            displacement.startIndex in latest.breakIndex..minOf(
                confirmationIndex,
                latest.breakIndex + cfg.maxDisplacementGapBars,
            )

        val eventType = when {
            strongMss -> SmsEventType.MSS
            latest.type == StructureBreakType.CHOCH || latest.type == StructureBreakType.MSS -> SmsEventType.CHOCH
            else -> SmsEventType.BOS
        }

        val protectedHigh = structure.swingHighs.lastOrNull { it.index < latest.breakIndex }?.price
        val protectedLow = structure.swingLows.lastOrNull { it.index < latest.breakIndex }?.price
        val liquidity = smcDetector.detectLiquidity(candles)
        val relevantSweep = liquidity.asSequence()
            .filter { it.swept && it.sweepIndex != null }
            .filter { (it.sweepIndex ?: -1) <= latest.breakIndex }
            .filter {
                when (latest.direction) {
                    Direction.BULLISH -> it.type == LiquidityType.SELL_SIDE
                    Direction.BEARISH -> it.type == LiquidityType.BUY_SIDE
                }
            }
            .maxByOrNull { it.sweepIndex ?: -1 }
        val sweepAligned = relevantSweep?.sweepIndex?.let { latest.breakIndex - it in 0..cfg.maxSweepToShiftBars } == true

        val ageBars = candles.lastIndex - confirmationIndex
        val base = when (eventType) {
            SmsEventType.MSS -> 86
            SmsEventType.CHOCH -> 76
            SmsEventType.BOS -> 66
        }
        var confidence = base + (if (sweepAligned) 7 else 0) + (if (strongMss) 5 else 0)
        if (ageBars > cfg.maxSignalAgeBars) confidence -= (ageBars - cfg.maxSignalAgeBars) * 2
        confidence = confidence.coerceIn(50, 98)

        val confirmations = buildList {
            add(eventType.name)
            if (strongMss) add("DISPLACEMENT")
            if (sweepAligned) add("LIQUIDITY_SWEEP")
            protectedHigh?.let { add("PROTECTED_HIGH") }
            protectedLow?.let { add("PROTECTED_LOW") }
        }

        // Keep analysis context for old events, but only expose a chart signal
        // while the confirmation is recent enough to be actionable.
        val passesRequiredContext = (!cfg.requireLiquiditySweep || sweepAligned) &&
            (!cfg.requireDisplacementForChoch || eventType == SmsEventType.BOS || strongMss) &&
            confidence >= cfg.minConfidence
        val signal = if (ageBars <= cfg.maxSignalAgeBars && passesRequiredContext) {
            SmsSignal(
                symbol = symbol,
                timeframe = timeframe,
                direction = latest.direction,
                type = eventType,
                price = candles[latest.breakIndex].close,
                eventIndex = latest.breakIndex,
                confirmationIndex = confirmationIndex,
                confidence = confidence,
                protectedHigh = protectedHigh,
                protectedLow = protectedLow,
                timestamp = candles[confirmationIndex].timestamp,
                confirmations = confirmations,
                rationale = "SMS ${eventType.name}: confirmed ${latest.direction.name.lowercase()} structure" +
                    if (sweepAligned) " after liquidity sweep." else ".",
            )
        } else null

        return SmsAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            bias = structure.bias,
            signal = signal,
            protectedHigh = protectedHigh,
            protectedLow = protectedLow,
            narrative = signal?.rationale ?: "SMS: latest ${eventType.name} is historical; waiting for a fresh confirmed structure event.",
        )
    }

    private companion object {
        const val MIN_BARS = 40
        const val SWING_BARS = 5
        const val MSS_DISPLACEMENT_ATR = 1.2
        const val MAX_DISPLACEMENT_GAP_BARS = 6
        const val MAX_SWEEP_TO_SHIFT_BARS = 12
        const val LIVE_EVENT_MAX_AGE_BARS = 4
    }
}
