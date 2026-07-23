package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject
import kotlin.math.abs

/**
 * Smart Money Technique (SMT) Divergence Detector.
 *
 * SMT divergence occurs when two correlated instruments *disagree* at a key
 * structural level: one makes a higher high (or lower low) while the other
 * fails to confirm. This failure reveals institutional intent — the "strong
 * hand" is distributing/accumulating while retail chases the obvious move.
 *
 * Detection algorithm:
 * 1. Identify recent swing highs/lows on BOTH instruments (fractal, same as
 *    the structure detector uses).
 * 2. Compare the last N swing highs: if instrument A prints a higher high but
 *    instrument B does NOT, that's a **bearish SMT** (distribution).
 * 3. Compare the last N swing lows: if instrument A prints a lower low but
 *    instrument B does NOT, that's a **bullish SMT** (accumulation).
 *
 * NON-REPAINTING: uses only confirmed (fractal) swing points with right-bar
 * confirmation. A confirmed divergence never changes.
 *
 * Typical correlated pairs: EURUSD↔GBPUSD, DXY↔EURUSD (inverse), NAS100↔US500,
 * BTCUSDT↔ETHUSDT.
 */
class SmtDetector @Inject constructor() {

    /**
     * A detected SMT divergence.
     */
    data class SmtDivergence(
        /** Expected reversal direction (bullish = accumulation, bearish = distribution). */
        val direction: Direction,
        /** Price level on the primary instrument where the divergence occurred. */
        val price: Double,
        /** Bar index on the primary instrument. */
        val barIndex: Int,
        /** Timestamp of the divergence bar. */
        val timestamp: Long,
        /** Confidence 0..100 based on the magnitude of the divergence. */
        val confidence: Double,
        /** Human-readable description. */
        val detail: String,
    )

    /**
     * Detect SMT divergences between a primary and a correlated instrument.
     *
     * @param primary The candles for the instrument being traded.
     * @param correlated The candles for the correlated reference instrument.
     *        Must cover the same time range and be the same timeframe.
     * @param leftBars Fractal confirmation lookback (default 3 for faster detection).
     * @param rightBars Fractal confirmation lookahead (default 3).
     * @param lookbackSwings How many recent swing points to compare (default 3).
     * @return List of detected SMT divergences (may be empty).
     */
    fun detect(
        primary: List<Candle>,
        correlated: List<Candle>,
        leftBars: Int = 3,
        rightBars: Int = 3,
        lookbackSwings: Int = 3,
    ): List<SmtDivergence> {
        val minBars = leftBars + rightBars + 1
        if (primary.size < minBars || correlated.size < minBars) return emptyList()

        // Use the shorter series length to keep indices aligned.
        val len = minOf(primary.size, correlated.size)
        val pCandles = primary.takeLast(len)
        val cCandles = correlated.takeLast(len)

        val pHighs = findSwingHighs(pCandles, leftBars, rightBars)
        val pLows = findSwingLows(pCandles, leftBars, rightBars)
        val cHighs = findSwingHighs(cCandles, leftBars, rightBars)
        val cLows = findSwingLows(cCandles, leftBars, rightBars)

        val divergences = mutableListOf<SmtDivergence>()

        // --- Bearish SMT: primary higher high, correlated fails to confirm ---
        checkBearishSmt(pHighs, cHighs, pCandles, lookbackSwings, divergences)

        // --- Bullish SMT: primary lower low, correlated fails to confirm ---
        checkBullishSmt(pLows, cLows, pCandles, lookbackSwings, divergences)

        return divergences
    }

    private fun checkBearishSmt(
        pHighs: List<SwingPt>,
        cHighs: List<SwingPt>,
        pCandles: List<Candle>,
        lookback: Int,
        out: MutableList<SmtDivergence>,
    ) {
        val recentP = pHighs.takeLast(lookback)
        val recentC = cHighs.takeLast(lookback)
        if (recentP.size < 2 || recentC.size < 2) return

        // Check if the latest primary swing high is higher than the previous one...
        val lastP = recentP.last()
        val prevP = recentP[recentP.size - 2]
        if (lastP.price <= prevP.price) return // no higher high on primary

        // ...but the correlated pair's latest swing high is NOT higher.
        val lastC = recentC.last()
        val prevC = recentC[recentC.size - 2]
        if (lastC.price > prevC.price) return // correlated confirmed — no divergence

        // Divergence magnitude = how much the correlated failed relative to primary's push.
        val primaryPush = lastP.price - prevP.price
        val corrFail = prevC.price - lastC.price // positive means it actually went lower
        val magnitude = if (primaryPush > 0) (corrFail + primaryPush) / primaryPush else 0.0
        val confidence = (magnitude * 50.0).coerceIn(30.0, 95.0)

        out += SmtDivergence(
            direction = Direction.BEARISH,
            price = lastP.price,
            barIndex = lastP.index,
            timestamp = pCandles.getOrNull(lastP.index)?.timestamp ?: 0L,
            confidence = confidence,
            detail = "Bearish SMT: primary higher high but correlated failed to confirm",
        )
    }

    private fun checkBullishSmt(
        pLows: List<SwingPt>,
        cLows: List<SwingPt>,
        pCandles: List<Candle>,
        lookback: Int,
        out: MutableList<SmtDivergence>,
    ) {
        val recentP = pLows.takeLast(lookback)
        val recentC = cLows.takeLast(lookback)
        if (recentP.size < 2 || recentC.size < 2) return

        val lastP = recentP.last()
        val prevP = recentP[recentP.size - 2]
        if (lastP.price >= prevP.price) return // no lower low on primary

        val lastC = recentC.last()
        val prevC = recentC[recentC.size - 2]
        if (lastC.price < prevC.price) return // correlated confirmed — no divergence

        val primaryPush = prevP.price - lastP.price
        val corrFail = lastC.price - prevC.price
        val magnitude = if (primaryPush > 0) (corrFail + primaryPush) / primaryPush else 0.0
        val confidence = (magnitude * 50.0).coerceIn(30.0, 95.0)

        out += SmtDivergence(
            direction = Direction.BULLISH,
            price = lastP.price,
            barIndex = lastP.index,
            timestamp = pCandles.getOrNull(lastP.index)?.timestamp ?: 0L,
            confidence = confidence,
            detail = "Bullish SMT: primary lower low but correlated failed to confirm",
        )
    }

    // ========================================================================
    // SWING DETECTION (lightweight fractal — reuses the same logic as
    // AnalyzeMarketStructureUseCase but with shorter lookback for SMT)
    // ========================================================================

    private data class SwingPt(val price: Double, val index: Int)

    private fun findSwingHighs(candles: List<Candle>, l: Int, r: Int): List<SwingPt> {
        val pts = mutableListOf<SwingPt>()
        for (i in l until candles.size - r) {
            val h = candles[i].high
            var isSwing = true
            for (j in 1..l) if (h <= candles[i - j].high) { isSwing = false; break }
            if (!isSwing) continue
            for (j in 1..r) if (h < candles[i + j].high) { isSwing = false; break }
            if (isSwing) pts += SwingPt(h, i)
        }
        return pts
    }

    private fun findSwingLows(candles: List<Candle>, l: Int, r: Int): List<SwingPt> {
        val pts = mutableListOf<SwingPt>()
        for (i in l until candles.size - r) {
            val lo = candles[i].low
            var isSwing = true
            for (j in 1..l) if (lo >= candles[i - j].low) { isSwing = false; break }
            if (!isSwing) continue
            for (j in 1..r) if (lo > candles[i + j].low) { isSwing = false; break }
            if (isSwing) pts += SwingPt(lo, i)
        }
        return pts
    }
}
