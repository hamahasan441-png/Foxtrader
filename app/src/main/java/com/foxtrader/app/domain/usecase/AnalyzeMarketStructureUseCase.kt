package com.foxtrader.app.domain.usecase

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.MarketStructure
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.SwingPoint
import com.foxtrader.app.domain.model.SwingType
import javax.inject.Inject

/**
 * Detects market structure (BOS / CHOCH) from a candle series.
 *
 * NON-REPAINTING: a swing point is only confirmed once [rightBars] candles have
 * formed after it. Signals never use data from the future relative to their
 * confirmation bar. This is a Kotlin port of the reference TypeScript engine.
 */
class AnalyzeMarketStructureUseCase @Inject constructor() {

    operator fun invoke(
        candles: List<Candle>,
        leftBars: Int = 5,
        rightBars: Int = 5,
    ): MarketStructure {
        if (candles.size < leftBars + rightBars + 1) {
            return MarketStructure(Bias.NEUTRAL, emptyList(), emptyList(), emptyList())
        }

        val highs = mutableListOf<SwingPoint>()
        val lows = mutableListOf<SwingPoint>()

        // --- Confirmed swing detection (no look-ahead beyond rightBars) ---
        for (i in leftBars until candles.size - rightBars) {
            if (isSwingHigh(candles, i, leftBars, rightBars)) {
                highs += SwingPoint(SwingType.HIGH, candles[i].high, candles[i].timestamp, i)
            }
            if (isSwingLow(candles, i, leftBars, rightBars)) {
                lows += SwingPoint(SwingType.LOW, candles[i].low, candles[i].timestamp, i)
            }
        }

        val breaks = detectBreaks(candles, highs, lows)
        val bias = deriveBias(breaks)
        return MarketStructure(bias, highs, lows, breaks)
    }

    private fun isSwingHigh(c: List<Candle>, i: Int, l: Int, r: Int): Boolean {
        val h = c[i].high
        // Strictly greater than everything to the LEFT, so an equal-high
        // plateau (double/triple top, EQH) reports its FIRST bar exactly once.
        for (j in 1..l) if (h <= c[i - j].high) return false
        // Greater-than-or-EQUAL to the right: tolerates equal highs, which are
        // common and structurally meaningful in real markets. Strict `>` here
        // would fail to confirm any peak whose next bar ties its high.
        for (j in 1..r) if (h < c[i + j].high) return false
        return true
    }

    private fun isSwingLow(c: List<Candle>, i: Int, l: Int, r: Int): Boolean {
        val lo = c[i].low
        for (j in 1..l) if (lo >= c[i - j].low) return false
        for (j in 1..r) if (lo > c[i + j].low) return false
        return true
    }

    /** Detect BOS/CHOCH by walking swings chronologically and tracking trend. */
    private fun detectBreaks(
        candles: List<Candle>,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
    ): List<StructureBreak> {
        val breaks = mutableListOf<StructureBreak>()
        val swings = (highs + lows).sortedBy { it.index }
        var trend: Direction? = null
        var lastHigh: SwingPoint? = null
        var lastLow: SwingPoint? = null

        for (s in swings) {
            if (s.type == SwingType.HIGH) {
                lastHigh?.let { prev ->
                    if (s.price > prev.price) {
                        val type = if (trend == Direction.BEARISH) StructureBreakType.CHOCH else StructureBreakType.BOS
                        breaks += StructureBreak(type, Direction.BULLISH, prev.price, s.timestamp, s.index, true)
                        trend = Direction.BULLISH
                    }
                }
                lastHigh = s
            } else {
                lastLow?.let { prev ->
                    if (s.price < prev.price) {
                        val type = if (trend == Direction.BULLISH) StructureBreakType.CHOCH else StructureBreakType.BOS
                        breaks += StructureBreak(type, Direction.BEARISH, prev.price, s.timestamp, s.index, true)
                        trend = Direction.BEARISH
                    }
                }
                lastLow = s
            }
        }
        return breaks
    }

    private fun deriveBias(breaks: List<StructureBreak>): Bias {
        val recent = breaks.takeLast(5)
        if (recent.isEmpty()) return Bias.NEUTRAL
        val bull = recent.count { it.direction == Direction.BULLISH }
        val bear = recent.size - bull
        return when {
            bull > bear -> Bias.BULLISH
            bear > bull -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
    }
}
