package com.foxtrader.app.domain.usecase.patterns

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandlePatternType
import com.foxtrader.app.domain.model.DetectedPattern
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PatternBias
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects all 28 major candlestick patterns with probability and context.
 * Pure domain logic — no platform dependencies.
 */
class CandlePatternDetector @Inject constructor() {

    /**
     * Scan the last [lookback] candles for candlestick patterns.
     */
    operator fun invoke(candles: List<Candle>, lookback: Int = 50): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        val start = max(2, candles.size - lookback)

        for (i in start until candles.size) {
            val c = candles[i]
            val prev = candles[i - 1]
            val ci = info(c)
            val pi = info(prev)

            // --- Single candle patterns ---
            if (ci.bodyRatio < 0.1 && ci.range > 0)
                addPattern(patterns, CandlePatternType.DOJI, i, c, candles)

            if (ci.lowerRatio > 0.6 && ci.bodyRatio < 0.3 && ci.upperRatio < 0.1 && isDowntrend(candles, i))
                addPattern(patterns, CandlePatternType.HAMMER, i, c, candles)

            if (ci.upperRatio > 0.6 && ci.bodyRatio < 0.3 && ci.lowerRatio < 0.1 && isUptrend(candles, i))
                addPattern(patterns, CandlePatternType.SHOOTING_STAR, i, c, candles)

            if (ci.upperRatio > 0.6 && ci.bodyRatio < 0.3 && ci.lowerRatio < 0.1 && isDowntrend(candles, i))
                addPattern(patterns, CandlePatternType.INVERTED_HAMMER, i, c, candles)

            if (ci.lowerRatio > 0.6 && ci.bodyRatio < 0.3 && ci.upperRatio < 0.1 && isUptrend(candles, i))
                addPattern(patterns, CandlePatternType.HANGING_MAN, i, c, candles)

            if (ci.bodyRatio > 0.9 && ci.isBullish)
                addPattern(patterns, CandlePatternType.MARUBOZU_BULLISH, i, c, candles)

            if (ci.bodyRatio > 0.9 && !ci.isBullish)
                addPattern(patterns, CandlePatternType.MARUBOZU_BEARISH, i, c, candles)

            if (ci.bodyRatio < 0.05 && ci.lowerRatio > 0.7)
                addPattern(patterns, CandlePatternType.DRAGONFLY_DOJI, i, c, candles)

            if (ci.bodyRatio < 0.05 && ci.upperRatio > 0.7)
                addPattern(patterns, CandlePatternType.GRAVESTONE_DOJI, i, c, candles)

            if (ci.bodyRatio < 0.3 && ci.upperRatio > 0.3 && ci.lowerRatio > 0.3)
                addPattern(patterns, CandlePatternType.SPINNING_TOP, i, c, candles)

            // --- Two candle patterns ---
            if (ci.isBullish && !pi.isBullish && ci.body > pi.body * 1.2 && c.close > prev.open && c.open < prev.close)
                addPattern(patterns, CandlePatternType.ENGULFING_BULLISH, i, c, candles)

            if (!ci.isBullish && pi.isBullish && ci.body > pi.body * 1.2 && c.close < prev.open && c.open > prev.close)
                addPattern(patterns, CandlePatternType.ENGULFING_BEARISH, i, c, candles)

            if (!pi.isBullish && ci.isBullish && ci.body < pi.body * 0.5 &&
                min(c.open, c.close) > min(prev.open, prev.close) &&
                max(c.open, c.close) < max(prev.open, prev.close)
            )
                addPattern(patterns, CandlePatternType.HARAMI_BULLISH, i, c, candles)

            if (pi.isBullish && !ci.isBullish && ci.body < pi.body * 0.5 &&
                min(c.open, c.close) > min(prev.open, prev.close) &&
                max(c.open, c.close) < max(prev.open, prev.close)
            )
                addPattern(patterns, CandlePatternType.HARAMI_BEARISH, i, c, candles)

            if (abs(c.high - prev.high) < ci.range * 0.02 && pi.isBullish && !ci.isBullish)
                addPattern(patterns, CandlePatternType.TWEEZER_TOP, i, c, candles)

            if (abs(c.low - prev.low) < ci.range * 0.02 && !pi.isBullish && ci.isBullish)
                addPattern(patterns, CandlePatternType.TWEEZER_BOTTOM, i, c, candles)

            // Piercing Line
            if (!pi.isBullish && ci.isBullish && c.open < prev.low && c.close > (prev.open + prev.close) / 2.0)
                addPattern(patterns, CandlePatternType.PIERCING_LINE, i, c, candles)

            // Dark Cloud Cover
            if (pi.isBullish && !ci.isBullish && c.open > prev.high && c.close < (prev.open + prev.close) / 2.0)
                addPattern(patterns, CandlePatternType.DARK_CLOUD_COVER, i, c, candles)

            // Kickers
            if (!pi.isBullish && ci.isBullish && c.open > prev.open)
                addPattern(patterns, CandlePatternType.BULLISH_KICKER, i, c, candles)

            if (pi.isBullish && !ci.isBullish && c.open < prev.open)
                addPattern(patterns, CandlePatternType.BEARISH_KICKER, i, c, candles)

            // --- Three candle patterns ---
            if (i >= 2) {
                val prev2 = candles[i - 2]
                val p2i = info(prev2)

                // Morning Star
                if (!p2i.isBullish && p2i.bodyRatio > 0.5 && pi.bodyRatio < 0.2 && ci.isBullish && ci.bodyRatio > 0.5)
                    addPattern(patterns, CandlePatternType.MORNING_STAR, i, c, candles)

                // Evening Star
                if (p2i.isBullish && p2i.bodyRatio > 0.5 && pi.bodyRatio < 0.2 && !ci.isBullish && ci.bodyRatio > 0.5)
                    addPattern(patterns, CandlePatternType.EVENING_STAR, i, c, candles)

                // Three White Soldiers
                if (p2i.isBullish && pi.isBullish && ci.isBullish &&
                    prev.close > prev2.close && c.close > prev.close &&
                    p2i.bodyRatio > 0.5 && pi.bodyRatio > 0.5 && ci.bodyRatio > 0.5
                )
                    addPattern(patterns, CandlePatternType.THREE_WHITE_SOLDIERS, i, c, candles)

                // Three Black Crows
                if (!p2i.isBullish && !pi.isBullish && !ci.isBullish &&
                    prev.close < prev2.close && c.close < prev.close &&
                    p2i.bodyRatio > 0.5 && pi.bodyRatio > 0.5 && ci.bodyRatio > 0.5
                )
                    addPattern(patterns, CandlePatternType.THREE_BLACK_CROWS, i, c, candles)

                // Three Inside Up (Harami + bullish confirmation)
                if (!p2i.isBullish && pi.isBullish && ci.isBullish &&
                    pi.body < p2i.body * 0.5 &&
                    min(prev.open, prev.close) > min(prev2.open, prev2.close) &&
                    c.close > max(prev.open, prev.close)
                )
                    addPattern(patterns, CandlePatternType.THREE_INSIDE_UP, i, c, candles)

                // Three Inside Down (Harami + bearish confirmation)
                if (p2i.isBullish && !pi.isBullish && !ci.isBullish &&
                    pi.body < p2i.body * 0.5 &&
                    max(prev.open, prev.close) < max(prev2.open, prev2.close) &&
                    c.close < min(prev.open, prev.close)
                )
                    addPattern(patterns, CandlePatternType.THREE_INSIDE_DOWN, i, c, candles)

                // Abandoned Baby Bullish (gap down doji then gap up)
                if (!p2i.isBullish && pi.bodyRatio < 0.1 &&
                    prev.high < min(prev2.open, prev2.close) &&
                    prev.high < min(c.open, c.close) && ci.isBullish
                )
                    addPattern(patterns, CandlePatternType.ABANDONED_BABY_BULL, i, c, candles)

                // Abandoned Baby Bearish (gap up doji then gap down)
                if (p2i.isBullish && pi.bodyRatio < 0.1 &&
                    prev.low > max(prev2.open, prev2.close) &&
                    prev.low > max(c.open, c.close) && !ci.isBullish
                )
                    addPattern(patterns, CandlePatternType.ABANDONED_BABY_BEAR, i, c, candles)
            }
        }

        return patterns
    }

    // ========================================================================
    // PATTERN METADATA
    // ========================================================================

    private data class PatternMeta(
        val direction: Direction,
        val bias: PatternBias,
        val probability: Int,
        val meaning: String,
    )

    private val META: Map<CandlePatternType, PatternMeta> = mapOf(
        CandlePatternType.DOJI to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 50, "Indecision — buyers and sellers in equilibrium. Look for next candle for direction."),
        CandlePatternType.HAMMER to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 65, "Long lower wick shows rejection of lower prices. Buyers stepped in."),
        CandlePatternType.INVERTED_HAMMER to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 60, "Upper shadow shows buying attempt. Reversal likely if bullish confirmation follows."),
        CandlePatternType.SHOOTING_STAR to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 65, "Long upper wick at a high — sellers rejected higher prices."),
        CandlePatternType.HANGING_MAN to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 60, "Hammer-shaped at a top. Signals potential selling pressure."),
        CandlePatternType.ENGULFING_BULLISH to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 72, "Large bullish candle engulfs prior bearish. Strong demand takeover."),
        CandlePatternType.ENGULFING_BEARISH to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 72, "Large bearish candle engulfs prior bullish. Supply overwhelms demand."),
        CandlePatternType.MORNING_STAR to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 75, "3-candle bottom reversal. Selling exhausts, indecision, then strong buying."),
        CandlePatternType.EVENING_STAR to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 75, "3-candle top reversal. Buying exhausts, indecision, then strong selling."),
        CandlePatternType.THREE_WHITE_SOLDIERS to PatternMeta(Direction.BULLISH, PatternBias.CONTINUATION, 70, "Three consecutive bullish candles with higher closes — strong buying momentum."),
        CandlePatternType.THREE_BLACK_CROWS to PatternMeta(Direction.BEARISH, PatternBias.CONTINUATION, 70, "Three consecutive bearish candles with lower closes — strong selling pressure."),
        CandlePatternType.HARAMI_BULLISH to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 58, "Small body inside prior large bearish — selling momentum fading."),
        CandlePatternType.HARAMI_BEARISH to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 58, "Small body inside prior large bullish — buying momentum fading."),
        CandlePatternType.PIERCING_LINE to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 64, "Bullish candle opens below prior low but closes above 50% of prior body."),
        CandlePatternType.DARK_CLOUD_COVER to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 64, "Bearish candle opens above prior high but closes below 50% of prior body."),
        CandlePatternType.TWEEZER_TOP to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 62, "Two candles with matching highs — double rejection at resistance."),
        CandlePatternType.TWEEZER_BOTTOM to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 62, "Two candles with matching lows — double rejection at support."),
        CandlePatternType.SPINNING_TOP to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 48, "Small body with long wicks — indecision. Low reliability alone."),
        CandlePatternType.MARUBOZU_BULLISH to PatternMeta(Direction.BULLISH, PatternBias.CONTINUATION, 68, "Full body no wicks — extreme bullish conviction."),
        CandlePatternType.MARUBOZU_BEARISH to PatternMeta(Direction.BEARISH, PatternBias.CONTINUATION, 68, "Full body no wicks — extreme bearish conviction."),
        CandlePatternType.DRAGONFLY_DOJI to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 63, "Long lower wick, no upper — strong rejection of low prices."),
        CandlePatternType.GRAVESTONE_DOJI to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 63, "Long upper wick, no lower — strong rejection of high prices."),
        CandlePatternType.THREE_INSIDE_UP to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 67, "Harami followed by bullish confirmation — reversal confirmed."),
        CandlePatternType.THREE_INSIDE_DOWN to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 67, "Harami followed by bearish confirmation — reversal confirmed."),
        CandlePatternType.BULLISH_KICKER to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 78, "Gap up open above prior bearish close — one of the strongest signals."),
        CandlePatternType.BEARISH_KICKER to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 78, "Gap down open below prior bullish close — extremely bearish."),
        CandlePatternType.ABANDONED_BABY_BULL to PatternMeta(Direction.BULLISH, PatternBias.REVERSAL, 76, "Doji gaps below prior and above next candle — rare and very bullish."),
        CandlePatternType.ABANDONED_BABY_BEAR to PatternMeta(Direction.BEARISH, PatternBias.REVERSAL, 76, "Doji gaps above prior and below next — rare and very bearish."),
    )

    // ========================================================================
    // HELPERS
    // ========================================================================

    private data class CandleInfo(
        val body: Double,
        val range: Double,
        val upperWick: Double,
        val lowerWick: Double,
        val isBullish: Boolean,
        val bodyRatio: Double,
        val upperRatio: Double,
        val lowerRatio: Double,
    )

    private fun info(c: Candle): CandleInfo {
        val body = abs(c.close - c.open)
        val range = (c.high - c.low).coerceAtLeast(0.00001)
        val top = max(c.open, c.close)
        val bot = min(c.open, c.close)
        return CandleInfo(
            body = body,
            range = range,
            upperWick = c.high - top,
            lowerWick = bot - c.low,
            isBullish = c.close >= c.open,
            bodyRatio = body / range,
            upperRatio = (c.high - top) / range,
            lowerRatio = (bot - c.low) / range,
        )
    }

    private fun addPattern(
        patterns: MutableList<DetectedPattern>,
        type: CandlePatternType,
        idx: Int,
        candle: Candle,
        candles: List<Candle>,
    ) {
        val meta = META[type] ?: return
        val context = getContext(candles, idx, meta.direction)
        val confidenceBoost = if ("confirms" in context) 8 else 0
        patterns += DetectedPattern(
            type = type,
            direction = meta.direction,
            bias = meta.bias,
            confidence = meta.probability + confidenceBoost,
            probability = meta.probability,
            startIndex = idx - 1,
            endIndex = idx,
            meaning = meta.meaning,
            context = context,
            timestamp = candle.timestamp,
        )
    }

    private fun isUptrend(candles: List<Candle>, idx: Int): Boolean {
        if (idx < 5) return false
        return candles[idx - 1].close > candles[idx - 5].close
    }

    private fun isDowntrend(candles: List<Candle>, idx: Int): Boolean {
        if (idx < 5) return false
        return candles[idx - 1].close < candles[idx - 5].close
    }

    private fun getContext(candles: List<Candle>, idx: Int, direction: Direction): String {
        val uptrend = isUptrend(candles, idx)
        val downtrend = isDowntrend(candles, idx)
        return when {
            direction == Direction.BULLISH && downtrend -> "Appears after downtrend — confirms reversal potential."
            direction == Direction.BEARISH && uptrend -> "Appears after uptrend — confirms reversal potential."
            direction == Direction.BULLISH && uptrend -> "In uptrend — may signal continuation."
            direction == Direction.BEARISH && downtrend -> "In downtrend — may signal continuation."
            else -> "Appears in mixed/ranging conditions — wait for confirmation."
        }
    }
}
