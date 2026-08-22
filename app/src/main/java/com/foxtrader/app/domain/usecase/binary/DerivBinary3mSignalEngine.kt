package com.foxtrader.app.domain.usecase.binary

import com.foxtrader.app.domain.model.BinarySignal
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Non-repainting M1 signal engine intended for 3-minute directional contracts.
 *
 * Design goals:
 * - Evaluate only a CLOSED 1-minute bar.
 * - Trade with the established EMA/DI trend, not against it.
 * - Require a pullback/reclaim rather than chasing an extended candle.
 * - Reject dead markets and one-bar volatility spikes with an ATR regime gate.
 * - Use no volume dependency because Deriv candle history can legitimately have
 *   volume=0 for instruments where exchange volume is unavailable.
 *
 * This is a deterministic research strategy, not a guarantee of profitability.
 */
@Singleton
class DerivBinary3mSignalEngine @Inject constructor() {

    fun evaluate(
        candles: List<Candle>,
        index: Int,
        minConfidence: Int = DEFAULT_MIN_CONFIDENCE,
    ): BinarySignal? {
        if (index < MIN_BARS || index >= candles.size) return null

        val emaFast = TechnicalIndicators.calculateEMA(candles, FAST_EMA)
        val emaMid = TechnicalIndicators.calculateEMA(candles, MID_EMA)
        val emaSlow = TechnicalIndicators.calculateEMA(candles, SLOW_EMA)
        val rsi = TechnicalIndicators.calculateRSI(candles, RSI_PERIOD)
        val atr = TechnicalIndicators.calculateATR(candles, ATR_PERIOD)
        val adx = TechnicalIndicators.calculateADX(candles, ADX_PERIOD)
        val macd = TechnicalIndicators.calculateMACD(candles)

        return evaluatePrepared(
            candles = candles,
            index = index,
            minConfidence = minConfidence,
            emaFast = emaFast,
            emaMid = emaMid,
            emaSlow = emaSlow,
            rsi = rsi,
            atr = atr,
            adx = adx,
            macdHistogram = macd.histogram,
        )
    }

    /** Bulk path used by backtesting so indicators are computed once per series. */
    fun evaluateAll(
        candles: List<Candle>,
        minConfidence: Int = DEFAULT_MIN_CONFIDENCE,
    ): List<BinarySignal> {
        if (candles.size <= MIN_BARS) return emptyList()
        val emaFast = TechnicalIndicators.calculateEMA(candles, FAST_EMA)
        val emaMid = TechnicalIndicators.calculateEMA(candles, MID_EMA)
        val emaSlow = TechnicalIndicators.calculateEMA(candles, SLOW_EMA)
        val rsi = TechnicalIndicators.calculateRSI(candles, RSI_PERIOD)
        val atr = TechnicalIndicators.calculateATR(candles, ATR_PERIOD)
        val adx = TechnicalIndicators.calculateADX(candles, ADX_PERIOD)
        val macdHistogram = TechnicalIndicators.calculateMACD(candles).histogram

        return buildList {
            for (index in MIN_BARS until candles.size) {
                evaluatePrepared(
                    candles = candles,
                    index = index,
                    minConfidence = minConfidence,
                    emaFast = emaFast,
                    emaMid = emaMid,
                    emaSlow = emaSlow,
                    rsi = rsi,
                    atr = atr,
                    adx = adx,
                    macdHistogram = macdHistogram,
                )?.let(::add)
            }
        }
    }

    @Suppress("LongParameterList", "ComplexMethod")
    private fun evaluatePrepared(
        candles: List<Candle>,
        index: Int,
        minConfidence: Int,
        emaFast: DoubleArray,
        emaMid: DoubleArray,
        emaSlow: DoubleArray,
        rsi: DoubleArray,
        atr: DoubleArray,
        adx: TechnicalIndicators.ADXResult,
        macdHistogram: DoubleArray,
    ): BinarySignal? {
        val bar = candles[index]
        val previous = candles[index - 1]
        val atrNow = atr[index]
        if (!isValidBar(bar) || atrNow <= 0.0 || !atrNow.isFinite()) return null

        val range = bar.range
        if (range <= 0.0) return null
        val rangeToAtr = range / atrNow
        if (rangeToAtr !in MIN_RANGE_ATR..MAX_RANGE_ATR) return null

        val bodyRatio = bar.bodySize / range
        val closeLocation = ((bar.close - bar.low) / range).coerceIn(0.0, 1.0)
        val lowerWick = bar.bodyLow - bar.low
        val upperWick = bar.high - bar.bodyHigh
        val midSlopeBars = index - SLOPE_LOOKBACK
        if (midSlopeBars < 0) return null

        val bullishTrend = emaFast[index] > emaMid[index] && emaMid[index] > emaSlow[index]
        val bearishTrend = emaFast[index] < emaMid[index] && emaMid[index] < emaSlow[index]
        val bullishSlope = emaMid[index] > emaMid[midSlopeBars]
        val bearishSlope = emaMid[index] < emaMid[midSlopeBars]
        val adxStrong = adx.adx[index] >= MIN_ADX
        val bullishDi = adx.plusDI[index] > adx.minusDI[index]
        val bearishDi = adx.minusDI[index] > adx.plusDI[index]

        // Pullback must interact with the fast/mid EMA zone, then reclaim fast EMA.
        // ATR tolerance makes this portable across FX and synthetic indices.
        val tolerance = atrNow * EMA_TOUCH_ATR_TOLERANCE
        val bullishPullback = bar.low <= emaFast[index] + tolerance &&
            bar.low >= emaMid[index] - atrNow * MAX_PULLBACK_BELOW_MID_ATR &&
            bar.close > emaFast[index]
        val bearishPullback = bar.high >= emaFast[index] - tolerance &&
            bar.high <= emaMid[index] + atrNow * MAX_PULLBACK_BELOW_MID_ATR &&
            bar.close < emaFast[index]

        val bullishCandle = bar.close > bar.open && closeLocation >= BULL_CLOSE_LOCATION && bodyRatio >= MIN_BODY_RATIO
        val bearishCandle = bar.close < bar.open && closeLocation <= BEAR_CLOSE_LOCATION && bodyRatio >= MIN_BODY_RATIO
        val bullishRejection = lowerWick >= bar.bodySize * MIN_REJECTION_WICK_TO_BODY || bar.close > previous.high
        val bearishRejection = upperWick >= bar.bodySize * MIN_REJECTION_WICK_TO_BODY || bar.close < previous.low

        val bullishRsi = rsi[index] in BULL_RSI_MIN..BULL_RSI_MAX
        val bearishRsi = rsi[index] in BEAR_RSI_MIN..BEAR_RSI_MAX
        val bullishMomentum = macdHistogram[index] > 0.0 && macdHistogram[index] >= macdHistogram[index - 1]
        val bearishMomentum = macdHistogram[index] < 0.0 && macdHistogram[index] <= macdHistogram[index - 1]

        val bullish = bullishTrend && bullishSlope && adxStrong && bullishDi &&
            bullishPullback && bullishCandle && bullishRsi
        val bearish = bearishTrend && bearishSlope && adxStrong && bearishDi &&
            bearishPullback && bearishCandle && bearishRsi

        if (!bullish && !bearish) return null

        val isBull = bullish
        var score = 0
        val reasons = mutableListOf<String>()

        score += 24
        reasons += if (isBull) "EMA 9>21>50 trend" else "EMA 9<21<50 trend"

        score += 8
        reasons += "EMA21 slope aligned"

        val adxValue = adx.adx[index]
        score += when {
            adxValue >= 28.0 -> 16
            adxValue >= 22.0 -> 13
            else -> 10
        }
        reasons += "ADX/DI trend strength"

        score += 17
        reasons += "EMA pullback + reclaim"

        score += if ((isBull && bullishRejection) || (!isBull && bearishRejection)) 13 else 8
        reasons += if ((isBull && bullishRejection) || (!isBull && bearishRejection)) {
            "rejection/expansion candle"
        } else {
            "directional close"
        }

        score += 10
        reasons += "RSI continuation zone"

        if ((isBull && bullishMomentum) || (!isBull && bearishMomentum)) {
            score += 8
            reasons += "MACD momentum confirms"
        }

        // A normal ATR-sized candle is preferred to either noise or a news-like spike.
        score += if (rangeToAtr in IDEAL_RANGE_ATR_MIN..IDEAL_RANGE_ATR_MAX) 7 else 4
        reasons += "ATR regime accepted"

        val confidence = score.coerceIn(0, 100)
        if (confidence < minConfidence.coerceIn(50, 95)) return null

        return BinarySignal(
            signalIndex = index,
            timestamp = bar.timestamp,
            direction = if (isBull) Direction.BULLISH else Direction.BEARISH,
            confidence = confidence,
            setupType = if (isBull) SETUP_CALL else SETUP_PUT,
            reasons = reasons,
        )
    }

    private fun isValidBar(candle: Candle): Boolean {
        if (!candle.open.isFinite() || !candle.high.isFinite() || !candle.low.isFinite() || !candle.close.isFinite()) return false
        if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0) return false
        return candle.high >= maxOf(candle.open, candle.close) &&
            candle.low <= minOf(candle.open, candle.close) &&
            candle.high >= candle.low && candle.timestamp > 0L
    }

    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 72
        const val MIN_BARS = 60
        const val FAST_EMA = 9
        const val MID_EMA = 21
        const val SLOW_EMA = 50
        const val RSI_PERIOD = 14
        const val ATR_PERIOD = 14
        const val ADX_PERIOD = 14
        const val SLOPE_LOOKBACK = 3
        const val MIN_ADX = 16.0
        const val MIN_RANGE_ATR = 0.40
        const val MAX_RANGE_ATR = 2.25
        const val IDEAL_RANGE_ATR_MIN = 0.65
        const val IDEAL_RANGE_ATR_MAX = 1.35
        const val EMA_TOUCH_ATR_TOLERANCE = 0.12
        const val MAX_PULLBACK_BELOW_MID_ATR = 0.45
        const val MIN_BODY_RATIO = 0.32
        const val MIN_REJECTION_WICK_TO_BODY = 0.18
        const val BULL_CLOSE_LOCATION = 0.62
        const val BEAR_CLOSE_LOCATION = 0.38
        const val BULL_RSI_MIN = 51.0
        const val BULL_RSI_MAX = 69.0
        const val BEAR_RSI_MIN = 31.0
        const val BEAR_RSI_MAX = 49.0
        const val SETUP_CALL = "DERIV_3M_CALL_PULLBACK"
        const val SETUP_PUT = "DERIV_3M_PUT_PULLBACK"
    }
}
